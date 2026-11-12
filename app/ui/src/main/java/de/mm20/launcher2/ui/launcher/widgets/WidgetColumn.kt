package de.mm20.launcher2.ui.launcher.widgets

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animate
import java.util.UUID
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.base.LocalAppWidgetHost
import de.mm20.launcher2.ui.launcher.sheets.WidgetPickerSheet
import de.mm20.launcher2.ui.locals.LocalSnackbarHostState
import de.mm20.launcher2.widgets.AppWidget
import kotlinx.coroutines.launch

@Composable
fun WidgetColumn(
    modifier: Modifier = Modifier,
    editMode: Boolean = false,
    onEditModeChange: (Boolean) -> Unit,
    parentId: String,
    scrollState: ScrollState,
    onInitialContentReady: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: WidgetsVM = viewModel(
        key = "widgets-column-$parentId",
        factory = WidgetsVM.Factory(parentId),
    )
    val snackbarHostState = LocalSnackbarHostState.current
    var addNewWidget by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val widgets by viewModel.widgets.collectAsState()
    val widgetsLoaded by viewModel.isLoaded.collectAsState()
    val editButton by viewModel.editButton.collectAsState()
    // Measured heights, keyed by widget id so they survive reordering.
    val heights = remember { mutableMapOf<UUID, Int>() }
    // Order shown while dragging (and until the saved order catches up), so a swap and
    // the matching offset correction land in the same frame.
    var dragOrder by remember { mutableStateOf<List<UUID>?>(null) }
    var draggedId by remember { mutableStateOf<UUID?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val spacingPx = with(density) { 8.dp.toPx() }
    val hysteresisPx = with(density) { 12.dp.toPx() }

    val displayed = remember(widgets, dragOrder) {
        val order = dragOrder ?: return@remember widgets
        val byId = widgets.associateBy { it.id }
        order.mapNotNull { byId[it] } + widgets.filter { it.id !in order }
    }

    LaunchedEffect(widgets, draggedId) {
        if (draggedId == null && dragOrder != null && widgets.map { it.id } == dragOrder) {
            dragOrder = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .onGloballyPositioned {
                if (widgetsLoaded) onInitialContentReady()
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        displayed.forEachIndexed { i, widget ->
            key(widget.id) {
                val isDragged = draggedId == widget.id
                val widgetHost = LocalAppWidgetHost.current
                WidgetItem(
                    widget = widget,
                    editMode = editMode,
                    onWidgetAdd = { addedWidget, offset ->
                        viewModel.addWidget(addedWidget, i + offset)
                    },
                    onWidgetRemove = {
                        lifecycleOwner.lifecycleScope.launch {
                            viewModel.removeWidget(widget)
                            val result = snackbarHostState.showSnackbar(
                                message = context.getString(R.string.widget_removed),
                                actionLabel = context.getString(R.string.action_undo),
                                duration = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.addWidget(widget, i)
                            } else if (widget is AppWidget) {
                                widgetHost.deleteAppWidgetId(widget.config.widgetId)
                            }
                        }
                    },
                    onWidgetUpdate = viewModel::updateWidget,
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 1f else 0f)
                        .onSizeChanged { heights[widget.id] = it.height }
                        .offset { IntOffset(0, if (isDragged) dragOffset.roundToInt() else 0) },
                    draggableState = rememberDraggableState { delta ->
                        if (draggedId != widget.id) {
                            draggedId = widget.id
                            dragOffset = 0f
                        }
                        val order = (dragOrder ?: displayed.map { it.id }).toMutableList()
                        dragOffset += delta
                        val idx = order.indexOf(widget.id)
                        if (idx < 0) return@rememberDraggableState
                        if (idx > 0) {
                            val step = (heights[order[idx - 1]] ?: 0) + spacingPx
                            if (dragOffset < -(step / 2f + hysteresisPx)) {
                                order.add(idx - 1, order.removeAt(idx))
                                dragOffset += step
                            }
                        }
                        if (idx < order.lastIndex && order[idx] == widget.id) {
                            val step = (heights[order[idx + 1]] ?: 0) + spacingPx
                            if (dragOffset > step / 2f + hysteresisPx) {
                                order.add(idx + 1, order.removeAt(idx))
                                dragOffset -= step
                            }
                        }
                        dragOrder = order
                    },
                    onDragStopped = {
                        val order = dragOrder
                        if (order != null) viewModel.setOrder(order)
                        scope.launch {
                            animate(dragOffset, 0f) { v, _ -> dragOffset = v }
                            draggedId = null
                            dragOffset = 0f
                        }
                    },
                )
            }
        }

        if (editMode || editButton == true) {
            val title = stringResource(
                if (editMode) R.string.widget_add_widget else R.string.menu_edit_widgets,
            )
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                onClick = {
                    if (!editMode) onEditModeChange(true) else addNewWidget = true
                },
            ) {
                Icon(
                    modifier = Modifier
                        .padding(end = ButtonDefaults.IconSpacing)
                        .size(ButtonDefaults.IconSize),
                    painter = painterResource(
                        if (editMode) R.drawable.add_20px else R.drawable.edit_20px,
                    ),
                    contentDescription = null,
                )
                Text(title)
            }
        }
    }

    WidgetPickerSheet(
        expanded = addNewWidget,
        onDismiss = { addNewWidget = false },
        onWidgetSelected = {
            viewModel.addWidget(it)
            addNewWidget = false
        },
    )
}
