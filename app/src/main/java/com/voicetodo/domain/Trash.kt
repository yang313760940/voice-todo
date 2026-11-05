package com.voicetodo.domain

import java.time.Duration
import java.time.Instant

const val TRASH_RETENTION_DAYS = 30L

enum class RecurringDeleteScope {
    ThisOnly,
    ThisAndFuture,
}

/** 回收站中的事项，保留删除时刻、到期时刻与界面展示的剩余天数。 */
data class TrashedItem(
    val item: TodoItem,
    val deletedAt: Instant,
    val expiresAt: Instant,
    val daysRemaining: Int,
) {
    companion object {
        fun from(item: TodoItem, now: Instant): TrashedItem {
            val deletedAt = requireNotNull(item.deletedAt) { "事项不在回收站" }
            val expiresAt = deletedAt.plus(Duration.ofDays(TRASH_RETENTION_DAYS))
            val remainingMillis = Duration.between(now, expiresAt).toMillis().coerceAtLeast(0)
            val days = if (remainingMillis == 0L) 0 else
                kotlin.math.ceil(remainingMillis / 86_400_000.0).toInt()
            return TrashedItem(item, deletedAt, expiresAt, days)
        }
    }
}
