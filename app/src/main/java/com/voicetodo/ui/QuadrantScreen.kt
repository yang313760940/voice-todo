package com.voicetodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.QuadrantEntry
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.hasNearDeadline
import com.voicetodo.domain.hasNearReminder
import com.voicetodo.domain.isOverdue
import com.voicetodo.domain.quadrantEntries
import java.time.Instant

@Composable
fun QuadrantScreen(vm: TodoViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    QuadrantScreenContent(
        items = state.items,
        now = state.now,
        enabled = !state.busy && !state.loading,
        onMove = vm::onMoveQuadrant,
    )
}

/**
 * 四象限页：展示未完成事项，支持长按拖拽与“移动到…”菜单两种等价入口。
 * 移动只改变分类，提醒、截止、计划日期与清单不受影响。
 */
@Composable
internal fun QuadrantScreenContent(
    items: List<TodoItem>,
    now: Instant,
    enabled: Boolean,
    onMove: (String, Quadrant) -> Unit,
) {
    var quadrantBounds by remember { mutableStateOf<Map<Quadrant, Rect>>(emptyMap()) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    val dragTarget = dragPosition?.let { point ->
        quadrantBounds.entries.firstOrNull { it.value.contains(point) }?.key
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("quadrant_screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("四象限", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (draggingId != null) "松开后移动到高亮象限；分类变化不会改动提醒或截止时间。"
            else "长按事项拖到其他象限，或使用每项的“移动到…”菜单。临近时间只提示，不自动迁移象限。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("quadrant_hint"),
        )
        listOf(0, 1).forEach { rowIndex ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Quadrant.entries.drop(rowIndex * 2).take(2).forEach { quadrant ->
                    QuadrantCard(
                        quadrant = quadrant,
                        entries = quadrantEntries(items, quadrant, now),
                        now = now,
                        enabled = enabled,
                        isDropTarget = draggingId != null && dragTarget == quadrant,
                        onMove = onMove,
                        modifier = Modifier.weight(1f)
                            .onGloballyPositioned { quadrantBounds = quadrantBounds + (quadrant to it.boundsInRoot()) },
                        onDragStart = { item, origin, local ->
                            draggingId = item.id
                            dragPosition = origin + local
                        },
                        onDrag = { amount ->
                            dragPosition = (dragPosition ?: Offset.Zero) + amount
                        },
                        onDragEnd = {
                            val target = dragPosition?.let { point ->
                                quadrantBounds.entries.firstOrNull { it.value.contains(point) }?.key
                            }
                            val movedId = draggingId
                            draggingId = null
                            dragPosition = null
                            if (movedId != null && target != null && target != quadrant) {
                                onMove(movedId, target)
                            }
                        },
                        onDragCancel = {
                            draggingId = null
                            dragPosition = null
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuadrantCard(
    quadrant: Quadrant,
    entries: List<QuadrantEntry>,
    now: Instant,
    enabled: Boolean,
    isDropTarget: Boolean,
    onMove: (String, Quadrant) -> Unit,
    modifier: Modifier,
    onDragStart: (TodoItem, Offset, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    Card(
        modifier = modifier
            .heightIn(min = 160.dp)
            .testTag("quadrant_${quadrant.name}")
            .then(
                if (isDropTarget) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(quadrant.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${entries.size} 项",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("quadrant_count_${quadrant.name}"),
                )
            }
            if (entries.isEmpty()) {
                Text("暂无事项", style = MaterialTheme.typography.bodySmall)
            } else {
                entries.forEach { entry ->
                    QuadrantItem(
                        entry = entry,
                        now = now,
                        enabled = enabled,
                        onMove = { target -> onMove(entry.item.id, target) },
                        onDragStart = { origin, local -> onDragStart(entry.item, origin, local) },
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuadrantItem(
    entry: QuadrantEntry,
    now: Instant,
    enabled: Boolean,
    onMove: (Quadrant) -> Unit,
    onDragStart: (Offset, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val item = entry.item
    var itemOrigin by remember(item.id) { mutableStateOf(Offset.Zero) }
    Card(
        modifier = Modifier.fillMaxWidth()
            .testTag("quadrant_item_${item.id}")
            .onGloballyPositioned { itemOrigin = it.boundsInRoot().topLeft }
            .pointerInput(item.id, enabled) {
                if (!enabled) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { local -> onDragStart(itemOrigin, local) },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    item.title,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                MoveMenu(item = item, enabled = enabled, onMove = onMove)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                QuadrantChip("当前：${item.quadrant.label}", "quadrant_value_${item.id}")
                if (item.isOverdue(now)) {
                    QuadrantChip("逾期", "quadrant_overdue_${item.id}", error = true)
                }
                if (item.hasNearDeadline(now)) {
                    QuadrantChip("临近截止", "quadrant_near_due_${item.id}")
                }
                if (item.hasNearReminder(now)) {
                    QuadrantChip("临近提醒", "quadrant_near_reminder_${item.id}")
                }
            }
            item.suggestedQuadrant?.let { suggested ->
                Text(
                    "AI 建议：${suggested.label}（仅建议）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("quadrant_suggestion_${item.id}"),
                )
            }
            Text(
                "长按拖动",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuadrantChip(text: String, tag: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                if (error) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag(tag),
    )
}

@Composable
private fun MoveMenu(item: TodoItem, enabled: Boolean, onMove: (Quadrant) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.size(48.dp).testTag("move_menu_${item.id}"),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "移动“${item.title}”到其他象限")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Quadrant.entries.filter { it != item.quadrant }.forEach { quadrant ->
                DropdownMenuItem(
                    text = { Text("移动到：${quadrant.label}") },
                    onClick = {
                        expanded = false
                        onMove(quadrant)
                    },
                    modifier = Modifier.testTag("move_to_${quadrant.name}_${item.id}"),
                )
            }
        }
    }
}
