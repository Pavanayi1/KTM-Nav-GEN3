package com.navigator.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.audio.NowPlayingRepository
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons
import kotlinx.coroutines.delay

/**
 * Now-playing screen, laid out to match the physical handlebar remote 1:1:
 * UP = next track, DOWN = previous, SET = play/pause, BACK = leave. The
 * on-screen control stack mirrors that top-to-bottom order and shares the
 * exact same action callbacks the remote drives, so tapping here and
 * pressing the handlebar buttons feel identical.
 */
@Composable
fun MusicPlayerScreen(
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val np by NowPlayingRepository.state.collectAsState()

    // Smoothly extrapolate the progress bar between metadata/playback
    // events instead of waiting for the next callback to move it.
    var displayPositionMs by remember { mutableLongStateOf(np?.positionMs ?: 0L) }
    LaunchedEffect(np) {
        val n = np ?: return@LaunchedEffect
        while (true) {
            val elapsed = if (n.isPlaying) {
                ((android.os.SystemClock.elapsedRealtime() - n.positionUpdateRealtimeMs) * n.playbackSpeed).toLong()
            } else 0L
            displayPositionMs = (n.positionMs + elapsed).coerceIn(0, n.durationMs.coerceAtLeast(0))
            if (!n.isPlaying) break
            delay(250)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Ktm.Screen).systemBarsPadding().padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Eyebrow("Now playing", color = Ktm.Dim, fontSize = 11, letterSpacing = 2.4)
            Text(
                np?.appLabel ?: "", color = Ktm.Muted, fontFamily = JetBrainsMono, fontSize = 11.sp,
            )
        }

        if (np == null) {
            EmptyState()
            return@Column
        }
        val n = np!!

        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(8.dp))
            AlbumArt(n)
            Spacer(Modifier.height(20.dp))
            Text(
                n.title, color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic, fontSize = 32.sp, lineHeight = 34.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
            )
            if (n.artist.isNotBlank()) {
                Text(
                    n.artist, color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            ProgressBar(displayPositionMs, n.durationMs)
            Spacer(Modifier.height(16.dp))
            Eyebrow("Handlebar mirror · touch or press", color = Ktm.Dim, fontSize = 10, letterSpacing = 2.2)
            Spacer(Modifier.height(8.dp))
            RemoteMirrorRows(
                isPlaying = n.isPlaying,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onBack = onBack,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The four handlebar buttons as rows, in the physical order they sit on the
 * bar: UP, DOWN, SET, BACK. Each row is both the label for what that button
 * does and a touch target that fires the identical action - so learning the
 * screen teaches the remote, and vice versa. SET is filled because play/pause
 * is the one you reach for most.
 */
@Composable
private fun RemoteMirrorRows(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MirrorRow("UP", "Next track", onClick = onNext)
        MirrorRow("DOWN", "Previous", onClick = onPrevious)
        MirrorRow("SET", if (isPlaying) "Pause" else "Play", primary = true, onClick = onPlayPause)
        MirrorRow("BACK", "Leave", onClick = onBack)
    }
}

@Composable
private fun MirrorRow(button: String, action: String, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusRow))
            .background(if (primary) Ktm.Orange else Ktm.Surface)
            .border(
                1.dp,
                if (primary) Ktm.Orange else Ktm.Border,
                RoundedCornerShape(Ktm.RadiusRow),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            button,
            color = if (primary) Ktm.OnAccent else Ktm.Orange,
            fontFamily = JetBrainsMono, fontSize = 10.sp,
            modifier = Modifier.width(46.dp),
        )
        Text(
            action.uppercase(),
            color = if (primary) Ktm.OnAccent else Ktm.White,
            fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
            fontSize = 18.sp, letterSpacing = 0.3.sp,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(Ktm.Surface).border(1.dp, Ktm.Border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NavIcons.Music, contentDescription = null, tint = Ktm.Dim, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Nothing playing", color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 15.sp)
        Text(
            "Start a track in Spotify, YouTube Music, or any player.",
            color = Ktm.Dim, fontFamily = Barlow, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AlbumArt(n: NowPlayingRepository.NowPlaying) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(
                Brush.linearGradient(listOf(Ktm.Surface, Ktm.ScreenDeep)),
            )
            .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = n.albumArt
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(Ktm.RadiusCard)),
            )
        } else {
            Icon(NavIcons.Music, contentDescription = null, tint = Ktm.Dim2, modifier = Modifier.size(56.dp))
        }
    }
}

@Composable
private fun ProgressBar(positionMs: Long, durationMs: Long) {
    val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ktm.SurfaceDisabled),
        ) {
            Box(
                Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ktm.Orange),
            )
        }
        if (durationMs > 0) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(positionMs), color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 11.sp)
                // Remaining, not total: "how long until the next track" is the
                // question a rider actually has.
                Text("-" + formatMs((durationMs - positionMs).coerceAtLeast(0)),
                    color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 10.sp)
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
