package de.mm20.launcher2.ui.features.orb

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import de.mm20.launcher2.ui.theme.LauncherTheme

/**
 * Full-screen calendar, task board and time tracker.
 *
 * Opened from the sphere's radial menu. All three tabs read and write through the same
 * repositories that back the agent tools, so on-screen and voice actions stay consistent.
 */
class OrbFeaturesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = intent?.getIntExtra(EXTRA_TAB, TAB_CALENDAR) ?: TAB_CALENDAR
        setContent {
            LauncherTheme {
                OrbFeaturesScreen(
                    initialTab = initialTab,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_CALENDAR = 0
        const val TAB_BOARD = 1
        const val TAB_TRACKER = 2
    }
}
