package de.mm20.launcher2.ui.launcher.setup

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.mm20.launcher2.orb.pie.PieRepository
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.preferences.ClockWidgetStyle
import de.mm20.launcher2.preferences.WallpaperType
import de.mm20.launcher2.preferences.ui.ClockWidgetSettings
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.launcher.widgets.clock.getClockStyleName
import org.koin.compose.koinInject

/**
 * Whether the first-run setup still has to be shown. Only fresh installs see it: an install
 * that was updated from a version without the setup is marked done right away, and keeps the
 * orb where the old defaults put it if the user never moved it.
 */
object FirstRunSetup {
    private const val PREFS = "orb_setup"
    private const val KEY_DONE = "done_v1"
    private const val KEY_STEP = "step"

    fun isNeeded(context: Context, pieRepository: PieRepository): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return false
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val updatedInstall = info != null && info.firstInstallTime != info.lastUpdateTime
        if (updatedInstall) {
            if (!pieRepository.hasOrbLayout()) pieRepository.saveOrbLayout(0.5f, 0.52f, 1f)
            markDone(context)
            return false
        }
        return true
    }

    /**
     * The step the user was on. Becoming the default home app restarts the launcher, so the
     * step is kept on disk, not only in saved state, to pick up where it left off.
     */
    internal fun savedStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_STEP, 0)

    internal fun saveStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_STEP, step) }
    }

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_DONE, true) }
    }
}

private enum class SetupStep { Wallpaper, Clock, Permissions, DefaultLauncher }

/**
 * Full-screen first-run setup, drawn over the home screen. The wallpaper and clock steps leave
 * a rounded window open onto the real home screen behind them, so each choice is previewed
 * live exactly as it will look.
 */
@Composable
fun FirstRunSetupOverlay(onDone: () -> Unit) {
    val context = LocalContext.current
    var stepIndex by rememberSaveable {
        mutableIntStateOf(FirstRunSetup.savedStep(context).coerceIn(0, SetupStep.entries.lastIndex))
    }
    LaunchedEffect(stepIndex) { FirstRunSetup.saveStep(context, stepIndex) }
    val step = SetupStep.entries[stepIndex]
    val background = MaterialTheme.colorScheme.surface
    var previewRect by remember { mutableStateOf<Rect?>(null) }
    val cornerPx = with(LocalDensity.current) { 28.dp.toPx() }
    val showsPreview = step == SetupStep.Wallpaper || step == SetupStep.Clock

    BackHandler(enabled = stepIndex > 0) { stepIndex-- }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Keep every touch, including those on the preview window, away from the home
            // screen underneath.
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                drawRect(background)
                val r = previewRect
                if (showsPreview && r != null) {
                    drawRoundRect(
                        color = Color.Black,
                        topLeft = r.topLeft,
                        size = r.size,
                        cornerRadius = CornerRadius(cornerPx),
                        blendMode = BlendMode.Clear,
                    )
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            if (showsPreview) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp)
                        .onGloballyPositioned { previewRect = it.boundsInRoot() }
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            RoundedCornerShape(28.dp),
                        )
                )
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = if (showsPreview) Modifier.fillMaxWidth() else Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "setupStep",
            ) { s ->
                when (s) {
                    SetupStep.Wallpaper -> WallpaperStep()
                    SetupStep.Clock -> ClockStep()
                    SetupStep.Permissions -> PermissionsStep()
                    SetupStep.DefaultLauncher -> DefaultLauncherStep()
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (stepIndex > 0) {
                    TextButton(onClick = { stepIndex-- }) { Text(stringResource(R.string.setup_back)) }
                }
                Spacer(Modifier.weight(1f))
                StepDots(count = SetupStep.entries.size, current = stepIndex)
                Spacer(Modifier.weight(1f))
                val last = stepIndex == SetupStep.entries.lastIndex
                Button(onClick = { if (last) onDone() else stepIndex++ }) {
                    Text(stringResource(if (last) R.string.setup_finish else R.string.setup_next))
                }
            }
        }
    }
}

@Composable
private fun StepDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Surface(
                shape = RoundedCornerShape(50),
                color = if (i == current) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(width = if (i == current) 18.dp else 6.dp, height = 6.dp),
            ) {}
        }
    }
}

@Composable
private fun StepHeader(title: String, body: String) {
    Column(modifier = Modifier.padding(horizontal = 24.dp).padding(top = 20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> OptionRow(options: List<Pair<String, T>>, isSelected: (T) -> Boolean, onSelect: (T) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val index = options.indexOfFirst { isSelected(it.second) }
        // Only scroll when the selection would otherwise start off screen.
        if (index > 2) listState.scrollToItem(index - 1)
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 16.dp),
    ) {
        items(options, key = { it.first }) { (label, value) ->
            FilterChip(
                selected = isSelected(value),
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun WallpaperStep() {
    val uiSettings: UiSettings = koinInject()
    val current by uiSettings.wallpaperType.collectAsState(initial = null)
    val options = listOf(
        stringResource(R.string.setup_wallpaper_keep_system) to WallpaperType.System,
        stringResource(R.string.setup_wallpaper_random) to WallpaperType.Random,
        stringResource(R.string.preference_wallpaper_type_aurora) to WallpaperType.Aurora,
        stringResource(R.string.preference_wallpaper_type_water) to WallpaperType.Water,
        stringResource(R.string.preference_wallpaper_type_prism) to WallpaperType.Prism,
        stringResource(R.string.preference_wallpaper_type_solar_system) to WallpaperType.SolarSystem,
        stringResource(R.string.preference_wallpaper_type_boids) to WallpaperType.Boids,
        stringResource(R.string.preference_wallpaper_type_double_pendulum) to WallpaperType.DoublePendulum,
        stringResource(R.string.preference_wallpaper_type_strings) to WallpaperType.Strings,
        stringResource(R.string.preference_wallpaper_type_magnetic_field) to WallpaperType.MagneticField,
        stringResource(R.string.preference_wallpaper_type_fluid_ink) to WallpaperType.FluidInk,
        stringResource(R.string.preference_wallpaper_type_gravity_sand) to WallpaperType.GravitySand,
        stringResource(R.string.preference_wallpaper_type_flow_field) to WallpaperType.FlowField,
        stringResource(R.string.preference_wallpaper_type_reaction_diffusion) to WallpaperType.ReactionDiffusion,
        stringResource(R.string.preference_wallpaper_type_tesseract) to WallpaperType.Tesseract,
        stringResource(R.string.preference_wallpaper_type_topographic) to WallpaperType.Topographic,
        stringResource(R.string.preference_wallpaper_type_games) to WallpaperType.Games,
    )
    Column {
        StepHeader(
            stringResource(R.string.setup_wallpaper_title),
            stringResource(R.string.setup_wallpaper_body),
        )
        if (current != null) {
            OptionRow(options, isSelected = { it == current }, onSelect = uiSettings::setWallpaperType)
        }
    }
}

@Composable
private fun ClockStep() {
    val context = LocalContext.current
    val clockSettings: ClockWidgetSettings = koinInject()
    val current by clockSettings.clockStyle.collectAsState(initial = null)
    val digital1 by clockSettings.digital1.collectAsState(initial = ClockWidgetStyle.Digital1())
    val analog by clockSettings.analog.collectAsState(initial = ClockWidgetStyle.Analog())
    val styles = listOf(
        digital1,
        ClockWidgetStyle.Digital2,
        analog,
        ClockWidgetStyle.Orbit,
        ClockWidgetStyle.Segment,
        ClockWidgetStyle.Binary,
        ClockWidgetStyle.Flip,
        ClockWidgetStyle.Particles,
        ClockWidgetStyle.SunArc,
        ClockWidgetStyle.Empty,
    )
    Column {
        StepHeader(
            stringResource(R.string.setup_clock_title),
            stringResource(R.string.setup_clock_body),
        )
        val selected = current
        if (selected != null) {
            OptionRow(
                options = styles.map { getClockStyleName(context, it) to it },
                isSelected = { it::class == selected::class },
                onSelect = clockSettings::setClockStyle,
            )
        }
    }
}

/** Re-reads [check] every time the activity comes back, e.g. from a system settings page. */
@Composable
private fun rememberCheckedOnResume(check: () -> Boolean): Boolean {
    var value by remember { mutableStateOf(check()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { value = check() }
    }
    return value
}

@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val permissionsManager: PermissionsManager = koinInject()

    var micGranted by remember { mutableStateOf(false) }
    val micChecked = rememberCheckedOnResume {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { micGranted = it }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        StepHeader(
            stringResource(R.string.setup_permissions_title),
            stringResource(R.string.setup_permissions_body),
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            icon = R.drawable.ic_mic,
            title = stringResource(R.string.setup_permission_mic_title),
            body = stringResource(R.string.setup_permission_mic_body),
            granted = micGranted || micChecked,
            onAllow = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
        listOf(
            Triple(PermissionGroup.Notifications, R.drawable.notifications_24px,
                R.string.setup_permission_notifications_title to R.string.setup_permission_notifications_body),
            Triple(PermissionGroup.Contacts, R.drawable.person_24px,
                R.string.setup_permission_contacts_title to R.string.setup_permission_contacts_body),
            Triple(PermissionGroup.Calendar, R.drawable.calendar_today_24px,
                R.string.setup_permission_calendar_title to R.string.setup_permission_calendar_body),
            Triple(PermissionGroup.Location, R.drawable.location_on_24px,
                R.string.setup_permission_location_title to R.string.setup_permission_location_body),
        ).forEach { (group, icon, text) ->
            val granted by permissionsManager.hasPermission(group).collectAsState(initial = false)
            PermissionCard(
                icon = icon,
                title = stringResource(text.first),
                body = stringResource(text.second),
                granted = granted,
                onAllow = { activity?.let { permissionsManager.requestPermission(it, group) } },
            )
        }
    }
}

@Composable
private fun PermissionCard(icon: Int, title: String, body: String, granted: Boolean, onAllow: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Icon(
                    painterResource(R.drawable.check_circle_24px_filled),
                    contentDescription = stringResource(R.string.setup_permission_allowed),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                FilledTonalButton(onClick = onAllow) { Text(stringResource(R.string.setup_permission_allow)) }
            }
        }
    }
}

private fun isDefaultLauncher(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager != null) return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
    }
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName == context.packageName
}

@Composable
private fun DefaultLauncherStep() {
    val context = LocalContext.current
    // The role dialog is its own activity, so coming back from it re-runs the check.
    val isDefault = rememberCheckedOnResume { isDefaultLauncher(context) }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(if (isDefault) R.drawable.check_circle_48px else R.drawable.home_24px),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(if (isDefault) R.string.setup_default_done else R.string.setup_default_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (!isDefault) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.setup_default_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                val roleIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.getSystemService(RoleManager::class.java)
                        ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }
                        ?.createRequestRoleIntent(RoleManager.ROLE_HOME)
                } else null
                runCatching {
                    roleLauncher.launch(roleIntent ?: Intent(Settings.ACTION_HOME_SETTINGS))
                }
            }) {
                Text(stringResource(R.string.setup_default_button))
            }
        }
    }
}

private fun Context.findActivity(): AppCompatActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is AppCompatActivity) return c
        c = c.baseContext
    }
    return null
}
