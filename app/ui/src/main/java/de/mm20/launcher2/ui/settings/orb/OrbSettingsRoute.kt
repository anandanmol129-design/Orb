package de.mm20.launcher2.ui.settings.orb

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Settings route for [OrbSettingsScreen], the first entry of the main settings page. The orb's
 * radial menu and the "Orb settings" gesture open it directly through
 * [de.mm20.launcher2.ui.settings.SettingsActivity.ROUTE_ORB].
 */
@Serializable
data object OrbSettingsRoute : NavKey
