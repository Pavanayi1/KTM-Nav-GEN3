package com.navigator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.components.pressFeedback
import com.navigator.app.ui.components.enterStagger
import com.navigator.app.ui.theme.NavIcons

private data class Tile(
    val label: String,
    val sub: String,
    val icon: ImageVector,
    val tint: androidx.compose.ui.graphics.Color,
    /** Something is live behind this tile: drawn filled and glowing, else an outline box. */
    val active: Boolean,
)

/**
 * Grid / Home (screen 02). A persistent connection banner, the READY TO RIDE
 * headline, and a 2-column tile grid. A tile is filled and glowing only while
 * something is live behind it (notifications, a route, music, a call); Home and
 * Exit are utility rows.
 * The currently focused tile (driven by the handlebar remote via
 * [ControllerStateMachine.gridSelection]) is highlighted so RCM navigation is
 * obvious; tiles are also tappable and feed the same pipeline.
 */
@Composable
fun GridMenuScreen(
    gridSelection: Int,
    onSelectGrid: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRides: () -> Unit = {},
    onOpenDeveloper: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings = remember { com.navigator.app.settings.AppSettings(context) }
    var notificationAccessGranted by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccessGranted =
                    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifCount by com.navigator.app.notifications.NotificationRepository.entries.collectAsState()
    val navText by com.navigator.app.notifications.NotificationRepository.currentNavText.collectAsState()

    val nowPlaying by com.navigator.app.audio.NowPlayingRepository.state.collectAsState()
    val intercomCall by com.mensa.riderintercom.call.IntercomService.instance.collectAsState()

    // A tile lights up (filled + glow) only while something is live behind it,
    // so a glance at the grid says "you have notifications / a route / music"
    // without reading the small print. Idle tiles are plain outline boxes.
    val tiles = listOf(
        Tile("NOTIFICATION", if (notifCount.isEmpty()) "None" else "${notifCount.size} new",
            NavIcons.Bell, Ktm.Orange, active = notifCount.isNotEmpty()),
        Tile("DIRECTION", if (navText != null) "Nav active" else "No route",
            NavIcons.Navigation, Ktm.Orange, active = navText != null),
        Tile("MUSIC", nowPlaying?.let { np -> if (np.artist.isNotBlank()) "${np.title} — ${np.artist}" else np.title } ?: "Nothing playing",
            NavIcons.Music, Ktm.Orange, active = nowPlaying != null),
        // Same index as "Intercom" in ControllerStateMachine.gridItems - see there.
        Tile("INTERCOM", if (intercomCall != null) "Call active" else "Talk to pillion",
            NavIcons.Phone, Ktm.Orange, active = intercomCall != null),
        Tile("RIDES", "Routes & analytics", NavIcons.Wave, Ktm.Orange, active = false),
        Tile("REMOTE", BccuConnectionService.remoteModeLabel(context),
            NavIcons.Gamepad, Ktm.Orange, active = false),
        Tile("HOME", "Minimize app", NavIcons.Minimize, Ktm.Orange, active = false),
        Tile("EXIT", "Disconnect & stop service", NavIcons.Power, Ktm.Danger, active = false),
    )

    // Collapsing header: scrolling the tiles up first folds the quick toggles
    // away and pulls the bike card up into their space (and slims it), and
    // only then scrolls the grid. Scrolling back down does the reverse once the
    // grid is at its top. Driven through nested scroll rather than by watching
    // the grid's offset: a header that collapses because the grid scrolled
    // gives the grid more room, which can un-scroll it and re-expand the
    // header - a visible flicker loop on a short grid.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val collapseRangePx = with(density) { COLLAPSE_RANGE.toPx() }
    var collapse by remember { mutableStateOf(0f) } // 0 = expanded, 1 = fully collapsed
    val collapseConnection = remember(collapseRangePx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): Offset {
                if (available.y >= 0f) return Offset.Zero // finger moving down: grid goes first
                val next = (collapse - available.y / collapseRangePx).coerceAtMost(1f)
                val used = (next - collapse) * collapseRangePx
                collapse = next
                return Offset(0f, -used)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): Offset {
                if (available.y <= 0f) return Offset.Zero // grid at its top: expand with what's left
                val next = (collapse - available.y / collapseRangePx).coerceAtLeast(0f)
                val used = (collapse - next) * collapseRangePx
                collapse = next
                return Offset(0f, used)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(collapseConnection)
            .background(Ktm.Screen)
            .systemBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 6.dp, bottom = 12.dp),
    ) {
        // Header: the brand's own wordmark + tagline, then two outline text
        // pills. Text rather than icons because the design makes these the
        // only two touch-only destinations - they should read as words, not
        // be guessed from a glyph with gloves on.
        val brandTheme = com.navigator.app.ui.theme.themeFor(settings.brand)
        // Hidden developer-mode unlock: ten quick taps on the wordmark (the
        // Android "tap the build number" idiom). Taps more than 2 s apart
        // restart the count, so idle prodding never trips it.
        var devMode by remember { mutableStateOf(settings.devModeEnabled) }
        var devTaps by remember { mutableStateOf(0) }
        var lastDevTapMs by remember { mutableStateOf(0L) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.clickable(
                    // No ripple: the wordmark shouldn't look tappable.
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    devTaps = if (now - lastDevTapMs > 2000L) 1 else devTaps + 1
                    lastDevTapMs = now
                    when {
                        devMode -> if (devTaps == 3) {
                            android.widget.Toast.makeText(context, "Developer mode is already on", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        devTaps >= 10 -> {
                            devMode = true
                            settings.devModeEnabled = true
                            devTaps = 0
                            android.widget.Toast.makeText(context, "Developer mode enabled", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        devTaps >= 6 -> android.widget.Toast.makeText(
                            context, "${10 - devTaps} taps from developer mode", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
            ) {
                Text(
                    brandTheme.wordmark, color = Ktm.White, fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.Bold,
                    fontStyle = if (brandTheme.wordmarkItalic) FontStyle.Italic else FontStyle.Normal,
                    fontSize = 26.sp, lineHeight = 27.sp, letterSpacing = (-0.26).sp,
                )
                Text(
                    brandTheme.tagline, color = Ktm.Orange, fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 2.5.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (devMode) HeaderPill("DEV", onOpenDeveloper)
                HeaderPill("RIDES", onOpenRides)
                HeaderPill("SETTINGS", onOpenSettings)
            }
        }

        ConnectionBanner()

        // Folds to zero height (and fades) as the header collapses.
        Box(
            Modifier
                .clipToBounds()
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    val h = (p.height * (1f - collapse)).roundToInt()
                    layout(p.width, h) { p.placeRelative(0, 0) }
                }
                .graphicsLayer { alpha = 1f - collapse },
        ) { QuickToggles() }

        // Greeting eyebrow + headline on the brand's hero illustration. The
        // rider's name comes from onboarding; without one the eyebrow just
        // states the time of day.
        val userName = remember { settings.userName }
        Box(Modifier.enterStagger(0)) {
            HeroBanner(
                brandTheme.brand, greetingFor(userName),
                topPadding = lerp(18.dp, 10.dp, collapse),
                height = lerp(HERO_HEIGHT, HERO_HEIGHT_COLLAPSED, collapse),
            )
        }

        if (!notificationAccessGranted) {
            NotificationAccessWarning(onOpenSettings)
        }

        // Exit is the last item in the grid list but renders as a full-width
        // row: it's destructive, so the design gives it its own line rather
        // than letting it sit in a corner cell next to Home.
        val gridTiles = tiles.dropLast(1)
        val exitIndex = tiles.lastIndex
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            // The grid clips its children to its bounds, which would shear the
            // live-tile glow off the outer edges. Widen the grid by GLOW_ROOM
            // past the screen padding on each side and pad its content back
            // in, so the tiles sit where they did and the glow has room.
            modifier = Modifier
                .padding(top = 16.dp - GLOW_ROOM)
                .layout { measurable, constraints ->
                    val extra = GLOW_ROOM.roundToPx() * 2
                    val p = measurable.measure(
                        constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra)
                    )
                    layout(constraints.maxWidth, p.height) { p.placeRelative(-extra / 2, 0) }
                }
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(GLOW_ROOM),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // An odd count would leave a hole in the last row, so the last grid
            // tile takes the full width instead (with Intercom in, that is Home,
            // which then reads as a pair with Exit below it).
            items(
                gridTiles.size,
                key = { it },
                span = { index ->
                    GridItemSpan(if (gridTiles.size % 2 == 1 && index == gridTiles.lastIndex) 2 else 1)
                },
            ) { index ->
                // GridTile keeps a fixed aspect ratio, so at double width it
                // would be double height - the full-width tile is drawn as a
                // slim WideTile row instead, matching Exit.
                val spansRow = gridTiles.size % 2 == 1 && index == gridTiles.lastIndex
                Box(Modifier.enterStagger(index + 1)) {
                    if (spansRow) {
                        WideTile(
                            tile = gridTiles[index],
                            focused = index == gridSelection,
                            onClick = { onSelectGrid(index) },
                        )
                    } else {
                        GridTile(
                            tile = gridTiles[index],
                            focused = index == gridSelection,
                            onClick = { onSelectGrid(index) },
                        )
                    }
                }
            }
            item(span = { GridItemSpan(2) }) {
                Box(Modifier.enterStagger(gridTiles.size + 1)) { WideTile(
                    tile = tiles[exitIndex],
                    focused = exitIndex == gridSelection,
                    onClick = { onSelectGrid(exitIndex) },
                ) }
            }
        }
    }
}

/**
 * Home hero: the brand's white line-art bike (drawable-nodpi/hero_*.webp),
 * right-aligned on its own dark card so it reads the same in the dark KTM and
 * light Husqvarna themes. The card colour is sampled from each image's
 * background so the art's edges disappear into it; the text sits top-left,
 * where both illustrations are empty, over a fade that keeps it legible if a
 * narrow phone pushes the bike under it.
 */
@Composable
private fun HeroBanner(
    brand: com.navigator.app.ui.theme.Brand,
    greeting: String,
    topPadding: androidx.compose.ui.unit.Dp = 18.dp,
    height: androidx.compose.ui.unit.Dp = HERO_HEIGHT,
) {
    val cardBg = com.navigator.app.ui.components.bikeArtFor(brand).cardColor
    val white = androidx.compose.ui.graphics.Color.White
    com.navigator.app.ui.components.BikeHeroCard(
        brand = brand,
        modifier = Modifier.padding(top = topPadding).fillMaxWidth().height(height),
        corner = 4.dp,
        artAlignment = Alignment.CenterEnd,
        artPadding = 6.dp,
    ) {
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to cardBg,
                    0.30f to cardBg.copy(alpha = 0.80f),
                    0.55f to androidx.compose.ui.graphics.Color.Transparent,
                )
            )
        )
        Column(Modifier.padding(start = 18.dp, top = 18.dp)) {
            Text(
                greeting, color = white.copy(alpha = 0.62f), fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.4.sp,
            )
            Text(
                "READY\nTO RIDE",
                modifier = Modifier.padding(top = 6.dp),
                color = white,
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                fontSize = 40.sp,
                lineHeight = 38.sp,
                letterSpacing = (-0.6).sp,
            )
        }
    }
}

/**
 * Home-screen buttons are sharp-cornered (field request, R66): a near-square
 * edge reads as the bikes' own angular design language rather than a generic
 * rounded-card app. One shape for every home control so they stay in step.
 */
private val SharpShape = RoundedCornerShape(3.dp)

/** Space around the tile grid for the live-tile glow to draw into. */
private val GLOW_ROOM = 8.dp

private val HERO_HEIGHT = 172.dp
private val HERO_HEIGHT_COLLAPSED = 128.dp

/**
 * How far the finger travels to fully collapse the header: roughly the quick
 * toggles' height plus the bike card's shrink, so the header tracks the finger.
 */
private val COLLAPSE_RANGE = 96.dp

/** Outline text pill in the home header ("RIDES", "SETTINGS"). */
@Composable
private fun HeaderPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.pressFeedback(onClick = onClick)
            .clip(SharpShape)
            .border(1.dp, Ktm.Border, SharpShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label, color = Ktm.TextSecondary, fontFamily = BarlowCondensed,
            fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.9.sp,
        )
    }
}

private fun greetingFor(userName: String?): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val part = when (hour) {
        in 5..11 -> "GOOD MORNING"
        in 12..16 -> "GOOD AFTERNOON"
        in 17..21 -> "GOOD EVENING"
        else -> "RIDE SAFE"
    }
    return if (userName.isNullOrBlank()) part else "$part, ${userName.uppercase()}"
}

/**
 * One-tap ride tools right on the home screen (R20 field request): the
 * settings that get flipped at the kerb - approach beeps, overspeed alert,
 * power saver - without digging into Settings. Touch-only, deliberately not on
 * the handlebar-remote path (the 2x2 grid below owns that).
 */
@Composable
private fun QuickToggles() {
    val context = LocalContext.current
    val settings = remember { com.navigator.app.settings.AppSettings(context) }
    var beeps by remember { mutableStateOf(settings.turnBeepEnabled) }
    var overspeed by remember { mutableStateOf(settings.overspeedEnabled) }
    var powerSave by remember { mutableStateOf(settings.powerSaveEnabled) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickChip("BEEPS", beeps, Modifier.weight(1f)) {
            beeps = !beeps; settings.turnBeepEnabled = beeps
            if (!beeps) com.navigator.app.audio.TurnBeeper.reset()
        }
        QuickChip("SPEED", overspeed, Modifier.weight(1f)) {
            overspeed = !overspeed; settings.overspeedEnabled = overspeed
        }
        QuickChip("PWR SAVE", powerSave, Modifier.weight(1f)) {
            powerSave = !powerSave; settings.powerSaveEnabled = powerSave
            BccuConnectionService.reevaluateRouteRecordingIfRunning()
            BccuConnectionService.reevaluateEngineDetectIfRunning()
        }
    }
}

/**
 * One quick toggle: a dot plus a label on a single line. ON is green (dot and
 * border), OFF is grey - field request (R76): on/off has to read at a glance,
 * and orange-for-on looked the same as the orange everywhere else.
 */
@Composable
private fun QuickChip(label: String, on: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Row(
        modifier = modifier.pressFeedback(onClick = onToggle)
            .clip(SharpShape)
            .background(if (on) Ktm.SurfaceAlt else Ktm.Surface)
            .border(1.dp, if (on) Ktm.Green.copy(alpha = 0.65f) else Ktm.Border, SharpShape)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (on) Ktm.Green else Ktm.Dim3),
        )
        Text(
            label,
            color = if (on) Ktm.TextPrimary else Ktm.Dim,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 7.dp),
        )
    }
}

@Composable
private fun ConnectionBanner() {
    val state by BccuConnectionService.connectionState.collectAsState()
    val deviceName by BccuConnectionService.deviceName.collectAsState()
    val rssi by BccuConnectionService.signalRssi.collectAsState()
    val profile by BccuConnectionService.vehicleProfile.collectAsState()

    val connected = state == BccuConnectionService.ConnectionState.AUTHENTICATED
    val connecting = state == BccuConnectionService.ConnectionState.CONNECTING
    val dotColor = when {
        connected -> Ktm.Green
        connecting -> Ktm.Orange
        else -> Ktm.Danger
    }
    val title = when {
        connected -> "CONNECTED"
        connecting -> "CONNECTING…"
        else -> "DISCONNECTED"
    }
    val sub = when {
        connected -> "${deviceName ?: "bike"} · ${profile?.generationLabel ?: "session secure"}"
        connecting -> "${deviceName ?: "bike"} · handshaking"
        else -> "Not linked to the dash"
    }
    val borderColor = if (connected) Ktm.ConnBorder else Ktm.Border

    // While connecting: the dot sends out a radar ping and a light sweeps
    // along the banner's bottom edge, so "working on it" is visibly different
    // from "stuck". Both stop the moment the state changes (an infinite
    // transition would keep redrawing the banner every frame while connected).
    val pingAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    val sweepAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(connecting) {
        if (!connecting) return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            launch {
                while (true) {
                    pingAnim.snapTo(0f)
                    pingAnim.animateTo(1f, androidx.compose.animation.core.tween(
                        1200, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                }
            }
            launch {
                while (true) {
                    sweepAnim.snapTo(0f)
                    sweepAnim.animateTo(1f, androidx.compose.animation.core.tween(
                        1400, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SharpShape)
            .background(Ktm.ConnBanner)
            .border(1.dp, borderColor, SharpShape)
            .drawWithContent {
                drawContent()
                if (connecting) {
                    val barW = size.width * 0.28f
                    val x = -barW + (size.width + barW) * sweepAnim.value
                    val h = 2.dp.toPx()
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                            0f to androidx.compose.ui.graphics.Color.Transparent,
                            0.5f to Ktm.Orange,
                            1f to androidx.compose.ui.graphics.Color.Transparent,
                            startX = x, endX = x + barW,
                        ),
                        topLeft = Offset(x, size.height - h),
                        size = androidx.compose.ui.geometry.Size(barW, h),
                    )
                }
            }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(9.dp).drawBehind {
                if (connecting) {
                    drawCircle(
                        color = dotColor.copy(alpha = 0.5f * (1f - pingAnim.value)),
                        radius = size.minDimension / 2f * (1f + 1.6f * pingAnim.value),
                    )
                }
            },
        ) {
            Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(dotColor))
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            // Status word carries the state colour - green/amber/red is the
            // fastest read on this screen and must not depend on the dot alone.
            Text(title, color = dotColor, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, lineHeight = 14.sp)
            Text(sub, color = Ktm.ConnSub, fontFamily = JetBrainsMono, fontSize = 12.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
        }
        if (connected) SignalBars(rssi)
    }
}

/**
 * Four-bar signal meter. RSSI in dBm maps to bars the way phones do it:
 * stronger than -60 is full, weaker than -90 is one bar. Unknown RSSI shows
 * a single lit bar rather than an empty meter, since we do know we're linked.
 */
@Composable
private fun SignalBars(rssi: Int?) {
    val lit = when {
        rssi == null -> 1
        rssi >= -60 -> 4
        rssi >= -72 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(16.dp),
    ) {
        listOf(6, 9, 12, 16).forEachIndexed { index, height ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .background(if (index < lit) Ktm.Green else Ktm.Bezel),
            )
        }
    }
}

@Composable
private fun NotificationAccessWarning(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(SharpShape)
            .background(Ktm.SurfaceAlt)
            .border(1.dp, Ktm.Danger, SharpShape)
            .clickable(onClick = onOpenSettings)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Notification access off — tap to fix in Settings",
            color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text("›", color = Ktm.Danger, fontSize = 18.sp)
    }
}

/**
 * Grid tile, in the brand's colours: live tiles (tile.active) are solid
 * accent - orange with black text on KTM, blue with white on Husqvarna - and
 * the rest are outlined in the accent on the normal surface. When the
 * handlebar remote's focus lands here the tile gets a thick ring in the
 * heading colour (white on KTM, navy on Husqvarna): an accent ring would
 * vanish against a solid accent tile, and the rider has to spot focus at a
 * glance from the saddle.
 */
@Composable
private fun GridTile(tile: Tile, focused: Boolean, onClick: () -> Unit) {
    val filled = tile.active
    // Live tiles breathe a soft accent glow around their edge; the glow is
    // drawn outside the tile's bounds, into the grid gutter, so it never
    // covers the label. Only active tiles run the animation.
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(filled) {
        if (!filled) { glow.snapTo(0f); return@LaunchedEffect }
        while (true) {
            glow.animateTo(1f, androidx.compose.animation.core.tween(1100))
            glow.animateTo(0.35f, androidx.compose.animation.core.tween(1100))
        }
    }
    val glowColor = tile.tint
    val bg = when {
        filled -> Ktm.Orange
        focused -> Ktm.SurfaceAlt
        else -> Ktm.Surface
    }
    val content = if (filled) Ktm.OnAccent else Ktm.White
    val sub = when {
        filled -> Ktm.OnAccent.copy(alpha = 0.82f)
        focused -> Ktm.Orange
        else -> Ktm.Muted
    }
    val ring = when {
        focused -> Ktm.White
        filled -> Ktm.Orange
        else -> Ktm.Orange.copy(alpha = 0.55f)
    }
    Box(
        modifier = Modifier.pressFeedback(pressedScale = 0.95f, onClick = onClick)
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .aspectRatio(1.62f)
            .drawBehind {
                if (filled) {
                    val a = glow.value
                    // Three widening translucent outlines read as a glow
                    // without a blur pass.
                    for (i in 1..3) {
                        val spread = (2.5f * i).dp.toPx()
                        drawRoundRect(
                            color = glowColor.copy(alpha = 0.22f * a / i),
                            topLeft = Offset(-spread, -spread),
                            size = androidx.compose.ui.geometry.Size(size.width + spread * 2, size.height + spread * 2),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx() + spread),
                        )
                    }
                }
            }
            .clip(SharpShape)
            .background(bg)
            .border(if (focused) 3.dp else 1.dp, ring, SharpShape)
            .padding(15.dp),
    ) {
        Icon(tile.icon, contentDescription = tile.label, tint = if (filled) Ktm.OnAccent else tile.tint,
            modifier = Modifier.align(Alignment.TopStart).size(22.dp))
        Column(modifier = Modifier.align(Alignment.BottomStart)) {
            Text(tile.label, color = content, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                fontSize = 18.sp, letterSpacing = 0.2.sp)
            Text(
                tile.sub,
                color = sub,
                fontFamily = JetBrainsMono, fontSize = 11.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Full-width row used for Exit (and Home when the tile count is odd): symbol left, label and sub stacked beside it. */
@Composable
private fun WideTile(tile: Tile, focused: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.pressFeedback(onClick = onClick)
            .fillMaxWidth()
            .clip(SharpShape)
            .background(if (focused) Ktm.SurfaceAlt else Ktm.Surface)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) tile.tint else tile.tint.copy(alpha = 0.45f),
                SharpShape,
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(tile.icon, contentDescription = tile.label, tint = tile.tint, modifier = Modifier.size(22.dp))
        Column {
            Text(tile.label, color = tile.tint, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                fontSize = 18.sp, letterSpacing = 0.2.sp)
            Text(tile.sub, color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}
