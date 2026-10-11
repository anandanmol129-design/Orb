package de.mm20.launcher2.ui.launcher.scaffold.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CompletableDeferred
import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.launcher.scaffold.LauncherScaffoldState
import de.mm20.launcher2.ui.launcher.widgets.WidgetColumn
import kotlinx.coroutines.launch

internal class WidgetsComponent(
    private val target: WidgetScreenTarget
) : ScaffoldComponent() {

    companion object {
        /**
         * Cache for widget component instances.
         * Components are created lazily only when needed.
         */
        private val componentCache = mutableMapOf<WidgetScreenTarget, WidgetsComponent>()

        /**
         * Get or create a WidgetsComponent for the given target.
         * This ensures we reuse the same instance for each target.
         */
        fun forTarget(target: WidgetScreenTarget): WidgetsComponent {
            return componentCache.getOrPut(target) {
                WidgetsComponent(target)
            }
        }
    }

    private val scrollState = ScrollState(0)
    private val initialContentReady = CompletableDeferred<Unit>()

    fun markInitialContentReady() {
        if (!initialContentReady.isCompleted) initialContentReady.complete(Unit)
    }

    val isInitialContentReady: Boolean
        get() = initialContentReady.isCompleted

    suspend fun awaitInitialContentReady() {
        initialContentReady.await()
    }

    override val isAtTop: State<Boolean?> = derivedStateOf {
        !scrollState.canScrollBackward
    }

    override val isAtBottom: State<Boolean?> = derivedStateOf {
        !scrollState.canScrollForward
    }

    // In note widget
    override val hasIme: Boolean = true

    @Composable
    override fun Component(
        modifier: Modifier,
        insets: PaddingValues,
        state: LauncherScaffoldState
    ) {
        var editMode by rememberSaveable { mutableStateOf(false) }

        val scope = rememberCoroutineScope()
        val topPadding by animateDpAsState(if (editMode) 64.dp else 0.dp)

        LaunchedEffect(scrollState, editMode) {
            var previousScroll = scrollState.value
            snapshotFlow { scrollState.value }.distinctUntilChanged().collect { scrollPosition ->
                val delta = scrollPosition - previousScroll
                if (!editMode && delta != 0) {
                    state.onComponentScroll(delta.toFloat())
                }
                previousScroll = scrollPosition
            }
        }

        WidgetColumn(
            modifier = modifier
                .padding(horizontal = 8.dp)
                .padding(insets)
                .widthIn(max = 900.dp)
                .fillMaxHeight()
                .padding(top = topPadding),
            editMode = editMode,
            onEditModeChange = {
                scope.launch { state.lock(hideSearchBar = true) }
                editMode = it
            },
            parentId = target.id.toString(),
            scrollState = scrollState,
            onInitialContentReady = ::markInitialContentReady,
        )
        if (editMode) {
            BackHandler {
                editMode = false
                scope.launch { state.unlock() }
            }
        }
        AnimatedVisibility(
            editMode,
            modifier = Modifier.zIndex(10f),
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.menu_edit_widgets)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            editMode = false
                            scope.launch { state.unlock() }
                        }
                    ) {
                        Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.action_done))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    }

}
