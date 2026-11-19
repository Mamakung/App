package com.example.smartscheduler.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.smartscheduler.data.local.dao.NoteDao
import com.example.smartscheduler.data.local.entity.NoteEntity
import com.example.smartscheduler.data.local.entity.NoteFolderEntity
import com.example.smartscheduler.data.session.CurrentUser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** ชิ้นส่วนของบันทึก: ข้อความ (หลายบรรทัด มีรูปแบบในบรรทัด) / รูป / ไฟล์แนบ */
sealed class NoteBlock {
    abstract val id: String
    data class Text(override val id: String, val text: String) : NoteBlock()
    data class Image(override val id: String, val path: String) : NoteBlock()
    data class Attachment(override val id: String, val path: String, val name: String, val size: Long) : NoteBlock()
}

object NoteDoc {
    // คำนำหน้าบรรทัด (เก็บในข้อความตรงๆ)
    const val HEADING = "# "
    const val CHECK = "☐ "
    const val CHECKED = "☑ "
    const val BULLET = "• "
    val prefixes = listOf(HEADING, CHECK, CHECKED, BULLET)

    fun newId() = UUID.randomUUID().toString().take(8)

    fun parse(body: String): List<NoteBlock> {
        if (body.isBlank()) return listOf(NoteBlock.Text(newId(), ""))
        val out = mutableListOf<NoteBlock>()
        runCatching {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                when (o.optString("t")) {
                    "img" -> out += NoteBlock.Image(newId(), o.getString("p"))
                    "file" -> out += NoteBlock.Attachment(newId(), o.getString("p"), o.optString("n"), o.optLong("s"))
                    else -> out += NoteBlock.Text(newId(), o.optString("x"))
                }
            }
        }.onFailure { return listOf(NoteBlock.Text(newId(), body)) }
        return normalize(out)
    }

    fun serialize(blocks: List<NoteBlock>): String {
        val arr = JSONArray()
        blocks.forEach { b ->
            arr.put(
                when (b) {
                    is NoteBlock.Text -> JSONObject().put("t", "text").put("x", b.text)
                    is NoteBlock.Image -> JSONObject().put("t", "img").put("p", b.path)
                    is NoteBlock.Attachment -> JSONObject().put("t", "file").put("p", b.path).put("n", b.name).put("s", b.size)
                }
            )
        }
        return arr.toString()
    }

    /** มีข้อความคั่นระหว่างรูป/ไฟล์เสมอ และขึ้นต้น/ลงท้ายด้วยข้อความ (ให้พิมพ์ต่อได้) */
    fun normalize(blocks: List<NoteBlock>): List<NoteBlock> {
        val out = mutableListOf<NoteBlock>()
        for (b in blocks) {
            val last = out.lastOrNull()
            if (b is NoteBlock.Text && last is NoteBlock.Text) {
                out[out.lastIndex] = last.copy(text = listOf(last.text, b.text).filter { it.isNotEmpty() }.joinToString("\n"))
            } else {
                if (b !is NoteBlock.Text && last !is NoteBlock.Text) out += NoteBlock.Text(newId(), "")
                out += b
            }
        }
        if (out.firstOrNull() !is NoteBlock.Text) out.add(0, NoteBlock.Text(newId(), ""))
        if (out.lastOrNull() !is NoteBlock.Text) out += NoteBlock.Text(newId(), "")
        return out
    }

    /** ข้อความล้วน (ไว้ค้นหา/แสดงตัวอย่าง) */
    fun plain(body: String): String = parse(body).filterIsInstance<NoteBlock.Text>().joinToString("\n") { it.text }
        .lines().joinToString("\n") { line -> prefixes.fold(line) { l, p -> l.removePrefix(p) } }
        .replace("**", "").replace("_", "")

    fun checks(body: String): Pair<Int, Int> {
        val lines = parse(body).filterIsInstance<NoteBlock.Text>().flatMap { it.text.lines() }
        val done = lines.count { it.startsWith(CHECKED) }
        return done to (done + lines.count { it.startsWith(CHECK) })
    }
}

@Singleton
class NoteRepository @Inject constructor(
    private val dao: NoteDao,
    private val currentUser: CurrentUser,
    @ApplicationContext private val context: Context
) {
    val uid get() = currentUser.uid

    fun observeFolders(): Flow<List<NoteFolderEntity>> = dao.observeFolders(uid)
    fun observeNotes(): Flow<List<NoteEntity>> = dao.observeNotes(uid)
    suspend fun getNote(id: Long) = dao.getNote(id)

    suspend fun saveFolder(folder: NoteFolderEntity): Long = dao.upsertFolder(folder.copy(ownerUid = uid))
    suspend fun saveNote(note: NoteEntity): Long = dao.upsertNote(note.copy(ownerUid = uid))
    suspend fun moveNote(noteId: Long, folderId: Long?) = dao.moveNote(noteId, folderId)
    suspend fun moveFolder(folderId: Long, parentId: Long?) = dao.moveFolder(folderId, parentId)

    suspend fun deleteNote(id: Long) {
        dao.deleteNote(id)
        withContext(Dispatchers.IO) { noteDir(id).deleteRecursively() }
    }

    /** ลบโฟลเดอร์พร้อมโฟลเดอร์ย่อยและทุกรายการข้างใน */
    suspend fun deleteFolder(folderId: Long) {
        val all = dao.allFolders(uid)
        val ids = mutableSetOf(folderId)
        var grew = true
        while (grew) {
            val more = all.filter { it.parentId in ids && it.folderId !in ids }.map { it.folderId }
            grew = more.isNotEmpty(); ids += more
        }
        val notes = dao.notesIn(uid, ids.toList())
        notes.forEach { n -> withContext(Dispatchers.IO) { noteDir(n.noteId).deleteRecursively() } }
        dao.deleteNotes(notes.map { it.noteId })
        dao.deleteFolders(ids.toList())
    }

    // ---------- ไฟล์แนบ (เก็บใน filesDir/notes/<noteId>/) ----------

    fun noteDir(noteId: Long) = File(context.filesDir, "notes/$noteId")
    fun fileOf(relPath: String) = File(context.filesDir, relPath)

    /** คัดลอกไฟล์จากโทรศัพท์มาเก็บในบันทึก คืน (ทางเก็บ, ชื่อไฟล์, ขนาด) */
    suspend fun copyIn(noteId: Long, uri: Uri): Triple<String, String, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            val cr = context.contentResolver
            var name = "file"
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            val ext = name.substringAfterLast('.', "").take(8).let { if (it.isEmpty()) "" else ".$it" }
            val dir = noteDir(noteId).apply { mkdirs() }
            val target = File(dir, NoteDoc.newId() + ext)
            cr.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
            Triple("notes/$noteId/${target.name}", name, target.length())
        }.getOrNull()
    }
}
