package com.voicetodo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicetodo.domain.Quadrant
import com.voicetodo.domain.QuadrantEntry
import com.voicetodo.domain.QuadrantUrgency
import com.voicetodo.domain.TodoItem
import com.voicetodo.domain.hasNearDeadline
import com.voicetodo.domain.hasNearReminder
import com.voicetodo.domain.isOverdue
import com.voicetodo.domain.quadrantEntries
import com.voicetodo.domain.reminderTimes
import com.voicetodo.settings.QuadrantPreferencesStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val quadrantTimeFormat = DateTimeFormatter.ofPattern("M-d HH:mm")

@Composable
fun QuadrantScreen(
    vm: TodoViewModel,
    preferencesStore: QuadrantPreferencesStore? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultStore = remember(context) { QuadrantPreferencesStore(context) }
    val store = preferencesStore ?: defaultStore
    var preferences by remember(store) { mutableStateOf(store.load()) }

    val detail = state.detail
    if (detail != null) {
        BackHandler(enabled = !state.busy, onBack = vm::onCancelDetail)
        DetailScreen(vm, state, detail, !state.loading && !state.busy && state.storageError == null)
        return
    }

    QuadrantScreenContent(
        items = state.items,
        completedItems = state.completedItems,
        now = state.now,
        zoneId = vm.zoneId,
        enabled = !state.busy && !state.loading,
        order = preferences.order,
        showCompleted = preferences.showCompleted,
        onOrderChange = { order ->
            preferences = runCatching { store.saveOrder(order) }
                .getOrElse { preferences.copy(order = order) }
        },
        onShowCompletedChange = { showCompleted ->
            preferences = runCatching { store.saveShowCompleted(showCompleted) }
                .getOrElse { preferences.copy(showCompleted = showCompleted) }
        },
        onMove = vm::onMoveQuadrant,
        onOpen = vm::onOpenDetail,
        onComplete = vm::onComplete,
        onRestore = vm::onRestore,
    )
}

@Composable
internal fun QuadrantScreenContent(
    items: List<TodoItem>,
    completedItems: List<TodoItem>,
    now: Instant,
    zoneId: ZoneId,
    enabled: Boolean,
    order: List<Quadrant>,
    showCompleted: Boolean,
    onOrderChange: (List<Quadrant>) -> Unit,
    onShowCompletedChange: (Boolean) -> Unit,
    onMove: (String, Quadrant) -> Unit,
    onOpen: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRestore: (String) -> Unit,
) {
    var quadrantBounds by remember { mutableStateOf<Map<Quadrant, Rect>>(emptyMap()) }
    var draggingItemId by remember { mutableStateOf<String?>(null) }
    var draggingQuadrant by remember { mutableStateOf<Quadrant?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var editingLayout by remember { mutableStateOf(false) }
    var guideVisible by remember { mutableStateOf(false) }
    val dragTarget = dragPosition?.let { point ->
        quadrantBounds.entries.firstOrNull { it.value.contains(point) }?.key
    }

    BackHandler(enabled = editingLayout) { editingLayout = false }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp).testTag("quadrant_screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (editingLayout) "调整象限排列" else "四象限",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (editingLayout) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onOrderChange(Quadrant.entries) },
                        enabled = enabled && order != Quadrant.entries,
                        modifier = Modifier.testTag("quadrant_reset_order"),
                    ) { Text("恢复默认") }
                    TextButton(
                        onClick = { editingLayout = false },
                        modifier = Modifier.testTag("quadrant_finish_edit"),
                    ) { Text("完成") }
                }
            } else {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("quadrant_more"),
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "四象限更多操作")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            onClick = {
                                menuExpanded = false
                                editingLayout = true
                            },
                            modifier = Modifier.testTag("quadrant_edit_layout"),
                        )
                        DropdownMenuItem(
                            text = { Text(if (showCompleted) "隐藏已完成" else "显示已完成") },
                            onClick = {
                                menuExpanded = false
                                onShowCompletedChange(!showCompleted)
                            },
                            modifier = Modifier.testTag("quadrant_toggle_completed"),
                        )
                        DropdownMenuItem(
                            text = { Text("使用指南") },
                            onClick = {
                                menuExpanded = false
                                guideVisible = true
                            },
                            modifier = Modifier.testTag("quadrant_open_guide"),
                        )
                    }
                }
            }
        }

        Text(
            if (editingLayout) "长按一个象限并拖到另一个象限，可交换窗口位置。"
            else "四个象限可分别滑动；点击事项查看详情，长按可调整象限。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("quadrant_hint"),
        )

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            order.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { quadrant ->
                        key(quadrant) {
                            val entries = quadrantEntries(items, quadrant, now) +
                                if (showCompleted) completedEntries(completedItems, quadrant) else emptyList()
                            QuadrantCard(
                                quadrant = quadrant,
                                entries = entries,
                                incompleteCount = entries.count { it.item.completedAt == null },
                                totalCount = entries.size,
                                now = now,
                                zoneId = zoneId,
                                enabled = enabled,
                                editingLayout = editingLayout,
                                isDropTarget = (draggingItemId != null || draggingQuadrant != null) &&
                                    dragTarget == quadrant,
                                onMove = onMove,
                                onOpen = onOpen,
                                onComplete = onComplete,
                                onRestore = onRestore,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                                    .onGloballyPositioned {
                                        quadrantBounds = quadrantBounds + (quadrant to it.boundsInRoot())
                                    },
                                onItemDragStart = { item, origin, local ->
                                    draggingItemId = item.id
                                    draggingQuadrant = null
                                    dragPosition = origin + local
                                },
                                onLayoutDragStart = { origin, local ->
                                    draggingQuadrant = quadrant
                                    draggingItemId = null
                                    dragPosition = origin + local
                                },
                                onDrag = { amount ->
                                    dragPosition = (dragPosition ?: Offset.Zero) + amount
                                },
                                onDragEnd = {
                                    val target = dragPosition?.let { point ->
                                        quadrantBounds.entries
                                            .firstOrNull { it.value.contains(point) }?.key
                                    }
                                    val movedItemId = draggingItemId
                                    val movedQuadrant = draggingQuadrant
                                    draggingItemId = null
                                    draggingQuadrant = null
                                    dragPosition = null
                                    when {
                                        movedItemId != null && target != null && target != quadrant ->
                                            onMove(movedItemId, target)
                                        movedQuadrant != null && target != null && target != movedQuadrant ->
                                            onOrderChange(order.swap(movedQuadrant, target))
                                    }
                                },
                                onDragCancel = {
                                    draggingItemId = null
                                    draggingQuadrant = null
                                    dragPosition = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (guideVisible) {
        AlertDialog(
            onDismissRequest = { guideVisible = false },
            title = { Text("四象限使用指南") },
            text = {
                Text(
                    "• 四个窗口可以分别上下滑动。\n" +
                        "• 点击事项可查看和编辑详情。\n" +
                        "• 长按事项并拖到其他窗口可调整象限。\n" +
                        "• “编辑”用于交换四个窗口的位置。\n" +
                        "• “显示已完成”会在原象限底部显示已完成事项。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { guideVisible = false },
                    modifier = Modifier.testTag("quadrant_close_guide"),
                ) { Text("知道了") }
            },
            modifier = Modifier.testTag("quadrant_guide"),
        )
    }
}

@Composable
private fun QuadrantCard(
    quadrant: Quadrant,
    entries: List<QuadrantEntry>,
    incompleteCount: Int,
    totalCount: Int,
    now: Instant,
    zoneId: ZoneId,
    enabled: Boolean,
    editingLayout: Boolean,
    isDropTarget: Boolean,
    onMove: (String, Quadrant) -> Unit,
    onOpen: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRestore: (String) -> Unit,
    modifier: Modifier,
    onItemDragStart: (TodoItem, Offset, Offset) -> Unit,
    onLayoutDragStart: (Offset, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var cardOrigin by remember(quadrant) { mutableStateOf(Offset.Zero) }
    val listState = rememberLazyListState()
    Card(
        modifier = modifier
            .testTag("quadrant_${quadrant.name}")
            .onGloballyPositioned { cardOrigin = it.boundsInRoot().topLeft }
            .then(
                if (editingLayout) Modifier.pointerInput(quadrant, enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local -> onLayoutDragStart(cardOrigin, local) },
                        onDrag = { change, amount -> change.consume(); onDrag(amount) },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                } else Modifier,
            )
            .then(
                if (isDropTarget) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp),
                ) else Modifier,
            ),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${quadrant.symbol} ${quadrant.label}",
                    color = quadrant.color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (totalCount == incompleteCount) "$incompleteCount" else "$incompleteCount/$totalCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("quadrant_count_${quadrant.name}"),
                )
                if (editingLayout) {
                    Text(
                        " ≡",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无事项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("quadrant_list_${quadrant.name}"),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(entries, key = { it.item.id }) { entry ->
                        QuadrantItem(
                            entry = entry,
                            now = now,
                            zoneId = zoneId,
                            enabled = enabled && !editingLayout,
                            onOpen = { onOpen(entry.item.id) },
                            onComplete = { onComplete(entry.item.id) },
                            onRestore = { onRestore(entry.item.id) },
                            onMove = { target -> onMove(entry.item.id, target) },
                            onDragStart = { origin, local -> onItemDragStart(entry.item, origin, local) },
                            onDrag = onDrag,
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuadrantItem(
    entry: QuadrantEntry,
    now: Instant,
    zoneId: ZoneId,
    enabled: Boolean,
    onOpen: () -> Unit,
    onComplete: () -> Unit,
    onRestore: () -> Unit,
    onMove: (Quadrant) -> Unit,
    onDragStart: (Offset, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val item = entry.item
    val completed = item.completedAt != null
    var itemOrigin by remember(item.id) { mutableStateOf(Offset.Zero) }
    Card(
        modifier = Modifier.fillMaxWidth()
            .testTag("quadrant_item_${item.id}")
            .onGloballyPositioned { itemOrigin = it.boundsInRoot().topLeft }
            .semantics {
                if (!completed) {
                    customActions = Quadrant.entries.filter { it != item.quadrant }.map { target ->
                        CustomAccessibilityAction("移动到${target.label}") {
                            onMove(target)
                            true
                        }
                    }
                }
            }
            .clickable(enabled = enabled, onClick = onOpen)
            .then(
                if (!completed) Modifier.pointerInput(item.id, enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local -> onDragStart(itemOrigin, local) },
                        onDrag = { change, amount -> change.consume(); onDrag(amount) },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                } else Modifier,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = completed,
                onCheckedChange = { if (completed) onRestore() else onComplete() },
                enabled = enabled,
                modifier = Modifier.size(40.dp).testTag("quadrant_complete_${item.id}"),
            )
            Column(Modifier.weight(1f).padding(top = 4.dp, end = 4.dp)) {
                Text(
                    item.title,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (completed) TextDecoration.LineThrough else null,
                    color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (item.note.isNotBlank()) {
                    Text(
                        item.note,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("quadrant_note_${item.id}"),
                    )
                }
                item.compactTime(zoneId)?.let { time ->
                    Text(
                        time,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = when {
                            item.isOverdue(now) -> MaterialTheme.colorScheme.error
                            item.hasNearDeadline(now) || item.hasNearReminder(now) -> quadrantColor(item.quadrant)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.testTag("quadrant_time_${item.id}"),
                    )
                }
            }
        }
    }
}

private fun completedEntries(items: List<TodoItem>, quadrant: Quadrant): List<QuadrantEntry> =
    items.filter { it.completedAt != null && it.quadrant == quadrant }
        .sortedWith(compareByDescending<TodoItem> { it.completedAt }.thenBy { it.id })
        .map { QuadrantEntry(it, QuadrantUrgency.Normal) }

private fun TodoItem.compactTime(zoneId: ZoneId): String? = when {
    completedAt != null -> "完成 ${completedAt.atZone(zoneId).format(quadrantTimeFormat)}"
    dueAt != null -> "截止 ${dueAt.atZone(zoneId).format(quadrantTimeFormat)}"
    reminderTimes().isNotEmpty() ->
        "提醒 ${reminderTimes().minOrNull()!!.atZone(zoneId).format(quadrantTimeFormat)}"
    plannedDate != null -> "计划 $plannedDate"
    else -> null
}

private val Quadrant.symbol: String
    get() = when (this) {
        Quadrant.ImportantUrgent -> "Ⅰ"
        Quadrant.ImportantNotUrgent -> "Ⅱ"
        Quadrant.NotImportantUrgent -> "Ⅲ"
        Quadrant.NotImportantNotUrgent -> "Ⅳ"
    }

private val Quadrant.color: Color get() = quadrantColor(this)

private fun quadrantColor(quadrant: Quadrant): Color = when (quadrant) {
    Quadrant.ImportantUrgent -> Color(0xFFE85D75)
    Quadrant.ImportantNotUrgent -> Color(0xFFFFA000)
    Quadrant.NotImportantUrgent -> Color(0xFF4C78FF)
    Quadrant.NotImportantNotUrgent -> Color(0xFF00A87A)
}

private fun List<Quadrant>.swap(first: Quadrant, second: Quadrant): List<Quadrant> {
    val firstIndex = indexOf(first)
    val secondIndex = indexOf(second)
    if (firstIndex < 0 || secondIndex < 0 || firstIndex == secondIndex) return this
    return toMutableList().apply {
        this[firstIndex] = second
        this[secondIndex] = first
    }
}
