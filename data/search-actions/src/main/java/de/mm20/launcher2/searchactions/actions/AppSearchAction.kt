package de.mm20.launcher2.searchactions.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import de.mm20.launcher2.ktx.tryStartActivity

data class AppSearchAction(
    override val label: String,
    val baseIntent: Intent,
    val query: String,
    override val icon: SearchActionIcon = SearchActionIcon.Custom,
    override val iconColor: Int = 1,
    override val customIcon: String? = null,
): SearchAction {

    override fun start(context: Context) {
        val intent = Intent(baseIntent).apply {
            when (baseIntent.action) {
                Intent.ACTION_WEB_SEARCH -> {
                    putExtra(SearchManager.QUERY, query)
                }
                Intent.ACTION_VIEW -> {
                    data = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
                }
                Intent.ACTION_PROCESS_TEXT -> {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_PROCESS_TEXT, query)
                    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                }
                Intent.ACTION_SEND -> {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, query)
                }
                else -> {
                    // Legacy actions were saved without an action: they all use ACTION_SEARCH.
                    action = Intent.ACTION_SEARCH
                    putExtra(SearchManager.QUERY, query)
                    putExtra(SearchManager.USER_QUERY, query)
                }
            }
        }
        if (context.tryStartActivity(intent)) return
        // The app may have dropped that entry point since the action was created:
        // fall back to sending the text to the same app as a plain share.
        val pkg = baseIntent.component?.packageName ?: return
        context.tryStartActivity(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(pkg)
                putExtra(Intent.EXTRA_TEXT, query)
            }
        )
    }
}
