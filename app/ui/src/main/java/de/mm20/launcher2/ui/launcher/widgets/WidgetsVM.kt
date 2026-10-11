package de.mm20.launcher2.ui.launcher.widgets

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.widgets.Widget
import de.mm20.launcher2.widgets.WidgetRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import kotlinx.coroutines.launch

class WidgetsVM(
    private val parentId: UUID?,
) : ViewModel(), KoinComponent {
    private val cacheKey = parentId?.toString() ?: "root"
    private val widgetRepository: WidgetRepository by inject()

    private val uiSettings: UiSettings by inject()

    val editButton = uiSettings.widgetEditButton
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private val _widgets = MutableStateFlow(cachedWidgets[cacheKey] ?: emptyList())
    val widgets = _widgets.asStateFlow()
    val isLoaded = MutableStateFlow(cachedWidgets.containsKey(cacheKey))

    init {
        viewModelScope.launch {
            widgetRepository.get(parent = parentId).collect { updatedWidgets ->
                cachedWidgets[cacheKey] = updatedWidgets
                _widgets.value = updatedWidgets
                isLoaded.value = true
            }
        }
    }

    fun addWidget(widget: Widget, index: Int? = null) {
        val widgets = widgets.value.toMutableList()
        if (index == null) {
            widgets.add(widget)
        } else {
            widgets.add(index.coerceAtMost(widgets.size), widget)
        }
        widgetRepository.set(widgets, parentId)
    }

    fun removeWidget(widget: Widget) {
        widgetRepository.delete(widget)
    }

    fun updateWidget(widget: Widget) {
        widgetRepository.update(widget)
    }

    fun moveUp(index: Int) {
        val widgets = widgets.value.toMutableList()
        val widget = widgets.removeAt(index)
        widgets.add(index - 1, widget)
        widgetRepository.set(widgets, parentId)
    }

    fun moveDown(index: Int) {
        val widgets = widgets.value.toMutableList()
        val widget = widgets.removeAt(index)
        widgets.add(index + 1, widget)
        widgetRepository.set(widgets, parentId)
    }

    companion object : KoinComponent {
        private val cachedWidgets = java.util.concurrent.ConcurrentHashMap<String, List<Widget>>()

        fun Factory(parentId: String) = viewModelFactory {
            initializer {
                val id = try {
                    UUID.fromString(parentId)
                } catch (e: IllegalArgumentException) {
                    Log.e("WidgetsVM", "Invalid parentId: $parentId", e)
                    null
                }
                WidgetsVM(id)
            }
        }
    }
}
