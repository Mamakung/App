package com.example.smartscheduler.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.smartscheduler.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ----------------------------------------------------------------------------
// GitHub Releases API (https://api.github.com/repos/{owner}/{repo}/releases/latest)
// ----------------------------------------------------------------------------

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList()
)

/** repo ยังไม่มี Release (หรือเป็น private) จึงไม่มีอะไรให้เทียบ */
class NoReleaseYetException : Exception("no release published")

/** เวอร์ชันใหม่ที่พร้อมให้ติดตั้ง */
data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long
)

/**
 * ระบบอัปเดตในแอป (สำหรับแอปที่ติดตั้งเอง ไม่ผ่าน Play Store)
 *
 * GitHub Actions สร้าง Release ทุกครั้งที่ push โดยตั้งชื่อ tag เป็น v1.0.<versionCode>
 * แล้วแนบไฟล์ APK ไว้ แอปจะอ่าน Release ล่าสุด เทียบ versionCode กับตัวเอง
 * ถ้าใหม่กว่าก็ดาวน์โหลด APK แล้วเปิดหน้าติดตั้งของ Android
 *
 * หมายเหตุ: repo ต้องเป็น public เพราะแอปอ่าน Release โดยไม่ใช้ token
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    baseClient: OkHttpClient
) {
    private val client = baseClient.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    private val apkFile: File get() = File(File(context.cacheDir, "updates").apply { mkdirs() }, "update.apk")

    /** คืนค่าเวอร์ชันใหม่ถ้ามี, null ถ้าเป็นเวอร์ชันล่าสุดแล้ว; โยน exception ถ้าเชื่อมต่อไม่ได้ */
    suspend fun checkForUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SmartScheduler-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw NoReleaseYetException() // ยังไม่มี release หรือ repo เป็น private
            if (!response.isSuccessful) error("GitHub ตอบกลับ ${response.code}")
            val release = json.decodeFromString(GithubRelease.serializer(), response.body!!.string())
            val code = versionCodeFromTag(release.tag) ?: return@withContext null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@withContext null
            if (code <= currentVersionCode) return@withContext null
            AppUpdate(
                versionCode = code,
                versionName = release.name?.removePrefix("SmartScheduler ")?.ifBlank { null } ?: release.tag,
                notes = release.body?.trim().orEmpty(),
                apkUrl = apk.downloadUrl,
                sizeBytes = apk.size
            )
        }
    }

    /** ดาวน์โหลด APK พร้อมรายงานความคืบหน้า 0f..1f */
    suspend fun download(update: AppUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = apkFile
        val partial = File(target.parentFile, "update.apk.part")
        val request = Request.Builder().url(update.apkUrl).header("User-Agent", "SmartScheduler-Android").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("ดาวน์โหลดไม่สำเร็จ (${response.code})")
            val body = response.body ?: error("ไฟล์ว่าง")
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (target.exists()) target.delete()
        partial.renameTo(target)
        target
    }

    /** Android 8+ ต้องให้ผู้ใช้อนุญาต "ติดตั้งแอปที่ไม่รู้จัก" สำหรับแอปนี้ก่อน */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** เปิดหน้าติดตั้งของระบบ (ผู้ใช้ต้องกด "อัปเดต" อีกครั้งในหน้านั้น) */
    fun installIntent(file: File = apkFile): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        /** เลขท้าย tag คือ versionCode: "build-445210" → 445210, "v1.0.42" → 42 */
        fun versionCodeFromTag(tag: String): Int? = tag.takeLastWhile { it.isDigit() }.toIntOrNull()
    }
}
