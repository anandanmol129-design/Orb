package de.mm20.launcher2.ui.settings.orb

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import de.mm20.launcher2.ui.theme.LauncherTheme

/**
 * Hosts [OrbSettingsScreen]. Launched from the sphere's radial menu.
 */
class OrbSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LauncherTheme {
                OrbSettingsScreen(onBack = { finish() })
            }
        }
    }
}
