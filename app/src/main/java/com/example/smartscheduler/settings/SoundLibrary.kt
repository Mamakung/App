package com.example.smartscheduler.settings

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * เสียงทั้งหมดที่ใช้ได้ เก็บเป็น key ข้อความ:
 *  - "app:<ชื่อไฟล์ใน res/raw>"  เสียงที่มากับแอป
 *  - "file:<id>"                 ไฟล์ที่ผู้ใช้เพิ่มเอง (คัดลอกเก็บในแอป ไม่หายแม้ลบต้นฉบับ)
 *  - "uri:<content uri>"         เสียงในโทรศัพท์ (RingtoneManager)
 */
class SoundLibrary(private val context: Context) {

    data class Sound(val key: String, val label: String, val detail: String)

    private val prefs = context.getSharedPreferences("sounds", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "alarm_sounds").apply { mkdirs() }

    fun appSounds(alarm: Boolean): List<Sound> = if (alarm) ALARM_SOUNDS else NOTIFY_SOUNDS

    // ---------- ไฟล์ของฉัน ----------

    data class Custom(val id: String, val name: String, val file: String, val durationMs: Long)

    fun customSounds(): List<Custom> {
        val arr = runCatching { JSONArray(prefs.getString(KEY_CUSTOM, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Custom(o.optString("id"), o.optString("name"), o.optString("file"), o.optLong("ms"))
        }.filter { File(dir, it.file).exists() }
    }

    private fun save(list: List<Custom>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("file", it.file).put("ms", it.durationMs)) }
        prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    /** คัดลอกไฟล์ที่ผู้ใช้เลือกเข้ามาเก็บในแอป คืน key หรือ null ถ้าอ่านไม่ได้ */
    fun addCustom(uri: Uri): String? = runCatching {
        val name = displayName(uri) ?: "เสียงของฉัน"
        val ext = name.substringAfterLast('.', "mp3").lowercase().take(5)
        val id = UUID.randomUUID().toString().take(8)
        val file = "$id.$ext"
        context.contentResolver.openInputStream(uri)?.use { input ->
            File(dir, file).outputStream().use { input.copyTo(it) }
        } ?: return null
        val ms = runCatching {
            MediaMetadataRetriever().run {
                setDataSource(File(dir, file).absolutePath)
                val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                release(); d
            }
        }.getOrDefault(0L)
        if (ms <= 0L) { File(dir, file).delete(); return null } // ไม่ใช่ไฟล์เสียงที่เล่นได้
        save(customSounds() + Custom(id, name.substringBeforeLast('.'), file, ms))
        "file:$id"
    }.getOrNull()

    fun rename(id: String, newName: String) =
        save(customSounds().map { if (it.id == id) it.copy(name = newName.trim().ifBlank { it.name }) else it })

    fun delete(id: String) {
        customSounds().firstOrNull { it.id == id }?.let { File(dir, it.file).delete() }
        save(customSounds().filter { it.id != id })
    }

    // ---------- แปลง key ----------

    fun uriOf(key: String?): Uri? = when {
        key == null -> null
        key.startsWith("app:") -> Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/raw/${key.removePrefix("app:")}")
        key.startsWith("file:") -> customSounds().firstOrNull { it.id == key.removePrefix("file:") }?.let { Uri.fromFile(File(dir, it.file)) }
        key.startsWith("uri:") -> Uri.parse(key.removePrefix("uri:"))
        else -> null
    }

    fun labelOf(key: String?): String = when {
        key == null -> "ตามตั้งค่า"
        key.startsWith("app:") -> (ALARM_SOUNDS + NOTIFY_SOUNDS).firstOrNull { it.key == key }?.label ?: "เสียงของแอป"
        key.startsWith("file:") -> customSounds().firstOrNull { it.id == key.removePrefix("file:") }?.name ?: "ไฟล์ที่ถูกลบไปแล้ว"
        key.startsWith("uri:") -> runCatching {
            RingtoneManager.getRingtone(context, Uri.parse(key.removePrefix("uri:")))?.getTitle(context)
        }.getOrNull() ?: "เสียงในโทรศัพท์"
        else -> "ไม่รู้จัก"
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        private const val KEY_CUSTOM = "custom"

        val NOTIFY_SOUNDS = listOf(
            Sound("app:snd_n1_sai", "ใส", "สองโน้ตไล่ขึ้น ได้ยินชัด"),
            Sound("app:snd_n2_pop", "ป๊อป", "สั้นมาก เหมือนฟองแตก"),
            Sound("app:snd_n3_ranat", "ระนาด", "ไม้ตีสามโน้ต"),
            Sound("app:snd_n4_bell", "กระดิ่ง", "กระดิ่งใส ก้องนิดหน่อย"),
            Sound("app:snd_n5_soft", "นุ่ม", "เบาและนุ่ม")
        )
        val ALARM_SOUNDS = listOf(
            Sound("app:snd_a1_morning", "เช้าสดใส", "ระนาดไล่โน้ตวน"),
            Sound("app:snd_a2_digital", "ดิจิทัล", "ปี๊ปๆ คลาสสิก"),
            Sound("app:snd_a3_bell", "ระฆัง", "ระฆังทุ้มสองจังหวะ"),
            Sound("app:snd_a4_birds", "นกร้อง", "นกจิ๊บๆ"),
            Sound("app:snd_a5_gentle", "ค่อยๆ ดัง", "เริ่มเบาแล้วดังขึ้น")
        )
    }
}
