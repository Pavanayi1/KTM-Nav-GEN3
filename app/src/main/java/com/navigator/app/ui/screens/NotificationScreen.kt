package com.navigator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.gemini.GeminiSummarizer
import com.navigator.app.notifications.NotificationEntry
import com.navigator.app.notifications.NotificationRepository
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.components.KtmToggle
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm

/**
 * Notifications feed (screen 04). Pure-black high-contrast surface for
 * sunlight legibility, most-recent-first cards (newest carries the orange
 * left accent), an optional Gemini summary note, a quick-mute "Mirror"
 * toggle, and handlebar Up/Down scrolling driven by [scrollIndex].
 */
@Composable
fun NotificationScreen(settings: AppSettings, scrollIndex: Int = 0) {
    val entries by NotificationRepository.entries.collectAsState()
    var summaries by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var mirrorEnabled by remember { mutableStateOf(settings.mirrorEnabled) }

    val geminiOn = !settings.geminiApiKey.isNullOrBlank()

    LaunchedEffect(entries) {
        loading = true
        val apiKey = settings.geminiApiKey
        summaries = if (apiKey.isNullOrBlank()) {
            entries.map { e -> if (e.title.isNotBlank()) e.title else e.text }
        } else {
            GeminiSummarizer(apiKey, settings.geminiModel).summarize(entries)
        }
        loading = false
    }

    val listState = rememberLazyListState()
    LaunchedEffect(scrollIndex, entries.size) {
        if (scrollIndex in entries.indices) listState.animateScrollToItem(scrollIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ktm.Black)
            .systemBarsPadding()
            .padding(top = 4.dp),
    ) {
        // Header: eyebrow + live state, matching the Direction screen so the two
        // on-ride surfaces read as siblings rather than two different apps.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            com.navigator.app.ui.components.Eyebrow(
                "Mirroring", color = Ktm.Dim, fontSize = 11, letterSpacing = 2.4,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (mirrorEnabled) Ktm.Green else Ktm.Dim3),
                )
                Text(
                    if (mirrorEnabled) "LIVE" else "MUTED",
                    color = Ktm.Muted, fontFamily = com.navigator.app.ui.theme.JetBrainsMono,
                    fontSize = 11.sp, modifier = Modifier.padding(start = 7.dp),
                )
            }
        }
        Text(
            "FEED",
            color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic, fontSize = 30.sp, letterSpacing = (-0.3).sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && entries.isEmpty() -> {
                    CircularProgressIndicator(
                        color = Ktm.Orange, modifier = Modifier.align(Alignment.Center),
                    )
                }
                entries.isEmpty() -> {
                    Text(
                        "No notifications yet", color = Ktm.Muted2, fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.SemiBold, fontSize = 22.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp, bottom = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(entries) { index, entry ->
                            val body = summaries.getOrNull(index)
                                ?: entry.title.ifBlank { entry.text }
                            NotificationCard(
                                entry = entry,
                                body = body,
                                priority = index == 0,
                                geminiOn = geminiOn && index == 0,
                                dimmed = index >= 3,
                            )
                        }
                    }
                }
            }
        }

        Text(
            "RCM ▲▼ to scroll · high-contrast mode",
            color = Ktm.Dim3, fontFamily = JetBrainsMono, fontSize = 10.5.sp, letterSpacing = 0.5.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Ktm.Black)
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 12.dp),
        )
    }
}

private fun relativeTime(postTimeMs: Long): String {
    val diff = System.currentTimeMillis() - postTimeMs
    return when {
        diff < 60_000 -> "now"
        diff < 3_600_000 -> "${diff / 60_000}m"
        diff < 86_400_000 -> "${diff / 3_600_000}h"
        else -> "${diff / 86_400_000}d"
    }
}

@Composable
private fun NotificationCard(
    entry: NotificationEntry,
    body: String,
    priority: Boolean,
    geminiOn: Boolean,
    dimmed: Boolean,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (dimmed) 0.72f else 1f }
            .clip(shape)
            .background(Ktm.NotifCard)
            .border(1.dp, Ktm.NotifBorder, shape)
            .then(
                if (priority) Modifier.drawLeftAccent() else Modifier,
            )
            .padding(horizontal = 15.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                entry.appLabel.uppercase(),
                color = if (dimmed) Ktm.TextSecondary else Ktm.White,
                fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontSize = 16.sp, letterSpacing = 0.8.sp,
            )
            Text(relativeTime(entry.postTimeMs), color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 11.sp)
        }
        Text(
            body,
            color = if (dimmed) Color2 else Ktm.TextPrimary,
            fontFamily = Barlow, fontSize = 15.sp, lineHeight = 21.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
        if (geminiOn) {
            Text(
                "✦ Gemini summary on",
                color = Ktm.Dim2, fontFamily = Barlow, fontStyle = FontStyle.Italic, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private val Color2 = androidx.compose.ui.graphics.Color(0xFFB7B9BD)

/** 3px orange left accent for the priority (newest) card. */
private fun Modifier.drawLeftAccent(): Modifier = this.drawBehind {
    drawRect(
        color = Ktm.Orange,
        size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
    )
}
