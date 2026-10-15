package de.mm20.launcher2.ui.launcher.scaffold.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Xml
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import coil.compose.AsyncImage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import de.mm20.launcher2.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

private data class RssArticle(
    val title: String,
    val description: String,
    val url: String,
    val source: String,
    val date: String,
    val image: String = "",
    val category: String = "",
    val author: String = "",
)

private data class RssFeedColumn(
    val url: String,
    val title: String,
    val articles: List<RssArticle>,
    val failed: Boolean = false,
)

private data class ParsedRssFeed(val title: String, val articles: List<RssArticle>)

private object RssSubscriptions {
    private const val PREFS = "orb_rss_reader"
    private const val URLS = "subscriptions"
    fun get(context: Context): List<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(URLS, emptySet()).orEmpty().sorted()
    fun add(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(URLS, prefs.getStringSet(URLS, emptySet()).orEmpty() + url).apply()
    }
    fun remove(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(URLS, prefs.getStringSet(URLS, emptySet()).orEmpty() - url).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun OrbFeedScreen(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val scope = rememberCoroutineScope()
        val subscriptions = remember { mutableStateListOf<String>().apply { addAll(RssSubscriptions.get(context)) } }
        var feeds by remember { mutableStateOf(emptyList<RssFeedColumn>()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var showAdd by remember { mutableStateOf(false) }
        var showManage by remember { mutableStateOf(false) }

        fun refresh() {
            scope.launch {
                loading = true
                error = null
                val results = withContext(Dispatchers.IO) {
                    subscriptions.map { url ->
                        runCatching {
                            val parsed = fetchRss(url)
                            RssFeedColumn(
                                url = url,
                                title = parsed.title.ifBlank { URL(url).host },
                                articles = parsed.articles.sortedByDescending { it.date }.take(50),
                            )
                        }.getOrElse {
                            RssFeedColumn(url, URL(url).host, emptyList(), failed = true)
                        }
                    }
                }
                feeds = results
                loading = false
                if (subscriptions.isNotEmpty() && results.all { it.failed }) error = "Couldn’t load these feeds. Check the URLs or try again."
            }
        }
        LaunchedEffect(subscriptions.toList()) { refresh() }

        val pagerState = rememberPagerState(pageCount = { feeds.size.coerceAtLeast(1) })
        val feedTabsScrollState = rememberScrollState()
        val feedTabCenters = remember { mutableMapOf<Int, Float>() }
        var feedTabsViewportCenter by remember { mutableStateOf(0f) }
        LaunchedEffect(pagerState.currentPage, feeds.size) {
            if (feeds.isNotEmpty() && pagerState.currentPage >= feeds.size) pagerState.scrollToPage(0)
        }
        LaunchedEffect(pagerState.currentPage, feeds.size) {
            if (feeds.isEmpty() || pagerState.currentPage >= feeds.size) return@LaunchedEffect
            withFrameNanos { }
            val selectedCenter = feedTabCenters[pagerState.currentPage] ?: return@LaunchedEffect
            val delta = selectedCenter - feedTabsViewportCenter
            val target = (feedTabsScrollState.value + delta.roundToInt())
                .coerceIn(0, feedTabsScrollState.maxValue)
            feedTabsScrollState.animateScrollTo(target)
        }

        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        ) { scaffoldPadding ->
            Column(
                Modifier.fillMaxSize()
                    .padding(scaffoldPadding)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            ) {
                Column(
                    Modifier.fillMaxWidth().clickable { showAdd = true }
                        .padding(start = 30.dp, end = 30.dp, top = 8.dp, bottom = 14.dp),
                ) {
                    Text(
                        "Your briefing",
                        style = MaterialTheme.typography.headlineMedium,
                        fontSize = 30.sp,
                        letterSpacing = (-0.9).sp,
                        maxLines = 1,
                    )
                    WaveUnderline(Modifier.padding(top = 1.dp).width(116.dp).height(8.dp))
                }

                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

                if (feeds.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                feedTabsViewportCenter = coordinates.boundsInWindow().center.x
                            }
                            .horizontalScroll(feedTabsScrollState)
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val sidePadding = configuration.screenWidthDp.dp / 2
                        Spacer(Modifier.width(sidePadding))
                        feeds.forEachIndexed { index, feed ->
                            val selected = pagerState.currentPage == index
                            Surface(
                                modifier = Modifier.padding(horizontal = 3.dp).clip(CircleShape).combinedClickable(
                                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                    onLongClick = { showManage = true },
                                ).onGloballyPositioned { coordinates ->
                                    feedTabCenters[index] = coordinates.boundsInWindow().center.x
                                },
                                shape = CircleShape,
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            ) {
                                Text(
                                    feed.title,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.width(sidePadding))
                    }
                }

                when {
                    subscriptions.isEmpty() -> Column(
                        Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Nothing here yet", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { showAdd = true }, modifier = Modifier.padding(top = 12.dp)) { Text("Import feed") }
                    }
                    error != null -> Column(
                        Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Something went wrong", style = MaterialTheme.typography.titleLarge)
                        Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { refresh() }) { Text("Refresh") }
                    }
                    else -> HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().weight(1f),
                        key = { index -> feeds.getOrNull(index)?.url ?: index },
                    ) { page ->
                        val feed = feeds.getOrNull(page)
                        when {
                            feed == null -> CircularProgressIndicator(Modifier.padding(30.dp))
                            feed.failed -> Column(
                                Modifier.fillMaxSize().padding(30.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("Something went wrong", style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = { refresh() }) { Text("Refresh") }
                            }
                            feed.articles.isEmpty() -> Column(
                                Modifier.fillMaxSize().padding(30.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("Nothing here yet", style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = { refresh() }) { Text("Refresh") }
                            }
                            else -> key(feed.url) {
                                val articlePagerState = rememberPagerState(pageCount = { feed.articles.size })
                                VerticalPager(
                                    state = articlePagerState,
                                    modifier = Modifier.fillMaxSize(),
                                    key = { index -> feed.articles[index].url + feed.articles[index].title },
                                ) { articleIndex ->
                                    FeedArticlePage(feed.articles[articleIndex], context)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAdd) AddFeedDialog(
            onDismiss = { showAdd = false },
            onAdd = { raw ->
                val url = raw.trim()
                if (url.isNotBlank()) {
                    val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                    RssSubscriptions.add(context, normalized)
                    subscriptions.clear(); subscriptions.addAll(RssSubscriptions.get(context))
                    showAdd = false
                }
            },
        )
        if (showManage) AlertDialog(
            onDismissRequest = { showManage = false },
            title = { Text("Your feeds") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    subscriptions.forEach { url ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(url, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = {
                                RssSubscriptions.remove(context, url)
                                subscriptions.remove(url)
                            }) { Icon(painterResource(R.drawable.delete_24px), contentDescription = "Remove feed") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showManage = false }) { Text("Done") } },
        )
}

@Composable
private fun FeedArticlePage(article: RssArticle, context: Context) {
    val hasImage = article.image.isNotBlank()
    val foreground = if (hasImage) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { openArticle(context, article.url) },
    ) {
        if (hasImage) {
            AsyncImage(
                model = article.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.16f),
                        0.34f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                ),
            )
        }
        if (article.category.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
                shape = CircleShape,
                color = if (hasImage) Color.Black.copy(alpha = 0.42f) else MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    article.category,
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (hasImage) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 24.dp, vertical = 34.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(article.title, style = MaterialTheme.typography.headlineMedium, color = foreground, letterSpacing = (-0.5).sp)
            if (article.description.isNotBlank()) {
                Text(article.description, style = MaterialTheme.typography.bodyLarge, color = foreground.copy(alpha = 0.9f), maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            val byline = listOf(article.author, article.date).filter { it.isNotBlank() }.joinToString(" · ")
            if (byline.isNotBlank()) Text(byline, style = MaterialTheme.typography.bodyMedium, color = foreground.copy(alpha = 0.74f))
        }
    }
}

@Composable
private fun WaveUnderline(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.secondary
    androidx.compose.foundation.Canvas(modifier) {
        val path = Path()
        val amplitude = size.height * 0.28f
        val baseline = size.height / 2f
        path.moveTo(0f, baseline)
        for (x in 1..size.width.toInt()) {
            val y = baseline + kotlin.math.sin(x / 8f) * amplitude
            path.lineTo(x.toFloat(), y)
        }
        drawPath(path, color, style = Stroke(width = 1.8.dp.toPx()))
    }
}

@Composable
private fun AddFeedDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var validating by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; previewTitle = null; validationError = null },
                    label = { Text("Feed link") },
                    placeholder = { Text("https://example.com/feed.xml") },
                    singleLine = true,
                )
                when {
                    validating -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Checking feed…")
                    }
                    validationError != null -> Text(validationError!!, color = MaterialTheme.colorScheme.error)
                    previewTitle != null -> Text(previewTitle!!, style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        confirmButton = {
            if (previewTitle == null) {
                TextButton(
                    onClick = {
                        val normalized = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
                        scope.launch {
                            validating = true
                            validationError = null
                            val result = withContext(Dispatchers.IO) { runCatching { fetchRss(normalized) } }
                            validating = false
                            result.onSuccess { previewTitle = it.title.ifBlank { URL(normalized).host } }
                                .onFailure { validationError = "This link isn’t a valid RSS or Atom feed." }
                        }
                    },
                    enabled = url.isNotBlank() && !validating,
                ) { Text("Preview") }
            } else TextButton(onClick = { onAdd(url) }) { Text("Link feed") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun openArticle(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun fetchRss(url: String): ParsedRssFeed {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("User-Agent", "OrbLauncher RSS Reader")
        instanceFollowRedirects = true
    }
    try {
        connection.inputStream.use { stream ->
            val parser = Xml.newPullParser().apply { setInput(stream, null) }
            var feedTitle = ""
            var feedImage = ""
            var inEntry = false
            var title = ""
            var link = ""
            var description = ""
            var date = ""
            var image = ""
            var category = ""
            var author = ""
            val result = mutableListOf<RssArticle>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val tagName = parser.name.lowercase()
                    val tag = tagName.substringAfterLast(':')
                    fun attribute(name: String): String? = (0 until parser.attributeCount)
                        .firstOrNull { parser.getAttributeName(it).substringAfterLast(':').equals(name, ignoreCase = true) }
                        ?.let { parser.getAttributeValue(it) }
                    val atomLink = if (tag == "link") attribute("href") else null
                    when (tag) {
                        "item", "entry" -> { inEntry = true; title = ""; link = ""; description = ""; date = ""; image = ""; category = ""; author = "" }
                        "title" -> {
                            val value = parser.nextText().plainText()
                            if (inEntry) title = value else if (feedTitle.isBlank()) feedTitle = value
                        }
                        "link" -> if (inEntry) link = atomLink ?: parser.nextText().trim()
                        "description", "summary", "encoded" -> if (inEntry && description.isBlank()) {
                            val raw = parser.nextText()
                            if (image.isBlank()) image = raw.firstImageUrl()
                            description = raw.plainText()
                        }
                        "pubdate", "published", "updated", "date" -> if (inEntry && date.isBlank()) date = parser.nextText().trim()
                        "author", "creator" -> if (inEntry && author.isBlank()) author = parser.nextText().plainText()
                        "category" -> if (inEntry && category.isBlank()) category = parser.nextText().plainText()
                        "url" -> if (!inEntry && feedImage.isBlank()) feedImage = parser.nextText().trim()
                        "image" -> {
                            val imageAttribute = attribute("url") ?: attribute("href") ?: ""
                            if (inEntry && image.isBlank()) image = imageAttribute
                            else if (!inEntry && feedImage.isBlank()) feedImage = imageAttribute
                        }
                        "thumbnail" -> if (inEntry && image.isBlank()) {
                            image = attribute("url") ?: attribute("href") ?: ""
                        }
                        "enclosure" -> if (inEntry && image.isBlank()) {
                            val mediaUrl = attribute("url") ?: attribute("href") ?: ""
                            val mimeType = attribute("type").orEmpty()
                            if (mimeType.startsWith("image/", ignoreCase = true) || mediaUrl.looksLikeImage()) image = mediaUrl
                        }
                        "content" -> if (inEntry) {
                            val mediaUrl = attribute("url") ?: attribute("href")
                            val mimeType = attribute("type").orEmpty()
                            val medium = attribute("medium").orEmpty()
                            if (mediaUrl != null && image.isBlank() &&
                                (mimeType.startsWith("image/", ignoreCase = true) || medium.equals("image", true) || mediaUrl.looksLikeImage())
                            ) image = mediaUrl
                            else if (mediaUrl == null && description.isBlank()) {
                                val raw = parser.nextText()
                                if (image.isBlank()) image = raw.firstImageUrl()
                                description = raw.plainText()
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && (parser.name.equals("item", true) || parser.name.equals("entry", true))) {
                    if (title.isNotBlank() && link.isNotBlank()) {
                        val base = URL(url)
                        val itemUrl = runCatching { URL(base, link).toString() }.getOrDefault(link)
                        val discoveredImage = image.ifBlank { description.firstImageUrl() }.ifBlank { feedImage }
                        val imageUrl = discoveredImage.takeIf { it.isNotBlank() }
                            ?.let { runCatching { URL(base, it).toString() }.getOrDefault(it) }
                            .orEmpty()
                        result += RssArticle(title, description, itemUrl, feedTitle.ifBlank { base.host }, date, imageUrl, category, author)
                    }
                    inEntry = false
                }
                event = parser.next()
            }
            return ParsedRssFeed(feedTitle, result)
        }
    } finally {
        connection.disconnect()
    }
}

private fun String.plainText(): String = HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_LEGACY)
    .toString().replace(Regex("\\s+"), " ").trim()

private fun String.looksLikeImage(): Boolean = Regex("\\.(jpe?g|png|webp|gif|avif)$", RegexOption.IGNORE_CASE)
    .containsMatchIn(substringBefore('?').substringBefore('#'))

private fun String.firstImageUrl(): String = Regex("<img[^>]+src=[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
    .find(this)?.groupValues?.getOrNull(1).orEmpty()
