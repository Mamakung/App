package com.example.smartscheduler.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.smartscheduler.R
import com.example.smartscheduler.data.local.entity.ScheduleTaskEntity
import com.example.smartscheduler.settings.AppSettings
import com.example.smartscheduler.settings.SoundLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** ข้อมูลที่หน้าจอปลุกต้องใช้ ส่งผ่าน Intent */
data class AlarmInfo(
    val title: String,
    val range: String,
    val kind: String,
    val icon: String?,
    val color: String?,
    val sound: String?,
    val dateEpochDay: Long
) {
    fun putInto(i: Intent) {
        i.putExtra(EXTRA_TITLE, title); i.putExtra(EXTRA_RANGE, range); i.putExtra(EXTRA_KIND, kind)
        i.putExtra(EXTRA_ICON, icon); i.putExtra(EXTRA_COLOR, color); i.putExtra(EXTRA_SOUND, sound)
        i.putExtra(EXTRA_DATE, dateEpochDay)
    }

    companion object {
        const val EXTRA_ID = "alarm_id"
        private const val EXTRA_TITLE = "alarm_title"
        private const val EXTRA_RANGE = "alarm_range"
        private const val EXTRA_KIND = "alarm_kind"
        private const val EXTRA_ICON = "alarm_icon"
        private const val EXTRA_COLOR = "alarm_color"
        private const val EXTRA_SOUND = "alarm_sound"
        private const val EXTRA_DATE = "alarm_date"

        fun from(i: Intent) = AlarmInfo(
            title = i.getStringExtra(EXTRA_TITLE) ?: "ถึงเวลาแล้ว",
            range = i.getStringExtra(EXTRA_RANGE) ?: "",
            kind = i.getStringExtra(EXTRA_KIND) ?: "TASK",
            icon = i.getStringExtra(EXTRA_ICON),
            color = i.getStringExtra(EXTRA_COLOR),
            sound = i.getStringExtra(EXTRA_SOUND),
            dateEpochDay = i.getLongExtra(EXTRA_DATE, LocalDate.now().toEpochDay())
        )

        fun of(task: ScheduleTaskEntity, date: LocalDate): AlarmInfo {
            val start = task.startHour * 60 + task.startMinute
            val end = start + task.durationMinutes
            return AlarmInfo(
                title = task.title,
                range = "%02d:%02d–%02d:%02d".format(task.startHour, task.startMinute, (end / 60) % 24, end % 60),
                kind = task.kind,
                icon = task.icon,
                color = task.color,
                sound = task.alarmSound,
                dateEpochDay = date.toEpochDay()
            )
        }
    }
}

/** สถานะกำลังปลุก ให้หน้าจอปลุกปิดตัวเองเมื่อหยุด/เลื่อนจากที่อื่น (เช่น ปุ่มในแจ้งเตือน) */
object AlarmState {
    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()
    internal fun set(v: Boolean) { _ringing.value = v }
}

/** ถึงเวลาปลุก: เริ่มเซอร์วิสเล่นเสียง (เริ่มจาก exact alarm ได้แม้แอปอยู่เบื้องหลัง) */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            putExtras(intent)
        }
        ContextCompat.startForegroundService(context, svc)
    }
}

/**
 * เล่นเสียงปลุกวนจนกดหยุด (ระดับเสียงนาฬิกาปลุก ดังแม้โหมดเงียบ) + สั่น
 * แสดงแจ้งเตือนเต็มจอเปิดหน้าจอปลุก มีปุ่ม หยุด / เลื่อน ในแจ้งเตือนด้วย
 * ไฟล์เสียงเล่นไม่ได้ → ใช้เสียงปลุกค่าเริ่มต้นแทน ไม่ปล่อยให้เงียบ / ดังนานสุด 10 นาที
 */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var info: AlarmInfo? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(AlarmInfo.from(intent))
            ACTION_SNOOZE -> { snooze(); stopAll() }
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun start(i: AlarmInfo) {
        info = i
        NotificationChannels.registerAll(this)
        val notif = buildNotification(i)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIF_ID, notif)
        AlarmState.set(true)
        play(i.sound)
        if (AppSettings.prefs(this).getBoolean(AppSettings.KEY_VIBRATE, true)) vibrate()
        handler.postDelayed({ stopAll() }, 10 * 60 * 1000L)
    }

    private fun play(soundKey: String?) {
        player?.release()
        val lib = SoundLibrary(this)
        val key = soundKey ?: AppSettings.prefs(this).getString(AppSettings.KEY_ALARM_SOUND, null) ?: AppSettings.DEFAULT_ALARM_SOUND
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        fun tryPlay(k: String): Boolean = runCatching {
            val uri = lib.uriOf(k) ?: return false
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
            true
        }.getOrDefault(false)
        if (!tryPlay(key)) tryPlay(AppSettings.DEFAULT_ALARM_SOUND)
    }

    private fun vibrate() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        val pattern = longArrayOf(0, 600, 500, 600, 1200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        else @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
    }

    private fun snooze() {
        val i = info ?: return
        val minutes = AppSettings.prefs(this).getInt(AppSettings.KEY_SNOOZE, 5)
        AlarmScheduler(applicationContext).scheduleAlarmAtMillis(
            SNOOZE_CODE, System.currentTimeMillis() + minutes * 60_000L,
            i.copy(title = i.title)
        )
    }

    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }; player?.release(); player = null
        vibrator?.cancel(); vibrator = null
        AlarmState.set(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        player?.release(); vibrator?.cancel(); AlarmState.set(false)
        super.onDestroy()
    }

    private fun buildNotification(i: AlarmInfo): Notification {
        val full = Intent(this, AlarmActivity::class.java).apply {
            i.putInto(this)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
        val fullPi = PendingIntent.getActivity(this, 1, full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, AlarmService::class.java).setAction(a),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeMin = AppSettings.prefs(this).getInt(AppSettings.KEY_SNOOZE, 5)
        return NotificationCompat.Builder(this, NotificationChannels.ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(i.title)
            .setContentText(i.range)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .addAction(0, "เลื่อน $snoozeMin นาที", action(ACTION_SNOOZE, 2))
            .addAction(0, "หยุด", action(ACTION_STOP, 3))
            .build()
    }

    companion object {
        const val ACTION_START = "alarm.START"
        const val ACTION_STOP = "alarm.STOP"
        const val ACTION_SNOOZE = "alarm.SNOOZE"
        private const val NOTIF_ID = 777_001
        private const val SNOOZE_CODE = 777_002

        fun send(context: Context, action: String) {
            context.startService(Intent(context, AlarmService::class.java).setAction(action))
        }
    }
}
