package com.example.smartscheduler.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.smartscheduler.domain.model.TaskKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ให้ Claude อ่านรูปหรือ PDF ของตาราง (ตารางเรียน ตารางงาน ตารางเวร ฯลฯ)
 * แล้วคืนรายการคาบรายสัปดาห์ เพื่อให้ผู้ใช้ตรวจก่อนบันทึก
 *
 * ใช้ API key ของผู้ใช้เอง (เก็บในเครื่อง) เพราะแอปนี้ไม่มีเซิร์ฟเวอร์กลาง
 * เอกสาร: https://platform.claude.com/docs
 */
@Singleton
class AiTimetableReader @Inject constructor(
    @ApplicationContext private val context: Context,
    baseClient: OkHttpClient
) {
    private val client = baseClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefs get() = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    @Serializable
    private data class AiEntry(
        val day: String = "",
        val start: String = "",
        val end: String = "",
        val title: String = "",
        val code: String = "",
        val room: String = "",
        val teacher: String = "",
        val kind: String = "CLASS"
    )

    @Serializable
    private data class AiResult(val entries: List<AiEntry> = emptyList(), val note: String = "")

    data class Result(val entries: List<TimetableEntry>, val note: String)

    /** [mime] เช่น image/jpeg, image/png, application/pdf */
    suspend fun read(bytes: ByteArray, mime: String): Result = withContext(Dispatchers.IO) {
        if (!hasApiKey) throw TimetableParseException("ยังไม่ได้ใส่ API key สำหรับให้ AI อ่านตาราง")

        val source: JsonObject = if (mime == "application/pdf") {
            buildJsonObject {
                put("type", "document")
                putJsonObject("source") {
                    put("type", "base64"); put("media_type", "application/pdf")
                    put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            }
        } else {
            val jpeg = shrinkImage(bytes)
            buildJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64"); put("media_type", "image/jpeg")
                    put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                }
            }
        }

        val body = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", 16000)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(source)
                        addJsonObject { put("type", "text"); put("text", PROMPT) }
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseText = client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            when {
                r.code == 401 -> throw TimetableParseException("API key ไม่ถูกต้อง ตรวจแล้วใส่ใหม่")
                r.code == 429 -> throw TimetableParseException("ใช้ AI ถี่เกินไป รอสักครู่แล้วลองใหม่")
                !r.isSuccessful -> throw TimetableParseException("AI อ่านไฟล์ไม่สำเร็จ (${r.code})")
            }
            text
        }

        // คำตอบอยู่ใน content[].text (ข้ามบล็อก thinking ถ้ามี)
        val answer = json.parseToJsonElement(responseText).jsonObject["content"]?.jsonArray
            ?.mapNotNull { block -> block.jsonObject.takeIf { it["type"]?.jsonPrimitive?.content == "text" }?.get("text")?.jsonPrimitive?.content }
            ?.joinToString("\n").orEmpty()
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) throw TimetableParseException("AI ไม่พบตารางในไฟล์นี้")
        val parsed = json.decodeFromString(AiResult.serializer(), answer.substring(start, end + 1))

        val entries = parsed.entries.mapNotNull { e ->
            val day = DayNames.parse(e.day) ?: return@mapNotNull null
            val s = parseClock(e.start) ?: return@mapNotNull null
            val f = parseClock(e.end) ?: return@mapNotNull null
            if (f <= s) return@mapNotNull null
            TimetableEntry(
                day = day, startMin = s, endMin = f,
                title = e.title.ifBlank { e.code }.ifBlank { "ไม่มีชื่อ" },
                code = e.code, room = e.room, teacher = e.teacher,
                kind = TaskKind.values().firstOrNull { it.code.equals(e.kind, true) } ?: TaskKind.CLASS
            )
        }
        if (entries.isEmpty()) throw TimetableParseException("AI ไม่พบคาบเรียนหรือช่วงเวลาในไฟล์นี้")
        Result(entries, parsed.note)
    }

    /** ย่อรูปให้ด้านยาวไม่เกิน 2000px และเป็น JPEG เพื่อให้ส่งเร็วและไม่เกินขนาดที่ API รับ */
    private fun shrinkImage(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw TimetableParseException("เปิดรูปนี้ไม่ได้")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw TimetableParseException("เปิดรูปนี้ไม่ได้")
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f)
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        else decoded
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    companion object {
        private const val KEY_API = "anthropic_api_key"
        private const val MAX_SIDE = 2000
        const val MODEL = "claude-sonnet-5-5"

        private val PROMPT = """
            This file is a weekly timetable (for example a Thai school/college class schedule, a work roster, or a shift plan).
            Extract every scheduled block that repeats weekly.

            Rules:
            - One entry per continuous block. If a subject spans several consecutive periods, merge them into one entry with the start of the first period and the end of the last period. Read the period times from the header row.
            - If the same subject appears twice on one day separated by a gap (such as lunch), output two entries.
            - Fill "title" with the full subject name. When the cell only shows a subject code, look the code up in any subject list on the page.
            - "code" is the subject code if shown, "room" the room or place, "teacher" the teacher's name as written.
            - Include recurring activities such as Home Room or a morning assembly column, on each day they apply.
            - Skip empty cells, signatures, totals, and lunch breaks with no activity.
            - "day" must be one of SUN, MON, TUE, WED, THU, FRI, SAT. Times are 24-hour "HH:MM".
            - "kind" is CLASS for lessons and school activities, WORK for work shifts, BREAK for breaks, TASK for anything else.
            - Keep Thai text exactly as written.

            Reply with JSON only, no prose:
            {"entries":[{"day":"MON","start":"09:15","end":"12:15","title":"...","code":"...","room":"...","teacher":"...","kind":"CLASS"}],"note":"anything you were unsure about, in Thai, or empty"}
        """.trimIndent()
    }
}
