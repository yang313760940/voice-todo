package com.voicetodo.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ReminderOutcome {
    /** 用户没有设置提醒，不需要系统调度。 */
    NotRequested,
    /** 提醒已真正排入系统。 */
    Scheduled,
    /** 用户设置了提醒，但权限或系统限制导致未排入。 */
    NotReady,
}

/** 保存/取消结账结果：区分“已保存”与“提醒是否已成功排入系统”。 */
data class ConfirmResult(
    val item: TodoItem,
    val outcome: ReminderOutcome,
)

data class RestoreResult(
    val item: TodoItem,
    val outcome: ReminderOutcome,
    val pastReminderCleared: Boolean,
)

data class TodoEdit(
    val title: String,
    val plannedDate: LocalDate?,
    val dueAt: Instant?,
    val remindAt: Instant?,
    val reminders: List<ReminderEdit>? = null,
)

data class UpdateResult(
    val item: TodoItem,
    val reminderChanged: Boolean,
    val outcome: ReminderOutcome?,
)

data class TodoDraftInput(
    val itemId: String,
    val edit: TodoEdit,
    val quadrant: Quadrant = Quadrant.NotImportantNotUrgent,
    val recurrence: RecurrenceRule? = null,
)

/**
 * 统一用例入口（应用层 seam）。界面通过它读取列表和提交用户操作；
 * 失败（空标题、过去时刻）抛 [IllegalArgumentException]，调用方在保存前拦截。
 *
 * 保存与提醒分别反馈：即使提醒未就绪，事项仍保存，[ConfirmResult.outcome] 如实返回 NotReady，
 * 调用方据此提示“提醒未就绪”，绝不误报已安排。
 */
class TodoUseCases(
    private val repo: TodoRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    private val mutations = Mutex()
    /** 全部校验 → 一个数据库事务 → 系统调度。重试使用同一草稿身份。 */
    suspend fun confirmDrafts(drafts: List<TodoDraftInput>): List<ConfirmResult> = mutations.withLock {
        require(drafts.isNotEmpty()) { "请至少保留一条草稿" }
        require(drafts.map { it.itemId }.distinct().size == drafts.size) { "草稿身份不能重复" }
        val now = clock.now()
        val items = drafts.map { draft ->
            require(draft.itemId.isNotBlank()) { "草稿身份不能为空" }
            repo.findById(draft.itemId)?.let { return@map it }
            val edit = draft.edit
            val title = edit.title.trim()
            require(title.isNotEmpty()) { "标题不能为空" }
            val rules = edit.reminders.orEmpty().ifEmpty {
                edit.remindAt?.let { listOf(ReminderEdit(id = draft.itemId, at = it)) }.orEmpty()
            }
            validateReminderRules(rules)
            val reminders = rules.map { rule ->
                val at = resolveReminderTime(rule, edit.dueAt)
                require(at.isAfter(now)) { "提醒时间必须晚于当前时间" }
                TodoReminder(rule.id, at, rule.beforeDue)
            }
            TodoItem(id = draft.itemId, title = title, plannedDate = edit.plannedDate,
                dueAt = edit.dueAt, remindAt = reminders.firstOrNull()?.at, reminders = reminders,
                createdAt = now, quadrant = draft.quadrant, recurrence = draft.recurrence?.validate())
        }
        val saved = repo.insertDrafts(items)
        saved.map { item ->
            // 已保存/完成的事项不会被重复确认改写；只有未就绪提醒可重试。
            if (item.completedAt != null || item.reminders.none { it.outcome == ReminderOutcome.NotReady }) {
                ConfirmResult(item, outcomeFor(item))
            } else {
                val (scheduled, outcome) = scheduleMultiple(item)
                ConfirmResult(scheduled, outcome)
            }
        }
    }
    suspend fun listItems(): List<TodoItem> = repo.all()
    suspend fun listIncomplete(): List<TodoItem> = repo.incomplete()
    suspend fun listCompleted(): List<TodoItem> = repo.completed()
    suspend fun findItem(id: String): TodoItem =
        repo.findById(id) ?: throw IllegalArgumentException("事项不存在")
    suspend fun wasDelivered(item: TodoItem): Boolean = if (item.reminders.isEmpty()) {
        repo.deliveredAt(item.id, item.reminderRevision) != null
    } else item.reminders.any { repo.deliveredAt(item.id, it.id, it.revision) != null }

    suspend fun reconcileReminders() = mutations.withLock {
        val now = clock.now()
        for (item in repo.incomplete()) {
            if (item.reminders.isNotEmpty()) {
                val pending = item.reminders.map { reminder ->
                    if (reminder.at.isAfter(now) && repo.deliveredAt(item.id, reminder.id, reminder.revision) == null)
                        reminder.copy(outcome = ReminderOutcome.NotReady) else reminder
                }
                scheduleMultiple(item.copy(reminders = pending))
                continue
            }
            val at = item.remindAt
            if (at != null && at.isAfter(now) && !wasDelivered(item)) schedule(item)
        }
    }

    suspend fun updateItem(id: String, edit: TodoEdit): UpdateResult = mutations.withLock {
        val item = repo.findById(id) ?: throw IllegalArgumentException("事项不存在")
        require(item.completedAt == null) { "已完成事项请先恢复再编辑" }
        val title = edit.title.trim()
        require(title.isNotEmpty()) { "标题不能为空" }
        val rules = edit.reminders ?: item.reminders.takeIf { it.isNotEmpty() }?.map {
            ReminderEdit(it.id, at = if (it.beforeDue == null) it.at else null, beforeDue = it.beforeDue)
        }
        if (rules != null) return@withLock updateMultiple(item, title, edit, rules)
        val changed = edit.remindAt != item.remindAt
        if (changed) edit.remindAt?.let {
            require(it.isAfter(clock.now())) { "提醒时间必须晚于当前时间" }
        }
        val updated = item.copy(
            title = title, plannedDate = edit.plannedDate, dueAt = edit.dueAt,
            remindAt = edit.remindAt,
            reminderRevision = item.reminderRevision + if (changed) 1 else 0,
        )
        repo.save(updated)
        if (!changed) return@withLock UpdateResult(updated, false, null)
        scheduler.cancel(id)
        val outcome = schedule(updated)
        UpdateResult(updated, true, outcome)
    }

    private suspend fun updateMultiple(
        item: TodoItem, title: String, edit: TodoEdit, rules: List<ReminderEdit>,
    ): UpdateResult {
        validateReminderRules(rules)
        val oldReminders = if (item.reminders.isNotEmpty()) item.reminders else item.remindAt?.let {
            listOf(TodoReminder(item.id, it, revision = item.reminderRevision))
        }.orEmpty()
        val nextRevision = item.reminderRevision + 1
        val reminders = rules.map { rule ->
            val at = resolveReminderTime(rule, edit.dueAt)
            val old = oldReminders.firstOrNull { it.id == rule.id }
            if (old != null && old.at == at && old.beforeDue == rule.beforeDue) old
            else TodoReminder(rule.id, at, rule.beforeDue, revision = nextRevision)
        }
        val changed = reminders != oldReminders
        reminders.forEach { reminder ->
            if (oldReminders.none { it == reminder }) require(reminder.at.isAfter(clock.now())) {
                "提醒时间必须晚于当前时间"
            }
        }
        val updated = item.copy(
            title = title, plannedDate = edit.plannedDate, dueAt = edit.dueAt,
            remindAt = reminders.firstOrNull()?.at, reminders = reminders,
            reminderRevision = if (changed) nextRevision else item.reminderRevision,
        )
        repo.save(updated)
        oldReminders.filter { old -> reminders.none { it == old } }.forEach {
            scheduler.cancelReminder(item.id, it.id)
        }
        val retrying = reminders.any { it.outcome == ReminderOutcome.NotReady }
        val (scheduled, outcome) = scheduleMultiple(updated)
        return UpdateResult(scheduled, changed, if (changed || retrying) outcome else null)
    }

    private suspend fun scheduleMultiple(item: TodoItem): Pair<TodoItem, ReminderOutcome> {
        val reminders = item.reminders.map { reminder ->
            if (reminder.outcome != ReminderOutcome.NotReady || reminder.deliveredAt != null ||
                !reminder.at.isAfter(clock.now())) reminder else reminder.copy(
                outcome = if (try {
                    scheduler.scheduleReminder(item.id, item.title, reminder.id, reminder.at, reminder.revision)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    false
                }) ReminderOutcome.Scheduled else ReminderOutcome.NotReady)
        }
        val scheduled = item.copy(reminders = reminders)
        repo.save(scheduled)
        val outcome = when {
            reminders.isEmpty() -> ReminderOutcome.NotRequested
            reminders.any { it.outcome == ReminderOutcome.NotReady && it.deliveredAt == null } -> ReminderOutcome.NotReady
            else -> ReminderOutcome.Scheduled
        }
        return scheduled to outcome
    }

    suspend fun retryReminders(id: String): ConfirmResult = mutations.withLock {
        val item = findItem(id)
        require(item.completedAt == null) { "已完成事项不能重试提醒" }
        val normalized = if (item.reminders.isEmpty() && item.remindAt != null) {
            if (wasDelivered(item)) return@withLock ConfirmResult(item, ReminderOutcome.Scheduled)
            item.copy(reminders = listOf(TodoReminder(item.id, item.remindAt,
                revision = item.reminderRevision)))
        } else item
        normalized.reminders.filter { it.outcome == ReminderOutcome.NotReady }.forEach {
            require(it.at.isAfter(clock.now())) { "提醒已过期，请先重新安排" }
        }
        repo.save(normalized)
        val (scheduled, outcome) = scheduleMultiple(normalized)
        ConfirmResult(scheduled, outcome)
    }

    suspend fun deliverReminder(
        itemId: String, reminderId: String, revision: Long, atMillis: Long,
        publish: suspend (TodoItem) -> Boolean,
    ): Boolean = mutations.withLock {
        repo.deliverReminder(itemId, reminderId, revision, atMillis, clock::now, publish)
    }

    private fun schedule(item: TodoItem): ReminderOutcome {
        val at = item.remindAt ?: return ReminderOutcome.NotRequested
        return if (scheduler.schedule(item.id, item.title, at, item.reminderRevision)) {
            ReminderOutcome.Scheduled
        } else ReminderOutcome.NotReady
    }

    /** 用户确认一个草稿：校验 → 持久化 → 请求系统闹钟（按真实结果反馈）。 */
    suspend fun confirmDraft(
        title: String,
        remindAt: Instant? = null,
        plannedDate: LocalDate? = null,
        dueAt: Instant? = null,
        reminders: List<ReminderEdit> = emptyList(),
        quadrant: Quadrant = Quadrant.NotImportantNotUrgent,
        itemId: String = UUID.randomUUID().toString(),
    ): ConfirmResult = mutations.withLock {
        val clean = title.trim()
        require(clean.isNotEmpty()) { "标题不能为空" }

        // 同一草稿重复确认时返回已创建事项，不再保存或重复排闹钟。
        repo.findById(itemId)?.let { existing ->
            return@withLock ConfirmResult(existing, outcomeFor(existing))
        }

        if (reminders.isNotEmpty()) {
            validateReminderRules(reminders)
            val now = clock.now()
            val normalized = reminders.map { rule ->
                val at = resolveReminderTime(rule, dueAt)
                require(at.isAfter(now)) { "提醒时间必须晚于当前时间" }
                TodoReminder(rule.id, at, rule.beforeDue)
            }
            val item = TodoItem(
                id = itemId,
                title = clean,
                remindAt = normalized.first().at,
                plannedDate = plannedDate,
                dueAt = dueAt,
                createdAt = now,
                reminders = normalized,
                quadrant = quadrant,
            )
            repo.save(item)
            val (scheduled, outcome) = scheduleMultiple(item)
            return@withLock ConfirmResult(scheduled, outcome)
        }

        remindAt?.let { require(it.isAfter(clock.now())) { "提醒时间必须晚于当前时间" } }

        val item = TodoItem(
            id = itemId,
            title = clean,
            remindAt = remindAt,
            plannedDate = plannedDate,
            dueAt = dueAt,
            createdAt = clock.now(),
            quadrant = quadrant,
        )
        repo.save(item)
        // 调度失败（缺权限/系统拒绝）不抛异常、不回滚事项，但如实返回 NotReady。
        val outcome = if (remindAt == null) {
            ReminderOutcome.NotRequested
        } else if (scheduler.schedule(item.id, item.title, remindAt, item.reminderRevision)) {
            ReminderOutcome.Scheduled
        } else {
            ReminderOutcome.NotReady
        }
        ConfirmResult(item, outcome)
    }

    private fun outcomeFor(item: TodoItem): ReminderOutcome = when {
        item.reminders.isEmpty() ->
            if (item.remindAt == null) ReminderOutcome.NotRequested else ReminderOutcome.Scheduled
        item.reminders.any { it.outcome == ReminderOutcome.NotReady && it.deliveredAt == null } ->
            ReminderOutcome.NotReady
        else -> ReminderOutcome.Scheduled
    }

    private fun validateReminderRules(rules: List<ReminderEdit>) {
        require(rules.map { it.id }.distinct().size == rules.size) { "同一事项的提醒身份不能重复" }
        rules.forEach { rule ->
            require(rule.id.isNotBlank()) { "提醒身份不能为空" }
            require((rule.at == null) != (rule.beforeDue == null)) { "请选择一种提醒：指定时刻或相对截止" }
            rule.beforeDue?.let { require(!it.isNegative && !it.isZero) { "提前时长必须大于零" } }
        }
    }

    private fun resolveReminderTime(rule: ReminderEdit, dueAt: Instant?): Instant =
        rule.at ?: requireNotNull(dueAt) { "相对提醒需要截止时间" }
            .minus(requireNotNull(rule.beforeDue) { "请选择指定时刻或相对提醒" })

    /** 用户取消确认：不创建事项、不调度。 */
    fun cancelDraft() {
        // 无副作用
    }

    /** 标记事项完成并撤销提醒；已完成事项保持原完成时刻，并再次确保提醒已取消。 */
    suspend fun completeItem(id: String): TodoItem = mutations.withLock {
        val item = repo.findById(id) ?: throw IllegalArgumentException("事项不存在")
        if (item.completedAt != null) {
            scheduler.cancel(id)
            return@withLock item
        }

        val completed = item.copy(
            completedAt = clock.now(),
            reminderRevision = item.reminderRevision + 1,
            reminders = item.reminders.map { it.copy(outcome = ReminderOutcome.NotRequested) },
        )
        repo.save(completed)
        scheduler.cancel(id)
        completed
    }

    suspend fun restoreItem(id: String): RestoreResult = mutations.withLock {
        val item = repo.findById(id) ?: throw IllegalArgumentException("事项不存在")
        if (item.completedAt == null) {
            return@withLock RestoreResult(
                item = item,
                outcome = ReminderOutcome.NotRequested,
                pastReminderCleared = false,
            )
        }

        val now = clock.now()
        if (item.reminders.isNotEmpty()) {
            val revision = item.reminderRevision + 1
            val future = item.reminders.filter { it.at.isAfter(now) }.map {
                it.copy(revision = revision, outcome = ReminderOutcome.NotReady, deliveredAt = null)
            }
            val restored = item.copy(completedAt = null, remindAt = future.firstOrNull()?.at,
                reminderRevision = revision, reminders = future)
            repo.save(restored)
            scheduler.cancel(id)
            val (scheduled, outcome) = scheduleMultiple(restored)
            return@withLock RestoreResult(scheduled, outcome, future.size != item.reminders.size)
        }
        val remindAt = item.remindAt
        if (remindAt != null && !remindAt.isAfter(now)) {
            val cleared = item.copy(
                completedAt = null,
                remindAt = null,
                reminderRevision = item.reminderRevision + 1,
            )
            repo.save(cleared)
            scheduler.cancel(id)
            return@withLock RestoreResult(
                item = cleared,
                outcome = ReminderOutcome.NotRequested,
                pastReminderCleared = true,
            )
        }

        val restored = item.copy(
            completedAt = null,
            reminderRevision = item.reminderRevision + 1,
        )
        repo.save(restored)
        scheduler.cancel(id)
        val outcome = if (remindAt == null) {
            ReminderOutcome.NotRequested
        } else if (
            scheduler.schedule(restored.id, restored.title, remindAt, restored.reminderRevision)
        ) {
            ReminderOutcome.Scheduled
        } else {
            ReminderOutcome.NotReady
        }
        RestoreResult(
            item = restored,
            outcome = outcome,
            pastReminderCleared = false,
        )
    }

    /** 删除事项并取消其未来闹钟。 */
    suspend fun deleteItem(id: String) = mutations.withLock {
        repo.delete(id)
        scheduler.cancel(id)
    }
}
