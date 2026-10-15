package de.mm20.launcher2.ui.launcher.feed

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import de.mm20.launcher2.ui.launcher.scaffold.components.OrbFeedScreen
import de.mm20.launcher2.ui.theme.LauncherTheme

/** Standalone host for the built-in RSS reader, isolated from the launcher's gesture layer. */
class FeedReaderActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LauncherTheme {
                OrbFeedScreen()
            }
        }
    }
}
