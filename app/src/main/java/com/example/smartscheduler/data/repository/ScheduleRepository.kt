package com.example.smartscheduler.data.repository

import androidx.room.withTransaction
import com.example.smartscheduler.data.local.AppDatabase
import com.example.smartscheduler.data.local.dao.RoutineDao
import com.example.smartscheduler.data.local.dao.ScheduleDao
import com.example.smartscheduler.data.local.dao.ImportBatchSummary
import com.example.smartscheduler.data.local.dao.ScheduleDayWithTasks
import com.example.smartscheduler.data.local.dao.TaskOnDate
import com.example.smartscheduler.data.local.entity.*
import com.example.smartscheduler.domain.model.Recurrence
import com.example.smartscheduler.domain.model.TaskKind
import com.example.smartscheduler.notification.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val ALARM_SLOTS = 40

/** ลบรายการที่ซ้ำ: เฉพาะวันนี้ / วันนี้และวันถัดไป / ทุกวัน (วันที่ผ่านมาแล้วไม่แตะเสมอ) */
enum class DeleteScope { THIS, FOLLOWING, ALL }

/** รายการนี้ซ้ำหลายวันหรือไม่ (มาจากกิจวัตรหรือจากตารางที่นำเข้า) */
val ScheduleTaskEntity.isRepeating: Boolean
    get() = importBatchId != null || originTemplateTaskId != null || seriesId != null

/** วันทำซ้ำของรายการนี้ (ถ้าไม่ได้ตั้งเอง ใช้ค่าเริ่มต้นของกิจวัตร) */
fun RoutineTemplateTaskEntity.effectiveDays(template: RoutineTemplateEntity): String = repeatDays ?: template.appliesToDaysOfWeek
fun RoutineTemplateTaskEntity.effectiveMonths(template: RoutineTemplateEntity): String = repeatMonths ?: template.appliesToMonths

fun RoutineTemplateTaskEntity.repeatsOn(date: LocalDate, template: RoutineTemplateEntity): Boolean =
    Recurrence.matches(date, effectiveDays(template), effectiveMonths(template))

private fun RoutineTemplateTaskEntity.toScheduleTask(dayId: Long) = ScheduleTaskEntity(
    dayId = dayId,
    title = title,
    description = description,
    startHour = startHour,
    startMinute = startMinute,
    durationMinutes = durationMinutes,
    sortOrder = sortOrder,
    reminderEnabled = reminderEnabled,
    kind = kind,
    originTemplateTaskId = templateTaskId,
    color = color,
    icon = icon,
    categoryId = categoryId
)

@Singleton
class ScheduleRepository @Inject constructor(
    private val db: AppDatabase,
    private val scheduleDao: ScheduleDao,
    private val routineDao: RoutineDao,
    private val alarmScheduler: AlarmScheduler
) {
    // ------------------------------------------------------------------ reads

    fun observeDay(uid: String, date: LocalDate): Flow<ScheduleDayWithTasks?> =
        scheduleDao.observeDay(uid, date.toEpochDay())

    fun observeRange(uid: String, from: LocalDate, to: LocalDate): Flow<List<ScheduleDayWithTasks>> =
        scheduleDao.observeRange(uid, from.toEpochDay(), to.toEpochDay())

    fun observeActiveTemplate(uid: String): Flow<RoutineTemplateEntity?> =
        routineDao.observeTemplates(uid).map { list -> list.firstOrNull { it.isActiveDefault } }

    fun observeTemplateTasks(templateId: Long): Flow<List<RoutineTemplateTaskEntity>> =
        routineDao.observeTemplateTasks(templateId)

    // ------------------------------------------- standard routine (master template)

    /** สร้างกิจวัตรตัวอย่าง (ทำงาน/พัก) ให้ผู้ใช้ใหม่ จะได้ไม่เจอหน้าว่าง */
    suspend fun ensureDefaultTemplate(uid: String) {
        if (uid.isBlank()) return
        db.withTransaction {
            if (routineDao.getActiveTemplate(uid) != null) return@withTransaction
            val id = routineDao.upsertTemplate(
                RoutineTemplateEntity(
                    ownerUid = uid,
                    name = "กิจวัตรมาตรฐาน",
                    wakeUpHour = 6,
                    wakeUpMinute = 30,
                    isActiveDefault = true,
                    appliesToDaysOfWeek = Recurrence.WEEKDAYS_CSV,
                    appliesToMonths = Recurrence.ALL_MONTHS_CSV
                )
            )
            fun t(title: String, kind: TaskKind, start: Int, end: Int, order: Int) =
                RoutineTemplateTaskEntity(
                    templateId = id, title = title, startHour = start / 60, startMinute = start % 60,
                    durationMinutes = end - start, sortOrder = order, kind = kind.code
                )
            routineDao.upsertTemplateTasks(
                listOf(
                    t("เตรียมตัว / อาหารเช้า", TaskKind.TASK, 7 * 60, 8 * 60 + 30, 0),
                    t("ช่วงทำงานรอบเช้า", TaskKind.WORK, 9 * 60, 12 * 60, 1),
                    t("พักเที่ยง", TaskKind.BREAK, 12 * 60, 13 * 60, 2),
                    t("ช่วงทำงานรอบบ่าย", TaskKind.WORK, 13 * 60, 15 * 60, 3),
                    t("พักเบรกสั้น", TaskKind.BREAK, 15 * 60, 15 * 60 + 15, 4),
                    t("ช่วงทำงานรอบเย็น", TaskKind.WORK, 15 * 60 + 15, 17 * 60 + 30, 5)
                )
            )
        }
        generateRange(uid, LocalDate.now(), 14)
    }

    suspend fun updateTemplateMeta(
        uid: String,
        template: RoutineTemplateEntity,
        wakeUpMinutes: Int,
        daysCsv: String,
        monthsCsv: String
    ) {
        routineDao.updateTemplate(
            template.copy(
                wakeUpHour = wakeUpMinutes / 60,
                wakeUpMinute = wakeUpMinutes % 60,
                appliesToDaysOfWeek = daysCsv,
                appliesToMonths = monthsCsv,
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        )
        afterTemplateChange(uid, template.templateId)
    }

    suspend fun upsertTemplateTask(uid: String, templateId: Long, task: RoutineTemplateTaskEntity) {
        routineDao.upsertTemplateTask(task.copy(templateId = templateId))
        // วันที่ปรับเองแล้วไม่ถูกสร้างใหม่ตามกิจวัตร จึงเปลี่ยนสีให้ตรงด้วย
        if (task.templateTaskId != 0L) {
            scheduleDao.setTemplateCopiesStyle(task.templateTaskId, task.kind, task.categoryId, task.color, task.icon)
        }
        afterTemplateChange(uid, templateId)
    }

    suspend fun deleteTemplateTask(uid: String, templateId: Long, task: RoutineTemplateTaskEntity) =
        deleteTemplateTasks(uid, templateId, listOf(task.templateTaskId))

    /**
     * ลบหลายรายการออกจากกิจวัตร แล้วเอาออกจากทุกวันข้างหน้าด้วย
     * รวมวันที่เคยปรับเอง (ซึ่งปกติกิจวัตรไม่ไปแก้) เพราะผู้ใช้สั่งลบรายการนี้ทั้งหมด
     */
    suspend fun deleteTemplateTasks(uid: String, templateId: Long, templateTaskIds: List<Long>) {
        if (templateTaskIds.isEmpty()) return
        templateTaskIds.forEach { routineDao.deleteTemplateTaskById(it) }
        removeTasks(uid, scheduleDao.findTemplateCopies(uid, templateTaskIds, LocalDate.now().toEpochDay()), restoreRoutine = false)
        afterTemplateChange(uid, templateId)
    }

    /**
     * ลบรายการจากปฏิทินตามขอบเขตที่เลือก
     *  - THIS: เฉพาะวันนี้ (กิจวัตรไม่เปลี่ยน)
     *  - FOLLOWING: คาบนี้ในวันนั้นและวันถัดไปทั้งหมด (ตารางที่นำเข้า)
     *  - ALL: ทุกวันตั้งแต่วันนี้ ถ้ามาจากกิจวัตรจะเอาออกจากกิจวัตรด้วย
     */
    suspend fun deleteTask(uid: String, task: ScheduleTaskEntity, scope: DeleteScope) {
        if (scope == DeleteScope.THIS || !task.isRepeating) return deleteSingleDayTask(task)
        val day = scheduleDao.getDayById(task.dayId) ?: return
        val today = LocalDate.now().toEpochDay()
        val from = if (scope == DeleteScope.FOLLOWING) maxOf(day.dateEpochDay, today) else today
        val batch = task.importBatchId
        val origin = task.originTemplateTaskId
        val series = task.seriesId
        when {
            series != null && batch == null && origin == null -> {
                val hits = scheduleDao.findSeries(uid, series, from)
                val withSelf = if (hits.any { it.taskId == task.taskId }) hits
                else hits + TaskOnDate(task.taskId, task.dayId, day.dateEpochDay)
                removeTasks(uid, withSelf, restoreRoutine = false)
            }
            batch != null -> {
                val weekday = LocalDate.ofEpochDay(day.dateEpochDay).dayOfWeek
                val hits = scheduleDao.findImportedSeries(uid, batch, task.title, task.startHour, task.startMinute, from)
                    .filter { LocalDate.ofEpochDay(it.dateEpochDay).dayOfWeek == weekday }
                // วันที่เลือกอยู่ในอดีตก็ลบอันที่เลือกไว้ด้วย
                val withSelf = if (hits.any { it.taskId == task.taskId }) hits
                else hits + TaskOnDate(task.taskId, task.dayId, day.dateEpochDay)
                removeTasks(uid, withSelf, restoreRoutine = true)
            }
            origin != null -> {
                // กิจวัตรลบได้แค่ "วันนี้" หรือ "ทุกวัน" (วันที่ยังไม่สร้างจะได้ไม่กลับมาอีก)
                val template = routineDao.getActiveTemplate(uid)
                if (template != null) deleteTemplateTasks(uid, template.templateId, listOf(origin))
                else removeTasks(uid, scheduleDao.findTemplateCopies(uid, listOf(origin), from), restoreRoutine = false)
            }
        }
    }

    /**
     * เปลี่ยนหน้าตาทั้งชุด: ประเภท หมวด สี ไอคอน (ไม่ใช่การแก้เวลา จึงให้ตรงกันทุกวัน)
     *  - คาบที่นำเข้า: ทุกคาบชื่อเดียวกันในตารางชุดนั้น (เช่น ระบบเสียง ทั้งเช้าและบ่าย)
     *  - กิจวัตร: รายการกิจวัตร และทุกวันที่สร้างจากรายการนี้
     * [series] = รายการก่อนแก้ (ใช้ชื่อเดิมหาคาบอื่น)  [style] = ค่าใหม่
     */
    suspend fun setSeriesStyle(uid: String, series: ScheduleTaskEntity, style: ScheduleTaskEntity) {
        series.seriesId?.let {
            scheduleDao.setSeriesStyle(uid, it, style.kind, style.categoryId, style.color, style.icon)
        }
        series.importBatchId?.let {
            scheduleDao.setImportedStyle(uid, it, series.title, style.kind, style.categoryId, style.color, style.icon)
        }
        series.originTemplateTaskId?.let { id ->
            routineDao.setStyle(id, style.kind, style.categoryId, style.color, style.icon)
            scheduleDao.setTemplateCopiesStyle(id, style.kind, style.categoryId, style.color, style.icon)
        }
    }

    /**
     * ลบรายการชุดหนึ่ง ยกเลิกเตือน แล้วตั้งเตือนใหม่
     * [restoreRoutine] = วันที่ว่างแล้วสร้างใหม่ตามกิจวัตร (ใช้กับตารางที่นำเข้า ซึ่งเคยแทนที่กิจวัตรไว้)
     */
    private suspend fun removeTasks(uid: String, hits: List<TaskOnDate>, restoreRoutine: Boolean) {
        if (hits.isEmpty()) return
        val byDay = hits.groupBy { it.dayId }
        for ((dayId, list) in byDay) {
            alarmScheduler.cancelForDay(dayId, ALARM_SLOTS)
            scheduleDao.deleteTasksByIds(list.map { it.taskId })
            if (restoreRoutine && scheduleDao.getTasksForDayOnce(dayId).isEmpty()) {
                resetDayToTemplate(uid, LocalDate.ofEpochDay(list.first().dateEpochDay))
            } else {
                rescheduleDay(dayId)
            }
        }
    }

    /** ตั้งวันทำซ้ำเดียวกันให้หลายรายการพร้อมกัน */
    suspend fun setRepeatForTasks(uid: String, templateId: Long, taskIds: List<Long>, daysCsv: String, monthsCsv: String) {
        if (taskIds.isEmpty()) return
        routineDao.setRepeat(taskIds, daysCsv, monthsCsv)
        afterTemplateChange(uid, templateId)
    }

    private suspend fun afterTemplateChange(uid: String, templateId: Long) {
        propagateTemplateToFutureDays(uid, templateId)
        generateRange(uid, LocalDate.now(), 30)
    }

    /**
     * อัปเดตทุกวัน (ตั้งแต่วันนี้เป็นต้นไป) ที่สร้างจาก template นี้และ "ยังไม่ถูกแก้เฉพาะวัน"
     *  - วันที่ไม่ตรงกับกฎ วัน/เดือน ใหม่ → ลบทิ้ง
     *  - วันที่ตรง → สร้างรายการใหม่ตาม template
     *  - วันที่มีงานติ๊กเสร็จแล้ว → ข้าม เพื่อไม่ให้ความคืบหน้าหาย
     */
    suspend fun propagateTemplateToFutureDays(uid: String, templateId: Long) {
        val template = routineDao.getTemplate(templateId) ?: return
        val templateTasks = routineDao.getTemplateTasksOnce(templateId)
        val days = scheduleDao.getFutureUneditedDaysForTemplate(uid, LocalDate.now().toEpochDay(), templateId)
        for (day in days) {
            val oldTasks = scheduleDao.getTasksForDayOnce(day.dayId)
            if (oldTasks.any { it.status != "PENDING" }) continue
            val date = LocalDate.ofEpochDay(day.dateEpochDay)
            alarmScheduler.cancelForDay(day.dayId, ALARM_SLOTS)
            val todays = templateTasks.filter { it.repeatsOn(date, template) }
            if (todays.isNotEmpty()) {
                fillDay(day, template, todays, date)
            } else {
                scheduleDao.deleteDay(day.dayId)
            }
        }
    }

    /** สร้างวันล่วงหน้าตาม template เพื่อให้ตั้งเตือนได้ทันที */
    suspend fun generateRange(uid: String, from: LocalDate, days: Int) {
        for (i in 0 until days) ensureDayGenerated(uid, from.plusDays(i.toLong()))
    }

    suspend fun ensureDayGenerated(uid: String, date: LocalDate) {
        if (uid.isBlank()) return
        db.withTransaction {
            if (scheduleDao.getDayOnce(uid, date.toEpochDay()) != null) return@withTransaction
            val template = routineDao.getActiveTemplate(uid) ?: return@withTransaction
            // แต่ละรายการมีวันทำซ้ำของตัวเอง สร้างวันเฉพาะเมื่อมีอย่างน้อยหนึ่งรายการตรงวันนี้
            val todays = routineDao.getTemplateTasksOnce(template.templateId).filter { it.repeatsOn(date, template) }
            if (todays.isEmpty()) return@withTransaction
            val dayId = scheduleDao.insertDay(
                ScheduleDayEntity(
                    ownerUid = uid,
                    dateEpochDay = date.toEpochDay(),
                    wakeUpHour = template.wakeUpHour,
                    wakeUpMinute = template.wakeUpMinute,
                    sourceTemplateId = template.templateId
                )
            )
            if (dayId == -1L) return@withTransaction
            val day = scheduleDao.getDayById(dayId) ?: return@withTransaction
            fillDay(day, template, todays, date)
        }
    }

    private suspend fun fillDay(
        day: ScheduleDayEntity,
        template: RoutineTemplateEntity,
        templateTasks: List<RoutineTemplateTaskEntity>,
        date: LocalDate
    ) {
        scheduleDao.deleteAllTasksForDay(day.dayId)
        scheduleDao.updateDay(day.copy(wakeUpHour = template.wakeUpHour, wakeUpMinute = template.wakeUpMinute))
        val tasks = templateTasks.map { it.toScheduleTask(day.dayId) }
        scheduleDao.upsertTasks(tasks)
        alarmScheduler.rescheduleForDay(day.dayId, date, template.wakeUpHour, template.wakeUpMinute, tasks)
    }

    // ------------------------------------------------- single-day customization

    /** เพิ่มกิจกรรมในวันนั้นวันเดียว (ถ้ายังไม่มีตารางของวันนั้นจะสร้างให้) ไม่กระทบ template */
    suspend fun addTaskToDate(uid: String, date: LocalDate, task: ScheduleTaskEntity) {
        ensureDayGenerated(uid, date)
        var day = scheduleDao.getDayOnce(uid, date.toEpochDay())
        if (day == null) {
            val template = routineDao.getActiveTemplate(uid)
            scheduleDao.insertDay(
                ScheduleDayEntity(
                    ownerUid = uid,
                    dateEpochDay = date.toEpochDay(),
                    wakeUpHour = template?.wakeUpHour ?: 7,
                    wakeUpMinute = template?.wakeUpMinute ?: 0,
                    sourceTemplateId = null,
                    isCustomized = true
                )
            )
            day = scheduleDao.getDayOnce(uid, date.toEpochDay())
        }
        val dayId = day?.dayId ?: return
        val newId = scheduleDao.upsertTask(task.copy(taskId = 0, dayId = dayId))
        scheduleDao.markCustomized(dayId)
        scheduleDao.insertOverride(ScheduleOverrideEntity(dayId = dayId, taskId = newId, action = "INSERT"))
        rescheduleDay(dayId)
    }

    suspend fun editSingleDayTask(task: ScheduleTaskEntity, previous: ScheduleTaskEntity) {
        scheduleDao.updateTask(task)
        scheduleDao.markCustomized(task.dayId)
        scheduleDao.insertOverride(
            ScheduleOverrideEntity(
                dayId = task.dayId, taskId = task.taskId, action = "EDIT",
                previousValueJson = "${previous.title}|${previous.startHour}:${previous.startMinute}|${previous.durationMinutes}|${previous.kind}"
            )
        )
        rescheduleDay(task.dayId)
    }

    suspend fun deleteSingleDayTask(task: ScheduleTaskEntity) {
        scheduleDao.deleteTask(task)
        scheduleDao.markCustomized(task.dayId)
        scheduleDao.insertOverride(
            ScheduleOverrideEntity(
                dayId = task.dayId, taskId = task.taskId, action = "DELETE",
                previousValueJson = "${task.title}|${task.startHour}:${task.startMinute}|${task.durationMinutes}|${task.kind}"
            )
        )
        rescheduleDay(task.dayId)
    }

    suspend fun markTaskStatus(taskId: Long, status: String) = scheduleDao.updateTaskStatus(taskId, status)

    /** ยกเลิกการแก้เฉพาะวัน แล้วสร้างวันนั้นใหม่ตามกิจวัตรมาตรฐาน */
    suspend fun resetDayToTemplate(uid: String, date: LocalDate) {
        val day = scheduleDao.getDayOnce(uid, date.toEpochDay()) ?: return
        alarmScheduler.cancelForDay(day.dayId, ALARM_SLOTS)
        scheduleDao.deleteDay(day.dayId)
        ensureDayGenerated(uid, date)
    }

    // --------------------------------------------- import weekly timetable (file)

    /** หนึ่งรายการในตารางรายสัปดาห์ ลงทุกวัน [day] ตั้งแต่ from จนถึง [until] */
    data class WeeklyItem(val day: String, val task: ScheduleTaskEntity, val until: LocalDate)

    /**
     * ลงตารางรายสัปดาห์ (เช่น ตารางเรียน) ตั้งแต่ [from]
     * แต่ละรายการมีวันสิ้นสุดของตัวเอง (เช่น วิชาที่สอบสัปดาห์ 17 จะเรียนถึงสัปดาห์ 16)
     * [replaceExisting] = ล้างตารางที่นำเข้าครั้งก่อนทั้งหมดตั้งแต่ [from] (วันที่ว่างแล้วกลับไปใช้กิจวัตร)
     *   แล้วล้างรายการเดิมของวันที่ได้รับรายการใหม่ (เวลาตื่นยังอยู่)
     * ทุกคาบติดป้ายชุดเดียวกัน จึงลบทั้งชุดได้ทีหลังด้วย [removeImported]
     * คืนจำนวนวันที่ได้รับรายการ
     */
    suspend fun importWeekly(
        uid: String,
        items: List<WeeklyItem>,
        from: LocalDate,
        replaceExisting: Boolean
    ): Int {
        val wake = routineDao.getActiveTemplate(uid)?.let { it.wakeUpHour * 60 + it.wakeUpMinute } ?: (7 * 60)
        val limit = from.plusDays(400)
        val batchId = UUID.randomUUID().toString()
        if (replaceExisting) removeImported(uid, batchId = null, from = from)

        // วันที่ → รายการที่ต้องลงในวันนั้น
        val perDate = sortedMapOf<LocalDate, MutableList<ScheduleTaskEntity>>()
        for (item in items) {
            var d = from
            val last = minOf(item.until, limit)
            while (!d.isAfter(last)) {
                if (Recurrence.dayCode(d.dayOfWeek) == item.day) {
                    perDate.getOrPut(d) { mutableListOf() } += item.task.copy(importBatchId = batchId)
                }
                d = d.plusDays(1)
            }
        }

        for ((date, tasks) in perDate) {
            if (replaceExisting) {
                ensureDayGenerated(uid, date)
                scheduleDao.getDayOnce(uid, date.toEpochDay())?.let { day ->
                    alarmScheduler.cancelForDay(day.dayId, ALARM_SLOTS)
                    scheduleDao.deleteAllTasksForDay(day.dayId)
                }
            }
            batchAssign(uid, listOf(date), wake, tasks)
        }
        return perDate.size
    }

    /** ตารางที่นำเข้าไว้ ที่ยังมีคาบตั้งแต่วันนี้เป็นต้นไป */
    fun observeImportBatches(uid: String): Flow<List<ImportBatchSummary>> =
        scheduleDao.observeImportBatches(uid, LocalDate.now().toEpochDay())

    /**
     * ลบคาบที่นำเข้าทั้งชุด ([batchId] = null คือทุกชุด) ตั้งแต่ [from] วันที่ผ่านมาแล้วไม่แตะ
     * วันที่ไม่เหลือรายการเลย (เพราะตอนนำเข้าแทนที่กิจวัตรไว้) จะสร้างใหม่ตามกิจวัตรมาตรฐาน
     * คืนจำนวนวันที่ถูกแก้
     */
    suspend fun removeImported(uid: String, batchId: String?, from: LocalDate = LocalDate.now()): Int {
        val dayIds = scheduleDao.getDaysWithImports(uid, from.toEpochDay(), batchId)
        for (dayId in dayIds) {
            val day = scheduleDao.getDayById(dayId) ?: continue
            alarmScheduler.cancelForDay(dayId, ALARM_SLOTS)
            scheduleDao.deleteImportedTasks(dayId, batchId)
            if (scheduleDao.getTasksForDayOnce(dayId).isEmpty()) {
                resetDayToTemplate(uid, LocalDate.ofEpochDay(day.dateEpochDay))
            } else {
                rescheduleDay(dayId)
            }
        }
        return dayIds.size
    }

    /** ตั้งปลุกใหม่ให้ทุกวันล่วงหน้า (ใช้หลังรีบูตเครื่อง เพราะระบบล้าง alarm ทิ้ง) */
    suspend fun rescheduleUpcomingAlarms(uid: String, days: Int = 30) {
        if (uid.isBlank()) return
        val today = LocalDate.now()
        generateRange(uid, today, days)
        for (i in 0 until days) {
            scheduleDao.getDayOnce(uid, today.plusDays(i.toLong()).toEpochDay())?.let { rescheduleDay(it.dayId) }
        }
    }

    private suspend fun rescheduleDay(dayId: Long) {
        val day = scheduleDao.getDayById(dayId) ?: return
        val tasks = scheduleDao.getTasksForDayOnce(dayId)
        alarmScheduler.rescheduleForDay(
            dayId, LocalDate.ofEpochDay(day.dateEpochDay), day.wakeUpHour, day.wakeUpMinute, tasks
        )
    }

    // --------------------------------------------------- batch / multi-day assign

    /**
     * เพิ่มกิจกรรมชุดเดียวกันให้หลายวัน โดย "เพิ่มต่อท้าย" รายการที่มีอยู่ (ไม่ลบของเดิม)
     * วันที่ยังไม่มีตารางจะถูกสร้างจากกิจวัตรมาตรฐานก่อน (ถ้าตรงกฎวัน/เดือน)
     * เวลาตื่นที่ระบุใช้เฉพาะวันที่ไม่มีกิจวัตรมาตรฐานรองรับ
     */
    /** เพิ่มกิจกรรมเดียวให้ทุกวันตั้งแต่ [from] ถึง [to] (เป็นชุดเดียวกัน ลบ/แก้สีทั้งชุดได้) */
    suspend fun addTaskToRange(uid: String, from: LocalDate, to: LocalDate, task: ScheduleTaskEntity): String {
        val dates = generateSequence(minOf(from, to)) { it.plusDays(1) }.takeWhile { !it.isAfter(maxOf(from, to)) }.take(400).toList()
        val wake = routineDao.getActiveTemplate(uid)?.let { it.wakeUpHour * 60 + it.wakeUpMinute } ?: (7 * 60)
        return batchAssign(uid, dates, wake, listOf(task))
    }

    suspend fun batchAssign(
        uid: String,
        dates: List<LocalDate>,
        wakeUpMinutes: Int,
        tasks: List<ScheduleTaskEntity>
    ): String {
        val batchId = UUID.randomUUID().toString()
        // แต่ละกิจกรรมที่เพิ่มหลายวันเป็นหนึ่งชุด (ลบ/แก้สีทั้งชุดได้) ยกเว้นคาบจากตารางที่นำเข้า ซึ่งมีชุดของตัวเอง
        val seriesTasks = tasks.mapIndexed { i, t ->
            if (t.importBatchId == null && t.originTemplateTaskId == null && t.seriesId == null && dates.size > 1) {
                t.copy(seriesId = "$batchId:$i")
            } else t
        }
        db.withTransaction {
            for (date in dates) {
                ensureDayGenerated(uid, date)
                var day = scheduleDao.getDayOnce(uid, date.toEpochDay())
                if (day == null) {
                    scheduleDao.insertDay(
                        ScheduleDayEntity(
                            ownerUid = uid, dateEpochDay = date.toEpochDay(),
                            wakeUpHour = wakeUpMinutes / 60, wakeUpMinute = wakeUpMinutes % 60,
                            sourceTemplateId = null, isCustomized = true, batchGroupId = batchId
                        )
                    )
                    day = scheduleDao.getDayOnce(uid, date.toEpochDay())
                } else {
                    scheduleDao.updateDay(day.copy(isCustomized = true, batchGroupId = batchId))
                }
                val dayId = day?.dayId ?: continue
                scheduleDao.upsertTasks(seriesTasks.map { it.copy(taskId = 0, dayId = dayId) })
            }
        }
        dates.forEach { d ->
            scheduleDao.getDayOnce(uid, d.toEpochDay())?.let { rescheduleDay(it.dayId) }
        }
        return batchId
    }
}
