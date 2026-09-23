package de.mm20.launcher2.ui.launcher

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.preferences.ColorScheme
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.ScreenOrientation
import de.mm20.launcher2.preferences.SearchBarColors
import de.mm20.launcher2.preferences.SearchBarStyle
import de.mm20.launcher2.preferences.WallpaperType
import de.mm20.launcher2.preferences.ui.GestureSettings
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.search.SavableSearchable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class LauncherScaffoldVM : ViewModel(), KoinComponent {

    private val uiSettings: UiSettings by inject()
    private val gestureSettings: GestureSettings by inject()
    private val searchableRepository: SavableSearchableRepository by inject()

    private var isSystemInDarkMode = MutableStateFlow(false)

    private val dimBackgroundState = combine(
        uiSettings.dimWallpaper,
        uiSettings.colorScheme,
        isSystemInDarkMode
    ) { dim, theme, systemDarkMode ->
        dim && (theme == ColorScheme.Dark || theme == ColorScheme.System && systemDarkMode)
    }
    val dimBackground = dimBackgroundState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val statusBarColor = uiSettings.statusBarColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    val navBarColor = uiSettings.navigationBarColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val chargingAnimation = uiSettings.chargingAnimation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val hideNavBar = uiSettings.hideNavigationBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val hideStatusBar = uiSettings.hideStatusBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    fun setSystemInDarkMode(darkMode: Boolean) {
        isSystemInDarkMode.value = darkMode
    }

    val bottomSearchBar = uiSettings.bottomSearchBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val reverseSearchResults = uiSettings.reverseSearchResults
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val fixedSearchBar = uiSettings.fixedSearchBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val fixedRotation = uiSettings.orientation
        .map { it != ScreenOrientation.Auto }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val widgetsOnHomeScreen = uiSettings.homeScreenWidgets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val autoFocusSearch = uiSettings.openKeyboardOnSearch

    val wallpaperBlur = uiSettings.blurWallpaper
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val wallpaperBlurRadius = uiSettings.wallpaperBlurRadius
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 32)
    val wallpaperType = uiSettings.wallpaperType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), WallpaperType.System)


    val fillClockHeight = uiSettings.clockFillScreen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val searchBarColor = uiSettings.searchBarColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), SearchBarColors.Auto)
    val searchBarStyle = uiSettings.searchBarStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), SearchBarStyle.Transparent)

    val gestureState: StateFlow<GestureState?> = gestureSettings.map { settings ->
            val tapAction = settings.tap
            val doubleTapAction = settings.doubleTap
            val tripleTapAction = settings.tripleTap
            val longPressAction = settings.longPress
            val homeButtonAction = settings.homeButton

            val tapAppKey = (tapAction as? GestureAction.Launch)?.key
            val doubleTapAppKey = (doubleTapAction as? GestureAction.Launch)?.key
            val tripleTapAppKey = (tripleTapAction as? GestureAction.Launch)?.key
            val longPressAppKey = (longPressAction as? GestureAction.Launch)?.key
            val homeButtonAppKey = (homeButtonAction as? GestureAction.Launch)?.key
            val apps = listOfNotNull(
                tapAppKey,
                doubleTapAppKey,
                tripleTapAppKey,
                longPressAppKey,
                homeButtonAppKey,
            ).let { searchableRepository.getByKeys(it).first() }

            GestureState(
                tapAction = tapAction,
                doubleTapAction = doubleTapAction,
                tripleTapAction = tripleTapAction,
                longPressAction = longPressAction,
                homeButtonAction = homeButtonAction,
                tapApp = apps.find { it.key == tapAppKey },
                doubleTapApp = apps.find { it.key == doubleTapAppKey },
                tripleTapApp = apps.find { it.key == tripleTapAppKey },
                longPressApp = apps.find { it.key == longPressAppKey },
                homeButtonApp = apps.find { it.key == homeButtonAppKey },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

data class GestureState(
    val tapAction: GestureAction = GestureAction.NoAction,
    val doubleTapAction: GestureAction = GestureAction.NoAction,
    val tripleTapAction: GestureAction = GestureAction.NoAction,
    val longPressAction: GestureAction = GestureAction.NoAction,
    val homeButtonAction: GestureAction = GestureAction.NoAction,
    val tapApp: SavableSearchable? = null,
    val doubleTapApp: SavableSearchable? = null,
    val tripleTapApp: SavableSearchable? = null,
    val longPressApp: SavableSearchable? = null,
    val homeButtonApp: SavableSearchable? = null,
)

