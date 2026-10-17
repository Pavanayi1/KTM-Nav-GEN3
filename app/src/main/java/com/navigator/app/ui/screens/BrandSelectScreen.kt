package com.navigator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.Brand
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.OpenDashIcons
import com.navigator.app.ui.theme.themeFor

/**
 * "Switchable bike" — the rider picks their brand and the whole app re-themes
 * live as they tap between chips (KTM dark/orange ⇄ Husqvarna light/blue). This
 * is the one screen the KTM→multi-brand merge introduces. Reached on first run
 * (before pairing) and from Settings → Change bike / brand.
 *
 * Selecting a chip applies the theme immediately (so the preview is truthful);
 * [onPair] persists it and moves on. [onBack] (non-null only when reached from
 * Settings) returns without forcing a re-pair.
 */
@Composable
fun BrandSelectScreen(
    onPair: (Brand) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    // Reflect the live theme so the preview matches; seeded from the saved brand.
    var selected by remember { mutableStateOf(Ktm.current.brand) }
    val theme = Ktm.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ktm.Screen)
            .systemBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 26.dp, bottom = 26.dp),
    ) {
        // Top accent glow.
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Ktm.Orange.copy(alpha = 0.20f),
                            1f to Ktm.Screen.copy(alpha = 0f),
                        )
                    )
            )
            Column {
                if (onBack != null) {
                    Icon(
                        OpenDashIcons.ChevronLeft, contentDescription = "Back", tint = Ktm.Muted,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onBack)
                            .padding(6.dp)
                            .size(24.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text("NAVIGATOR GEN3", color = Ktm.Muted, fontFamily = JetBrainsMono,
                    fontSize = 11.sp, letterSpacing = 3.5.sp)
                Text("Choose your ride", color = Ktm.Dim, fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }

        // Center: wordmark + tagline (bike hero image slot omitted — no asset yet).
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 230.dp, height = 140.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ktm.ScreenDeep)
                    .border(1.dp, Ktm.Orange.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(OpenDashIcons.Bike, contentDescription = null, tint = Ktm.Orange,
                    modifier = Modifier.size(64.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text(
                theme.wordmark,
                color = Ktm.White,
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.ExtraBold,
                fontStyle = if (theme.wordmarkItalic) FontStyle.Italic else FontStyle.Normal,
                fontSize = 52.sp,
                letterSpacing = theme.wordmarkTracking,
            )
            Text(
                theme.tagline,
                color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, letterSpacing = 4.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // Brand chips — tapping re-themes live.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Brand.entries.forEach { b ->
                BrandChip(
                    brand = b,
                    active = b == selected,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selected = b
                        Ktm.applyBrand(b) // live preview
                    },
                )
            }
        }

        Text(
            "Also changes the app name and icon on your home screen.",
            color = Ktm.Muted, fontFamily = Barlow, fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )

        // Confirm the brand; returning users do not need to pair again.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ktm.Orange)
                .clickable { onPair(selected) },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (onBack != null) "USE ${theme.displayName.uppercase()}" else "PAIR YOUR ${theme.displayName.uppercase()}",
                    color = Ktm.OnAccent, fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold,
                    fontSize = 19.sp, letterSpacing = 1.2.sp,
                )
                Spacer(Modifier.size(10.dp))
                Icon(OpenDashIcons.ChevronRight, contentDescription = null, tint = Ktm.OnAccent,
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun BrandChip(brand: Brand, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // The chip's dot always shows THAT brand's accent, even when inactive, so the
    // rider can see each brand's identity before selecting.
    val chipAccent = themeFor(brand).accent
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (active) Ktm.SurfaceAlt else Ktm.Surface)
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = if (active) Ktm.Orange else Ktm.Border,
                shape = RoundedCornerShape(13.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (active) chipAccent else Ktm.Surface)
                .border(if (active) 0.dp else 1.5.dp, chipAccent, CircleShape),
        )
        Text(
            themeFor(brand).displayName,
            color = if (active) Ktm.White else Ktm.Muted,
            fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        )
    }
}
