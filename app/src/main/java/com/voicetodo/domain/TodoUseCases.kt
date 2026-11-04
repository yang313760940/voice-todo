package com.voicetodo.domain

import java.time.LocalDateTime

/** 保存/取消结账结果：区分“已保存”与“提醒是否已成功排入系统”。 */
data class ConfirmResult(
    val item: TodoItem,
    /** 提醒是否**真正**排入系统；权限缺失或系统拒绝时为 false。 */
    val scheduled: Boolean,
)

/**
 * 统一用例入口（应用层 seam）。界面通过它读取列表和提交用户操作；
 * 失败（空标题、过去时刻）抛 [IllegalArgumentException]，调用方在保存前拦截。
 *
 * 保存与提醒分别反馈：即使提醒未就绪，事项仍保存，[ConfirmResult.scheduled] 如实返回 false，
 * 调用方据此提示“提醒未就绪”，绝不误报已安排。
 */
class TodoUseCases(
    private val repo: TodoRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    fun listItems(): List<TodoItem> = repo.all()

    /** 用户确认一个草稿：校验 → 持久化 → 请求系统闹钟（按真实结果反馈）。 */
    fun confirmDraft(title: String, remindAt: LocalDateTime): ConfirmResult {
        val clean = title.trim()
        require(clean.isNotEmpty()) { "标题不能为空" }
        require(remindAt.isAfter(clock.now())) { "提醒时间必须晚于当前时间" }

        val item = TodoItem(title = clean, remindAt = remindAt, createdAt = clock.now())
        repo.save(item)
        // 调度失败（缺权限/系统拒绝）不抛异常、不回滚事项，但如实返回 false。
        val scheduled = scheduler.schedule(item.id, item.title, item.remindAt)
        return ConfirmResult(item, scheduled = scheduled)
    }

    /** 用户取消确认：不创建事项、不调度。 */
    fun cancelDraft() {
        // 无副作用
    }

    /** 删除事项并取消其未来闹钟。 */
    fun deleteItem(id: String) {
        repo.delete(id)
        scheduler.cancel(id)
    }
}
