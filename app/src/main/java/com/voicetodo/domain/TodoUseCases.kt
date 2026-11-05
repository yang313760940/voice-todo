package com.voicetodo.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    val listId: String? = null,
    /** 只有详情页或显式移动操作设置此标志时才改变归属。 */
    val updateList: Boolean = false,
)

data class UpdateResult(
    val item: TodoItem,
    val reminderChanged: Boolean,
    val outcome: ReminderOutcome?,
)

/** 通知入口“延后提醒”的预设时长。 */
enum class SnoozeOption(val minutes: Long) {
    TenMinutes(10),
    ThirtyMinutes(30),
    OneHour(60);

    companion object {
        /** 未知分钟数回退到 10 分钟，供通知/界面共用，避免各处重复解析。 */
        fun fromMinutes(minutes: Long): SnoozeOption =
            entries.firstOrNull { it.minutes == minutes } ?: TenMinutes
    }
}

/**
 * 通知入口的处理结果，供全屏提醒界面/通知动作与测试统一断言。
 *
 * 完成：标记事项完成并取消本实例未来提醒（重复实例仍保留后续安排）。
 * 延后：只在 [until] 重新登记一条系统闹钟，不改变截止时间、提醒列表或下一周期。
 * 关闭：只结束本次提醒，不完成事项，也不影响后续重复实例。
 */
data class ReminderActionOutcome(
    val item: TodoItem,
    val action: ReminderAction,
    val snoozeUntil: Instant? = null,
    val outcome: ReminderOutcome = ReminderOutcome.NotRequested,
    /** 重复点击同一动作返回 true：不重复调度，也不再次改写事项。 */
    val ignored: Boolean = false,
)

enum class ReminderAction { Complete, Snooze, Dismiss }

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
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val mutations = Mutex()
    suspend fun listItems(): List<TodoItem> = repo.all()
    suspend fun listRecurrenceSeries(): List<TodoItem> = repo.all().filter { it.recurrence != null }
    suspend fun listIncomplete(): List<TodoItem> = withContext(Dispatchers.IO) {
        mutations.withLock {
            ensureAllSeries(clock.now())
            repo.incomplete().filter { it.recurrence == null }
        }
    }
    suspend fun listCompleted(): List<TodoItem> = repo.completed().filter { it.recurrence == null }
    suspend fun listTodoLists(): List<TodoList> = repo.lists()
    suspend fun findItem(id: String): TodoItem =
        repo.findById(id) ?: throw IllegalArgumentException("事项不存在")
    suspend fun wasDelivered(item: TodoItem): Boolean = if (item.reminders.isEmpty()) {
        repo.deliveredAt(item.id, item.reminderRevision) != null
    } else item.reminders.any { repo.deliveredAt(item.id, it.id, it.revision) != null }

    suspend fun reconcileReminders() = mutations.withLock {
        val now = clock.now()
        ensureAllSeries(now)
        for (item in repo.incomplete().filter { it.recurrence == null }) {
            if (item.reminders.isNotEmpty()) {
                val pending = item.reminders.map { reminder ->
                    if (reminder.at.isAfter(now) && reminder.outcome != ReminderOutcome.NotRequested &&
                        repo.deliveredAt(item.id, reminder.id, reminder.revision) == null)
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
        require(item.recurrence == null) { "重复安排模板不能作为普通事项编辑" }
        require(item.completedAt == null) { "已完成事项请先恢复再编辑" }
        val title = edit.title.trim()
        require(title.isNotEmpty()) { "标题不能为空" }
        val nextListId = if (edit.updateList) edit.listId else item.listId
        nextListId?.let { require(repo.findListById(it) != null) { "清单不存在" } }
        val rules = edit.reminders ?: item.reminders.takeIf { it.isNotEmpty() }?.map {
            ReminderEdit(it.id, at = if (it.beforeDue == null) it.at else null, beforeDue = it.beforeDue)
        }
        if (rules != null) return@withLock updateMultiple(item.copy(listId = nextListId), title, edit, rules)
        val changed = edit.remindAt != item.remindAt
        if (changed) edit.remindAt?.let {
            require(it.isAfter(clock.now())) { "提醒时间必须晚于当前时间" }
        }
        val updated = item.copy(
            title = title, plannedDate = edit.plannedDate, dueAt = edit.dueAt,
            remindAt = edit.remindAt, listId = nextListId,
            reminderRevision = item.reminderRevision + if (changed) 1 else 0,
        )
        repo.save(updated)
        if (!changed) return@withLock UpdateResult(updated, false, null)
        scheduler.cancel(id)
        val outcome = schedule(updated)
        UpdateResult(updated, true, outcome)
    }

    suspend fun createList(name: String): TodoList = mutations.withLock {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "清单名称不能为空" }
        TodoList(name = clean, createdAt = clock.now()).also { repo.saveList(it) }
    }

    suspend fun renameList(id: String, name: String): TodoList = mutations.withLock {
        val existing = repo.findListById(id) ?: throw IllegalArgumentException("清单不存在")
        val clean = name.trim()
        require(clean.isNotEmpty()) { "清单名称不能为空" }
        existing.copy(name = clean).also { repo.saveList(it) }
    }

    suspend fun moveItemToList(itemId: String, listId: String?): TodoItem = mutations.withLock {
        val item = repo.findById(itemId) ?: throw IllegalArgumentException("事项不存在")
        listId?.let { require(repo.findListById(it) != null) { "清单不存在" } }
        item.copy(listId = listId).also { repo.save(it) }
    }

    suspend fun deleteList(id: String) = mutations.withLock {
        require(repo.findListById(id) != null) { "清单不存在" }
        repo.deleteList(id)
    }

    private suspend fun updateMultiple(
        item: TodoItem, title: String, edit: TodoEdit, rules: List<ReminderEdit>,
    ): UpdateResult {
        require(rules.map { it.id }.distinct().size == rules.size) { "同一事项的提醒身份不能重复" }
        rules.forEach {
            require(it.id.isNotBlank()) { "提醒身份不能为空" }
            require((it.at == null) != (it.beforeDue == null)) { "请选择一种提醒：指定时刻或相对截止" }
            it.beforeDue?.let { offset ->
                require(!offset.isNegative && !offset.isZero) { "提前时长必须大于零" }
            }
        }
        val oldReminders = if (item.reminders.isNotEmpty()) item.reminders else item.remindAt?.let {
            listOf(TodoReminder(item.id, it, revision = item.reminderRevision))
        }.orEmpty()
        val nextRevision = item.reminderRevision + 1
        val reminders = rules.map { rule ->
            val at = rule.at ?: requireNotNull(edit.dueAt) { "相对提醒需要截止时间" }
                .minus(requireNotNull(rule.beforeDue) { "请选择指定时刻或相对提醒" })
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
                outcome = if (scheduler.scheduleReminder(item.id, item.title, reminder.id, reminder.at,
                        reminder.revision)) ReminderOutcome.Scheduled else ReminderOutcome.NotReady)
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
        suggestedQuadrant: Quadrant? = null,
        listId: String? = null,
    ): ConfirmResult = mutations.withLock {
        val clean = title.trim()
        require(clean.isNotEmpty()) { "标题不能为空" }
        listId?.let { require(repo.findListById(it) != null) { "清单不存在" } }

        // 同一草稿重复确认时返回已创建事项，不再保存或重复排闹钟。
        repo.findById(itemId)?.let { existing ->
            return@withLock ConfirmResult(existing, outcomeFor(existing))
        }

        if (reminders.isNotEmpty()) {
            require(reminders.map { it.id }.distinct().size == reminders.size) {
                "同一事项的提醒身份不能重复"
            }
            val now = clock.now()
            val normalized = reminders.map { rule ->
                require(rule.id.isNotBlank()) { "提醒身份不能为空" }
                require((rule.at == null) != (rule.beforeDue == null)) {
                    "请选择一种提醒：指定时刻或相对截止"
                }
                rule.beforeDue?.let { offset ->
                    require(!offset.isNegative && !offset.isZero) { "提前时长必须大于零" }
                }
                val at = rule.at ?: requireNotNull(dueAt) { "相对提醒需要截止时间" }
                    .minus(requireNotNull(rule.beforeDue))
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
                suggestedQuadrant = suggestedQuadrant,
                listId = listId,
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
            suggestedQuadrant = suggestedQuadrant,
            listId = listId,
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

    /**
     * 只调整四象限分类：不触碰提醒安排、截止时间、计划日期或清单归属，
     * 也不请求系统重新调度。用户可以在象限页反复调整。
     */
    suspend fun moveToQuadrant(id: String, quadrant: Quadrant): TodoItem = mutations.withLock {
        val item = repo.findById(id) ?: throw IllegalArgumentException("事项不存在")
        require(item.completedAt == null) { "已完成事项不能在四象限中移动" }
        if (item.quadrant == quadrant) return@withLock item
        val updated = item.copy(quadrant = quadrant)
        repo.save(updated)
        updated
    }

    private fun outcomeFor(item: TodoItem): ReminderOutcome = when {
        item.reminders.isEmpty() ->
            if (item.remindAt == null) ReminderOutcome.NotRequested else ReminderOutcome.Scheduled
        item.reminders.any { it.outcome == ReminderOutcome.NotReady && it.deliveredAt == null } ->
            ReminderOutcome.NotReady
        else -> ReminderOutcome.Scheduled
    }

    /**
     * 创建重复安排模板，并按需物化接下来两次实例。
     * 模板本身不出现在待办列表；每次实例都有稳定身份并独立调度、完成。
     */
    suspend fun createRecurring(
        title: String,
        rule: RecurrenceRule,
        listId: String? = null,
    ): ConfirmResult = mutations.withLock {
        val clean = title.trim()
        require(clean.isNotEmpty()) { "标题不能为空" }
        rule.validate()
        require(listId == null || repo.lists().any { it.id == listId }) { "清单不存在" }
        val now = clock.now()
        val series = TodoItem(
            title = clean,
            createdAt = now,
            listId = listId,
            recurrence = rule,
        )
        repo.save(series)
        ConfirmResult(series, ensureSeries(series, emptyList(), now))
    }

    /** 结束整个重复安排；保留已完成历史，撤销未完成实例的未来提醒。 */
    suspend fun stopRecurrence(occurrenceOrSeriesId: String) = mutations.withLock {
        val item = repo.findById(occurrenceOrSeriesId) ?: throw IllegalArgumentException("事项不存在")
        val series = repo.findById(item.seriesId ?: item.id) ?: return@withLock
        if (series.recurrence == null) return@withLock
        stopSeries(series)
    }

    /**
     * 系统闹钟触发后补充后续实例。进程未打开时也由接收器调用，
     * 这样只预排两次也不会在用户长期不打开应用时停摆。
     */
    suspend fun ensureNextOccurrence(occurrenceId: String) = mutations.withLock {
        val item = repo.findById(occurrenceId) ?: return@withLock
        item.seriesId?.let { ensureSeriesFor(it, clock.now()) }
    }

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
        item.seriesId?.let { ensureSeriesFor(it, clock.now()) }
        completed
    }

    /**
     * 通知/响铃界面的“完成”：完成事项并撤销本实例未来提醒。
     * 重复实例完成本次不结束后续安排；[reminderId]/[revision] 标识被触发的这一次提醒，
     * 与当前提醒不符（已被处理过）时返回 ignored，不重复调度。
     */
    suspend fun completeFromReminder(
        itemId: String, reminderId: String = itemId, revision: Long = ANY_REVISION,
    ): ReminderActionOutcome = mutations.withLock {
        val item = repo.findById(itemId) ?: throw IllegalArgumentException("事项不存在")
        if (item.completedAt != null || !isFiring(item, reminderId, revision)) {
            if (item.completedAt != null) scheduler.cancel(itemId)
            return@withLock ReminderActionOutcome(item, ReminderAction.Complete, ignored = true)
        }
        val now = clock.now()
        val completed = item.copy(
            completedAt = now,
            reminderRevision = item.reminderRevision + 1,
            reminders = item.reminders.map { it.copy(outcome = ReminderOutcome.NotRequested) },
        )
        repo.save(completed)
        scheduler.cancel(itemId)
        item.seriesId?.let { ensureSeriesFor(it, now) }
        ReminderActionOutcome(completed, ReminderAction.Complete, outcome = ReminderOutcome.NotRequested)
    }

    /** 10 分钟 / 30 分钟 / 1 小时；只在指定时刻重新登记系统闹钟。 */
    suspend fun snoozeFromReminder(
        itemId: String, option: SnoozeOption, reminderId: String = itemId, revision: Long = ANY_REVISION,
    ): ReminderActionOutcome =
        snoozeFromReminderAt(itemId, clock.now().plus(Duration.ofMinutes(option.minutes)), reminderId, revision)

    /**
     * 自选时刻延后：只为被触发的这一次提醒重新登记系统闹钟（新修订号，可正常投递），
     * 不改变截止时间、其它提醒或下一重复周期。
     */
    suspend fun snoozeFromReminderAt(
        itemId: String, until: Instant, reminderId: String = itemId, revision: Long = ANY_REVISION,
    ): ReminderActionOutcome = mutations.withLock {
        val item = repo.findById(itemId) ?: throw IllegalArgumentException("事项不存在")
        require(item.completedAt == null) { "已完成事项不能再延后" }
        if (!isFiring(item, reminderId, revision)) {
            return@withLock ReminderActionOutcome(item, ReminderAction.Snooze, ignored = true)
        }
        require(until.isAfter(clock.now())) { "延后时间必须晚于当前时间" }
        val newRevision = item.reminderRevision + 1
        val scheduled = scheduler.scheduleReminder(itemId, item.title, reminderId, until, newRevision)
        val outcome = if (scheduled) ReminderOutcome.Scheduled else ReminderOutcome.NotReady
        val nextReminders = if (item.reminders.isEmpty()) {
            listOf(TodoReminder(reminderId, until, revision = newRevision, outcome = outcome))
        } else item.reminders.map {
            if (it.id == reminderId) TodoReminder(it.id, until, beforeDue = it.beforeDue,
                revision = newRevision, outcome = outcome, deliveredAt = null) else it
        }
        val updated = item.copy(
            dismissedAt = clock.now(),
            remindAt = nextReminders.map { it.at }.minOrNull(),
            reminderRevision = newRevision,
            reminders = nextReminders,
        )
        repo.save(updated)
        ReminderActionOutcome(updated, ReminderAction.Snooze, snoozeUntil = until, outcome = outcome)
    }

    /** 关闭本次提醒：不完成事项、不改截止与其它提醒，只让被触发的这一次提醒失效。 */
    suspend fun dismissReminder(
        itemId: String, reminderId: String = itemId, revision: Long = ANY_REVISION,
    ): ReminderActionOutcome = mutations.withLock {
        val item = repo.findById(itemId) ?: throw IllegalArgumentException("事项不存在")
        if (item.completedAt != null || !isFiring(item, reminderId, revision)) {
            return@withLock ReminderActionOutcome(
                item, ReminderAction.Dismiss,
                outcome = ReminderOutcome.NotRequested, ignored = true,
            )
        }
        val newRevision = item.reminderRevision + 1
        val nextReminders = if (item.reminders.isEmpty()) {
            listOf(TodoReminder(reminderId, item.remindAt ?: clock.now(),
                revision = newRevision, outcome = ReminderOutcome.NotRequested))
        } else item.reminders.map {
            if (it.id == reminderId) it.copy(revision = newRevision, outcome = ReminderOutcome.NotRequested)
            else it
        }
        val updated = item.copy(
            dismissedAt = clock.now(),
            reminderRevision = newRevision,
            reminders = nextReminders,
        )
        repo.save(updated)
        scheduler.cancelReminder(itemId, reminderId)
        ReminderActionOutcome(updated, ReminderAction.Dismiss, outcome = ReminderOutcome.NotRequested)
    }

    /** 被触发的这次提醒是否仍是当前提醒；[revision] 为哨兵时表示不校验（界面便捷调用）。 */
    private fun isFiring(item: TodoItem, reminderId: String, revision: Long): Boolean =
        revision == ANY_REVISION || item.findReminder(reminderId)?.revision == revision

    /** 设置某实例是否使用持续响铃通知；默认提示音振动与之都只是呈现方式。 */
    suspend fun setPersistentRing(id: String, persistent: Boolean): TodoItem = mutations.withLock {
        val item = repo.findById(id) ?: throw IllegalArgumentException("事项不存在")
        if (item.persistentRing == persistent) return@withLock item
        item.copy(persistentRing = persistent).also { repo.save(it) }
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
        val item = repo.findById(id)
        if (item?.recurrence != null) {
            stopSeries(item)
        } else {
            repo.delete(id)
            scheduler.cancel(id)
        }
    }

    private suspend fun ensureAllSeries(now: Instant) {
        val all = repo.all()
        val bySeries = all.filter { it.seriesId != null }.groupBy { it.seriesId }
        for (series in all.filter { it.recurrence != null }) {
            ensureSeries(series, bySeries[series.id].orEmpty(), now)
        }
    }

    private suspend fun ensureSeriesFor(seriesId: String, now: Instant) {
        val series = repo.findById(seriesId) ?: return
        if (series.recurrence == null) return
        ensureSeries(series, repo.all().filter { it.seriesId == seriesId }, now)
    }

    private suspend fun ensureSeries(
        series: TodoItem,
        occurrences: List<TodoItem>,
        now: Instant,
    ): ReminderOutcome {
        val rule = series.recurrence ?: return ReminderOutcome.NotRequested
        val today = now.atZone(zone).toLocalDate()
        val existingDates = occurrences.mapNotNull { it.occurrenceDate }.toSet()
        var upcomingCount = occurrences.count {
            it.completedAt == null && it.occurrenceDate != null && !it.occurrenceDate.isBefore(today) &&
                (it.reminders.isEmpty() || it.reminders.any { reminder -> reminder.at.isAfter(now) })
        }
        if (upcomingCount >= FUTURE_INSTANCES) return ReminderOutcome.NotRequested

        var candidate = rule.firstOccurrenceOnOrAfter(maxOf(today, rule.startDate))
        var outcome = ReminderOutcome.NotRequested
        var guard = 0
        while (upcomingCount < FUTURE_INSTANCES && guard++ < 1000) {
            val date = candidate
            val instants = rule.reminderTimes.map { rule.instantOn(date, it, zone) }
            val futureInstants = instants.filter { it.isAfter(now) }
            if (date !in existingDates && futureInstants.isNotEmpty()) {
                val occurrence = TodoItem(
                    id = rule.occurrenceId(series.id, date),
                    title = series.title,
                    remindAt = futureInstants.min(),
                    plannedDate = date,
                    createdAt = now,
                    reminders = rule.reminderTimes.mapIndexed { index, _ ->
                        TodoReminder(rule.reminderId(series.id, date, index), instants[index])
                    },
                    seriesId = series.id,
                    occurrenceDate = date,
                    listId = series.listId,
                    persistentRing = series.persistentRing,
                )
                repo.save(occurrence)
                val (_, createdOutcome) = scheduleMultiple(occurrence)
                outcome = mergeOutcome(outcome, createdOutcome)
                upcomingCount++
            }
            candidate = rule.nextOccurrenceAfter(date)
        }
        return outcome
    }

    private suspend fun stopSeries(series: TodoItem) {
        val today = clock.now().atZone(zone).toLocalDate()
        repo.all().filter {
            it.seriesId == series.id && it.completedAt == null &&
                (it.occurrenceDate == null || !it.occurrenceDate.isBefore(today))
        }.forEach {
            scheduler.cancel(it.id)
            repo.delete(it.id)
        }
        repo.delete(series.id)
        scheduler.cancel(series.id)
    }

    private fun mergeOutcome(first: ReminderOutcome, second: ReminderOutcome): ReminderOutcome = when {
        first == ReminderOutcome.NotReady || second == ReminderOutcome.NotReady -> ReminderOutcome.NotReady
        first == ReminderOutcome.Scheduled || second == ReminderOutcome.Scheduled -> ReminderOutcome.Scheduled
        else -> ReminderOutcome.NotRequested
    }

    private companion object {
        const val FUTURE_INSTANCES = 2
        /** 不校验触发修订号的哨兵值，用于没有具体投递上下文的便捷调用。 */
        const val ANY_REVISION = Long.MIN_VALUE
    }
}
