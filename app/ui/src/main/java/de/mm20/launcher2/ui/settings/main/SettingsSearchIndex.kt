package de.mm20.launcher2.ui.settings.main

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.settings.about.AboutSettingsRoute
import de.mm20.launcher2.ui.settings.appearance.AppearanceSettingsRoute
import de.mm20.launcher2.ui.settings.apps.AppSearchSettingsRoute
import de.mm20.launcher2.ui.settings.appshortcuts.AppShortcutsSettingsRoute
import de.mm20.launcher2.ui.settings.backup.BackupSettingsRoute
import de.mm20.launcher2.ui.settings.breezyweather.BreezyWeatherSettingsRoute
import de.mm20.launcher2.ui.settings.buildinfo.BuildInfoSettingsRoute
import de.mm20.launcher2.ui.settings.calendarsearch.CalendarSearchSettingsRoute
import de.mm20.launcher2.ui.settings.colorscheme.ColorSchemesSettingsRoute
import de.mm20.launcher2.ui.settings.contacts.ContactsSettingsRoute
import de.mm20.launcher2.ui.settings.favorites.FavoritesSettingsRoute
import de.mm20.launcher2.ui.settings.filesearch.FileSearchSettingsRoute
import de.mm20.launcher2.ui.settings.filterbar.FilterBarSettingsRoute
import de.mm20.launcher2.ui.settings.gestures.GesturesSettingsRoute
import de.mm20.launcher2.ui.settings.hiddenitems.HiddenItemsSettingsRoute
import de.mm20.launcher2.ui.settings.homescreen.HomescreenSettingsRoute
import de.mm20.launcher2.ui.settings.icons.IconsSettingsRoute
import de.mm20.launcher2.ui.settings.locale.CurrencySettingsRoute
import de.mm20.launcher2.ui.settings.locale.LocaleSettingsRoute
import de.mm20.launcher2.ui.settings.locations.LocationsSettingsRoute
import de.mm20.launcher2.ui.settings.media.MediaIntegrationSettingsRoute
import de.mm20.launcher2.ui.settings.orb.OrbSettingsRoute
import de.mm20.launcher2.ui.settings.search.SearchSettingsRoute
import de.mm20.launcher2.ui.settings.searchactions.SearchActionsSettingsRoute
import de.mm20.launcher2.ui.settings.shapes.ShapeSchemesSettingsRoute
import de.mm20.launcher2.ui.settings.tags.TagsSettingsRoute
import de.mm20.launcher2.ui.settings.transparencies.TransparencySchemesSettingsRoute
import de.mm20.launcher2.ui.settings.typography.TypographiesSettingsRoute
import de.mm20.launcher2.ui.settings.unitconverter.UnitConverterSettingsRoute
import de.mm20.launcher2.ui.settings.weather.WeatherIntegrationSettingsRoute
import de.mm20.launcher2.ui.settings.wikipedia.WikipediaSettingsRoute
import java.text.Normalizer

/**
 * One searchable thing in settings: a page, or a preference on a page. Tapping it opens [route];
 * [path] names the pages leading there, so results that share a title can be told apart.
 */
data class SettingsSearchEntry(
    val title: String,
    val path: String,
    val keywords: String,
    val route: NavKey,
) {
    private val haystack = normalize("$title $path $keywords")
    private val normalizedTitle = normalize(title)

    /** 0 = no match, higher is better. Every word of the query must appear somewhere. */
    fun score(words: List<String>): Int {
        if (words.isEmpty() || !words.all { haystack.contains(it) }) return 0
        val query = words.joinToString(" ")
        return when {
            normalizedTitle == query -> 4
            normalizedTitle.startsWith(query) -> 3
            words.all { normalizedTitle.contains(it) } -> 2
            else -> 1
        }
    }
}

private val Diacritics = "\\p{Mn}+".toRegex()

internal fun normalize(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Diacritics, "")

internal fun searchSettings(index: List<SettingsSearchEntry>, query: String): List<SettingsSearchEntry> {
    val words = normalize(query).split(' ', ',').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return index
        .map { it to it.score(words) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .map { it.first }
}

/**
 * The static settings search index. Built from string resources, so it follows the app language.
 * Keep it in step with the pages: an entry points at the page holding the setting, not the row.
 */
internal fun buildSettingsSearchIndex(res: Resources): List<SettingsSearchEntry> {
    val entries = mutableListOf<SettingsSearchEntry>()

    /** Adds a top-level page, then lets [block] add its preferences with the page as their path. */
    fun page(
        @StringRes title: Int,
        route: NavKey,
        @StringRes summary: Int,
        block: PageScope.() -> Unit = {},
    ) {
        val t = res.getString(title)
        entries += SettingsSearchEntry(
            title = t,
            path = res.getString(R.string.settings),
            keywords = res.getString(summary),
            route = route,
        )
        PageScope(res, t, route, entries).block()
    }

    page(R.string.preference_screen_orb, OrbSettingsRoute, summary = R.string.preference_screen_orb_summary) {
        pref(R.string.preference_orb_voice)
        pref(R.string.preference_orb_default_assistant, R.string.preference_orb_voice)
        pref(R.string.preference_orb_wake_word, R.string.preference_orb_wake_word_keywords)
        pref(R.string.preference_orb_cloud_commands, R.string.preference_orb_cloud_commands_keywords)
        pref(R.string.preference_orb_voice_commands, R.string.preference_orb_voice)
        pref(R.string.preference_orb_shape, R.string.preference_orb_shape_keywords)
        pref(R.string.preference_orb_music, R.string.preference_orb_music_keywords)
        pref(R.string.preference_orb_media_ring, R.string.preference_orb_media_ring_keywords)
        pref(R.string.preference_orb_pie_menu, R.string.preference_orb_pie_menu_keywords)
    }

    page(R.string.preference_screen_appearance, AppearanceSettingsRoute, summary = R.string.preference_screen_appearance_summary) {
        pref(R.string.preference_theme)
        pref(R.string.preference_mdy_color_source)
        subpage(R.string.preference_screen_colors, ColorSchemesSettingsRoute)
        subpage(R.string.preference_screen_typography, TypographiesSettingsRoute)
        subpage(R.string.preference_screen_shapes, ShapeSchemesSettingsRoute)
        subpage(R.string.preference_screen_transparencies, TransparencySchemesSettingsRoute)
    }

    page(R.string.preference_screen_homescreen, HomescreenSettingsRoute, summary = R.string.preference_screen_homescreen_summary) {
        pref(R.string.preference_layout_fixed_rotation)
        pref(R.string.preference_screen_clockwidget, R.string.preference_screen_clockwidget_summary)
        subpage(R.string.preference_weather_integration, WeatherIntegrationSettingsRoute, R.string.preference_weather_integration_summary) {
            pref(R.string.preference_weather_provider)
            pref(R.string.preference_location)
            pref(R.string.preference_automatic_location)
            subpage(R.string.preference_breezyweather_integration, BreezyWeatherSettingsRoute)
        }
        subpage(R.string.preference_media_integration, MediaIntegrationSettingsRoute, R.string.preference_media_integration_summary)
        pref(R.string.preference_clockwidget_favorites_part, R.string.preference_clockwidget_favorites_part_summary)
        pref(R.string.preference_edit_button)
        pref(R.string.preference_category_searchbar)
        pref(R.string.preference_search_bar_style)
        pref(R.string.preference_layout_search_bar_position)
        pref(R.string.preference_category_wallpaper)
        pref(R.string.preference_dim_wallpaper)
        pref(R.string.preference_blur_wallpaper)
        pref(R.string.preference_charging_animation)
        pref(R.string.preference_category_system_bars)
        pref(R.string.preference_hide_status_bar)
        pref(R.string.preference_hide_nav_bar)
    }

    page(R.string.preference_screen_icons, IconsSettingsRoute, summary = R.string.preference_screen_icons_summary) {
        pref(R.string.preference_grid_column_count)
        pref(R.string.preference_grid_icon_size)
        pref(R.string.preference_grid_labels)
        pref(R.string.preference_icon_pack)
        pref(R.string.preference_icon_shape)
        pref(R.string.preference_themed_icons)
        pref(R.string.preference_force_themed_icons)
        pref(R.string.preference_category_badges)
        pref(R.string.preference_notification_badges)
    }

    page(R.string.preference_screen_search, SearchSettingsRoute, summary = R.string.preference_screen_search_summary) {
        subpage(R.string.preference_search_favorites, FavoritesSettingsRoute)
        subpage(R.string.preference_search_apps, AppSearchSettingsRoute)
        subpage(R.string.preference_search_files, FileSearchSettingsRoute)
        subpage(R.string.preference_search_contacts, ContactsSettingsRoute)
        subpage(R.string.preference_search_calendar, CalendarSearchSettingsRoute)
        subpage(R.string.preference_search_appshortcuts, AppShortcutsSettingsRoute)
        subpage(R.string.preference_search_unitconverter, UnitConverterSettingsRoute)
        subpage(R.string.preference_search_wikipedia, WikipediaSettingsRoute)
        subpage(R.string.preference_search_locations, LocationsSettingsRoute)
        pref(R.string.preference_search_calculator)
        pref(R.string.preference_search_websites)
        subpage(R.string.preference_screen_search_actions, SearchActionsSettingsRoute)
        subpage(R.string.preference_hidden_items, HiddenItemsSettingsRoute)
        subpage(R.string.preference_screen_tags, TagsSettingsRoute)
        subpage(R.string.preference_customize_filter_bar, FilterBarSettingsRoute)
        pref(R.string.preference_filter_bar)
        pref(R.string.preference_search_bar_auto_focus)
        pref(R.string.preference_search_bar_launch_on_enter)
        pref(R.string.preference_layout_search_results)
    }

    page(R.string.preference_screen_gestures, GesturesSettingsRoute, summary = R.string.preference_screen_gestures_summary) {
        pref(R.string.preference_gesture_tap)
        pref(R.string.preference_gesture_double_tap)
        pref(R.string.preference_gesture_triple_tap)
        pref(R.string.preference_gesture_long_press)
        pref(R.string.preference_gesture_home_button)
    }

    page(R.string.preference_screen_locale, LocaleSettingsRoute, summary = R.string.preference_screen_locale_summary) {
        pref(R.string.preference_language)
        pref(R.string.preference_measurement_system)
        pref(R.string.preference_clock_widget_time_format)
        pref(R.string.preference_calendar_system)
        pref(R.string.preference_transliteration)
        pref(R.string.preference_form_of_address)
        subpage(R.string.preference_currencies, CurrencySettingsRoute)
    }

    page(R.string.preference_screen_backup, BackupSettingsRoute, summary = R.string.preference_screen_backup_summary) {
        pref(R.string.preference_backup)
        pref(R.string.preference_restore)
    }

    page(R.string.preference_screen_about, AboutSettingsRoute, summary = R.string.preference_screen_about_summary) {
        pref(R.string.preference_version)
        subpage(R.string.preference_screen_buildinfo, BuildInfoSettingsRoute)
        pref(R.string.preference_about_license)
    }

    return entries
}

internal class PageScope(
    private val res: Resources,
    private val path: String,
    private val route: NavKey,
    private val entries: MutableList<SettingsSearchEntry>,
) {
    /** A preference shown on this page. */
    fun pref(@StringRes title: Int, @StringRes keywords: Int? = null) {
        entries += SettingsSearchEntry(
            title = res.getString(title),
            path = path,
            keywords = keywords?.let { res.getString(it) } ?: "",
            route = route,
        )
    }

    /** A page reached from this one, with its own preferences in [block]. */
    fun subpage(
        @StringRes title: Int,
        route: NavKey,
        @StringRes keywords: Int? = null,
        block: PageScope.() -> Unit = {},
    ) {
        val t = res.getString(title)
        entries += SettingsSearchEntry(
            title = t,
            path = path,
            keywords = keywords?.let { res.getString(it) } ?: "",
            route = route,
        )
        PageScope(res, "$path › $t", route, entries).block()
    }
}
