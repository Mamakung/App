package com.example.smartscheduler.data.backup

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smartscheduler.MainActivity
import com.example.smartscheduler.data.local.AppDatabase
import com.example.smartscheduler.data.session.CurrentUser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * สำรอง/กู้คืนข้อมูลทั้งหมดเป็นไฟล์ zip เดียว
 *  - db/  ฐานข้อมูล (ปฏิทิน กิจวัตร หมวด สมุด)
 *  - prefs/  ตั้งค่า (ไม่รวมค่าที่ผูกกับเครื่อง เช่น ไอคอนวันที่ / อัปเดต)
 *  - files/notes, files/alarm_sounds  รูป ไฟล์แนบ PDF เสียงปลุก
 *  - meta.json  เวอร์ชันและจำนวนรายการ
 * กู้คืนได้ 2 แบบ: รวมกับข้อมูลที่มี / แทนที่ทั้งหมด (แล้วเริ่มแอปใหม่)
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val currentUser: CurrentUser
) {
    data class Counts(val tasks: Int, val notes: Int, val pdfs: Int, val folders: Int, val categories: Int)
    data class Meta(val created: Long, val dbVersion: Int, val counts: Counts, val sizeBytes: Long)

    private val prefsToBackup = listOf("settings", "quick", "sounds", "calendar", "widgets", "pdf_reader")
    private val fileDirs = listOf("notes", "alarm_sounds")
    private val state = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    val lastBackupAt get() = state.getLong("last_time", 0L)
    val lastBackupSize get() = state.getLong("last_size", 0L)
    var remind: Boolean
        get() = state.getBoolean("remind", true)
        set(v) { state.edit().putBoolean("remind", v).apply() }

    fun suggestedName(): String = "SmartScheduler-สำรอง-${java.time.LocalDate.now()}.zip"

    private fun countsOf(d: SupportSQLiteDatabase): Counts {
        fun n(sql: String) = runCatching { d.query(sql).use { if (it.moveToFirst()) it.getInt(0) else 0 } }.getOrDefault(0)
        return Counts(
            tasks = n("SELECT COUNT(*) FROM schedule_tasks"),
            notes = n("SELECT COUNT(*) FROM notes WHERE kind = 'NOTE'"),
            pdfs = n("SELECT COUNT(*) FROM notes WHERE kind = 'PDF'"),
            folders = n("SELECT COUNT(*) FROM note_folders"),
            categories = n("SELECT COUNT(*) FROM categories")
        )
    }

    // ---------- สำรอง ----------

    suspend fun export(target: Uri, onProgress: (Float) -> Unit): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val w = db.openHelper.writableDatabase
            runCatching { w.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() } }
            val counts = countsOf(w)
            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val files = fileDirs.flatMap { dir -> File(context.filesDir, dir).walkTopDown().filter { it.isFile }.map { dir to it }.toList() }
            val total = (dbFile.length() + files.sumOf { it.second.length() }).coerceAtLeast(1)
            var done = 0L
            context.contentResolver.openOutputStream(target, "w")!!.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    fun put(name: String, file: File) {
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val r = input.read(buf); if (r < 0) break
                                zip.write(buf, 0, r); done += r
                                onProgress((done.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                        zip.closeEntry()
                    }
                    put("db/${AppDatabase.DATABASE_NAME}", dbFile)
                    val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                    prefsToBackup.forEach { p -> File(prefsDir, "$p.xml").takeIf { it.exists() }?.let { put("prefs/$p.xml", it) } }
                    files.forEach { (dir, f) -> put("files/$dir/" + f.relativeTo(File(context.filesDir, dir)).path, f) }
                    val meta = JSONObject()
                        .put("app", "SmartScheduler").put("created", System.currentTimeMillis())
                        .put("dbVersion", w.version)
                        .put("tasks", counts.tasks).put("notes", counts.notes).put("pdfs", counts.pdfs)
                        .put("folders", counts.folders).put("categories", counts.categories)
                    zip.putNextEntry(ZipEntry("meta.json")); zip.write(meta.toString().toByteArray()); zip.closeEntry()
                }
            }
            val size = context.contentResolver.openFileDescriptor(target, "r")?.use { it.statSize } ?: total
            state.edit().putLong("last_time", System.currentTimeMillis()).putLong("last_size", size).apply()
            size
        }
    }

    // ---------- อ่านไฟล์ก่อนกู้คืน ----------

    private val restoreDir get() = File(context.cacheDir, "restore")

    /** แตกไฟล์ไว้ก่อน แล้วอ่านข้อมูลสรุป (null = ไม่ใช่ไฟล์สำรองของแอปนี้) */
    suspend fun open(source: Uri): Result<Meta> = withContext(Dispatchers.IO) {
        runCatching {
            restoreDir.deleteRecursively(); restoreDir.mkdirs()
            var size = 0L
            context.contentResolver.openInputStream(source)!!.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val out = File(restoreDir, e.name).canonicalFile
                        // กันชื่อไฟล์ที่พยายามเขียนออกนอกโฟลเดอร์
                        require(out.path.startsWith(restoreDir.canonicalPath + File.separator)) { "bad entry" }
                        if (e.isDirectory) { out.mkdirs(); continue }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { size += zip.copyTo(it) }
                    }
                }
            }
            val metaFile = File(restoreDir, "meta.json")
            require(metaFile.exists() && File(restoreDir, "db/${AppDatabase.DATABASE_NAME}").exists()) { "not a backup" }
            val m = JSONObject(metaFile.readText())
            require(m.optString("app") == "SmartScheduler") { "not a backup" }
            Meta(
                created = m.optLong("created"),
                dbVersion = m.optInt("dbVersion"),
                counts = Counts(m.optInt("tasks"), m.optInt("notes"), m.optInt("pdfs"), m.optInt("folders"), m.optInt("categories")),
                sizeBytes = size
            )
        }
    }

    fun currentDbVersion(): Int = db.openHelper.readableDatabase.version

    // ---------- กู้คืนแบบแทนที่ทั้งหมด ----------

    /** แทนที่ทุกอย่างด้วยของในไฟล์ แล้วเริ่มแอปใหม่ (ฐานข้อมูลเวอร์ชันเก่าจะถูกอัปเกรดตอนเปิด) */
    suspend fun replaceAll(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val src = File(restoreDir, "db/${AppDatabase.DATABASE_NAME}")
            require(src.exists())
            db.close()
            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
            src.copyTo(dbFile, overwrite = true)
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(restoreDir, "prefs").listFiles()?.forEach { it.copyTo(File(prefsDir, it.name), overwrite = true) }
            fileDirs.forEach { dir ->
                File(context.filesDir, dir).deleteRecursively()
                File(restoreDir, "files/$dir").takeIf { it.exists() }?.copyRecursively(File(context.filesDir, dir), overwrite = true)
            }
            restoreDir.deleteRecursively()
            Unit
        }
    }

    /** เริ่มแอปใหม่หลังกู้คืนแบบแทนที่ */
    fun restartApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        Runtime.getRuntime().exit(0)
    }

    // ---------- กู้คืนแบบรวม ----------

    /** เพิ่มของในไฟล์เข้าไปในข้อมูลที่มี (ของเดิมไม่หาย ของที่ซ้ำกันไม่เพิ่มซ้ำ) */
    suspend fun merge(): Result<Counts> = withContext(Dispatchers.IO) {
        runCatching {
            val srcFile = File(restoreDir, "db/${AppDatabase.DATABASE_NAME}")
            val src = SQLiteDatabase.openDatabase(srcFile.path, null, SQLiteDatabase.OPEN_READONLY)
            val uid = currentUser.uid
            var tasksAdded = 0; var notesAdded = 0; var pdfAdded = 0; var foldersAdded = 0; var catsAdded = 0
            try {
                db.runInTransaction(Runnable {
                    val dst = db.openHelper.writableDatabase
                    val catMap = HashMap<Long, Long>()
                    val tplMap = HashMap<Long, Long>()
                    val tplTaskMap = HashMap<Long, Long>()
                    val dayMap = HashMap<Long, Long>()
                    val folderMap = HashMap<Long, Long>()

                    // หมวด: ชื่อ+ประเภทเดียวกัน = ใช้ของเดิม
                    src.eachRow("categories") { row, id ->
                        val existing = dst.longOrNull("SELECT categoryId FROM categories WHERE name = ? AND kind = ?", row.getAsString("name"), row.getAsString("kind"))
                        catMap[id] = existing ?: dst.insertRow("categories", row.withUid(uid)).also { catsAdded++ }
                    }
                    // กิจวัตร: ชื่อเดียวกัน = ข้าม
                    val newTemplates = HashSet<Long>()
                    src.eachRow("routine_templates") { row, id ->
                        val existing = dst.longOrNull("SELECT templateId FROM routine_templates WHERE name = ?", row.getAsString("name"))
                        if (existing != null) tplMap[id] = existing
                        else { tplMap[id] = dst.insertRow("routine_templates", row.withUid(uid)); newTemplates += id }
                    }
                    src.eachRow("routine_template_tasks") { row, id ->
                        val oldTpl = row.getAsLong("templateId") ?: return@eachRow
                        if (oldTpl !in newTemplates) return@eachRow
                        row.put("templateId", tplMap[oldTpl])
                        row.remap("categoryId", catMap)
                        tplTaskMap[id] = dst.insertRow("routine_template_tasks", row)
                    }
                    // วัน: วันที่เดียวกัน = ใช้วันเดิม
                    src.eachRow("schedule_days") { row, id ->
                        val date = row.getAsLong("dateEpochDay")
                        val existing = dst.longOrNull("SELECT dayId FROM schedule_days WHERE ownerUid = ? AND dateEpochDay = ?", uid, date.toString())
                        dayMap[id] = existing ?: run {
                            row.remap("sourceTemplateId", tplMap)
                            dst.insertRow("schedule_days", row.withUid(uid))
                        }
                    }
                    // กิจกรรม: วันเดียวกัน ชื่อ+เวลาเดียวกัน = ข้าม
                    src.eachRow("schedule_tasks") { row, _ ->
                        val day = dayMap[row.getAsLong("dayId")] ?: return@eachRow
                        val dup = dst.longOrNull(
                            "SELECT taskId FROM schedule_tasks WHERE dayId = ? AND title = ? AND startHour = ? AND startMinute = ?",
                            day.toString(), row.getAsString("title"), row.getAsString("startHour"), row.getAsString("startMinute")
                        )
                        if (dup != null) return@eachRow
                        row.put("dayId", day)
                        row.remap("categoryId", catMap)
                        row.remap("originTemplateTaskId", tplTaskMap)
                        dst.insertRow("schedule_tasks", row); tasksAdded++
                    }
                    // โฟลเดอร์: ใส่ก่อนแล้วค่อยต่อชั้น (ชื่อเดียวกันในชั้นเดียวกัน = ใช้ของเดิม)
                    val parents = HashMap<Long, Long?>()
                    val folderRows = ArrayList<Pair<Long, ContentValues>>()
                    src.eachRow("note_folders") { row, id -> parents[id] = row.getAsLong("parentId"); folderRows += id to row }
                    fun ensureFolder(id: Long): Long? {
                        folderMap[id]?.let { return it }
                        val row = folderRows.firstOrNull { it.first == id }?.second ?: return null
                        val parentNew = parents[id]?.let { ensureFolder(it) }
                        val existing = dst.longOrNull(
                            if (parentNew == null) "SELECT folderId FROM note_folders WHERE name = ? AND parentId IS NULL" else "SELECT folderId FROM note_folders WHERE name = ? AND parentId = ?",
                            *listOfNotNull(row.getAsString("name"), parentNew?.toString()).toTypedArray()
                        )
                        val newId = existing ?: run {
                            val v = ContentValues(row); v.put("parentId", parentNew)
                            dst.insertRow("note_folders", v.withUid(uid)).also { foldersAdded++ }
                        }
                        folderMap[id] = newId
                        return newId
                    }
                    folderRows.forEach { ensureFolder(it.first) }
                    // บันทึก/PDF: ชื่อ+เวลาสร้างเดียวกัน = ข้าม; คัดลอกไฟล์แนบไปโฟลเดอร์ใหม่
                    src.eachRow("notes") { row, id ->
                        val dup = dst.longOrNull("SELECT noteId FROM notes WHERE title = ? AND createdAt = ?", row.getAsString("title"), row.getAsString("createdAt"))
                        if (dup != null) return@eachRow
                        row.remap("folderId", folderMap)
                        val newId = dst.insertRow("notes", row.withUid(uid))
                        val from = File(restoreDir, "files/notes/$id")
                        if (from.exists()) from.copyRecursively(File(context.filesDir, "notes/$newId"), overwrite = true)
                        val fix = ContentValues()
                        row.getAsString("body")?.let { fix.put("body", it.replace("notes/$id/", "notes/$newId/")) }
                        row.getAsString("filePath")?.let { fix.put("filePath", it.replace("notes/$id/", "notes/$newId/")) }
                        if (fix.size() > 0) dst.update("notes", SQLiteDatabase.CONFLICT_NONE, fix, "noteId = ?", arrayOf(newId))
                        if (row.getAsString("kind") == "PDF") pdfAdded++ else notesAdded++
                    }
                })
            } finally {
                src.close()
                restoreDir.deleteRecursively()
            }
            Counts(tasksAdded, notesAdded, pdfAdded, foldersAdded, catsAdded)
        }
    }

    // ---------- ตัวช่วย SQL ----------

    private val pkOf = mapOf(
        "categories" to "categoryId", "routine_templates" to "templateId", "routine_template_tasks" to "templateTaskId",
        "schedule_days" to "dayId", "schedule_tasks" to "taskId", "note_folders" to "folderId", "notes" to "noteId"
    )

    /** อ่านทุกแถวของตาราง (ถ้ามี) เป็น ContentValues ไม่รวมคีย์หลัก */
    private fun SQLiteDatabase.eachRow(table: String, block: (ContentValues, Long) -> Unit) {
        val exists = rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
        if (!exists) return
        val pk = pkOf[table]!!
        rawQuery("SELECT * FROM `$table`", null).use { c ->
            while (c.moveToNext()) {
                val v = ContentValues()
                var id = 0L
                for (i in 0 until c.columnCount) {
                    val name = c.getColumnName(i)
                    if (name == pk) { id = c.getLong(i); continue }
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> v.putNull(name)
                        Cursor.FIELD_TYPE_INTEGER -> v.put(name, c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> v.put(name, c.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> v.put(name, c.getBlob(i))
                        else -> v.put(name, c.getString(i))
                    }
                }
                block(v, id)
            }
        }
    }

    /** ใส่แถว โดยใช้เฉพาะคอลัมน์ที่ตารางปลายทางมี (ไฟล์สำรองรุ่นเก่า/ใหม่กว่าก็ใส่ได้) */
    private fun SupportSQLiteDatabase.insertRow(table: String, values: ContentValues): Long {
        val cols = query("PRAGMA table_info(`$table`)").use { c -> buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
        val v = ContentValues()
        values.keySet().filter { it in cols }.forEach { k ->
            when (val x = values.get(k)) {
                null -> v.putNull(k)
                is Long -> v.put(k, x); is Int -> v.put(k, x); is Double -> v.put(k, x)
                is ByteArray -> v.put(k, x); else -> v.put(k, x.toString())
            }
        }
        return insert(table, SQLiteDatabase.CONFLICT_IGNORE, v)
    }

    private fun SupportSQLiteDatabase.longOrNull(sql: String, vararg args: String?): Long? =
        query(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    private fun ContentValues.withUid(uid: String) = apply { if (containsKey("ownerUid")) put("ownerUid", uid) }

    private fun ContentValues.remap(col: String, map: Map<Long, Long>) {
        if (!containsKey(col)) return
        val old = getAsLong(col) ?: return
        val new = map[old]
        if (new != null) put(col, new) else putNull(col)
    }
}
