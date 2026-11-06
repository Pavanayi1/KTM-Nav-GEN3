package com.navigator.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.navigator.app.R
import com.navigator.app.ui.theme.Brand

/**
 * The brand's white line-art bike (res/drawable-nodpi/hero_*.webp, generated
 * art cropped to the bike) and the card colour it sits on. The colour is
 * sampled from each image's own background so the art's edges vanish into the
 * card - keep the two in step if the images are ever regenerated.
 */
data class BikeArt(val drawable: Int, val cardColor: Color)

/**
 * Which KTM the rider has. Only changes the artwork and home headline: the
 * dash protocol is the same Gen-3 one for both. Husqvarna riders keep their
 * brand's single artwork whatever this says.
 */
enum class BikeModel(val id: String, val label: String) {
    ADVENTURE("adventure", "Adventure"),
    DUKE("duke", "Duke");

    companion object {
        /** Unknown or unset reads as Adventure: the art every rider had before this choice existed. */
        fun fromId(id: String?): BikeModel = entries.firstOrNull { it.id == id } ?: ADVENTURE
    }
}

/**
 * Decoded bitmaps, shared app-wide. `painterResource` decodes at every new
 * composition site - the brand picker's crossfade and each return to the home
 * screen paid a fresh WebP decode on the main thread (a dropped frame or two
 * on a slow phone). Decoding once and reusing the ImageBitmap removes that.
 */
object BikeBitmaps {
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, androidx.compose.ui.graphics.ImageBitmap>()

    fun get(context: android.content.Context, id: Int): androidx.compose.ui.graphics.ImageBitmap =
        cache.getOrPut(id) {
            android.graphics.BitmapFactory.decodeResource(context.resources, id)
                .asImageBitmap()
                .also { it.prepareToDraw() }
        }

    /** Warm the cache off the main thread at startup, so the first screen shows art immediately. */
    fun preload(context: android.content.Context) {
        val app = context.applicationContext
        Thread({
            listOf(R.drawable.hero_ktm, R.drawable.hero_ktm_duke, R.drawable.hero_husqvarna, R.drawable.bike_ktm, R.drawable.bike_husqvarna)
                .forEach { runCatching { get(app, it) } }
        }, "bike-art-preload").start()
    }
}

fun bikeArtFor(brand: Brand, model: BikeModel = BikeModel.ADVENTURE): BikeArt = when (brand) {
    Brand.HUSQVARNA -> BikeArt(R.drawable.hero_husqvarna, Color(0xFF0A162A))
    // The Duke art has a transparent background, so it sits on the same card.
    Brand.KTM -> if (model == BikeModel.DUKE) BikeArt(R.drawable.hero_ktm_duke, Color(0xFF060709))
        else BikeArt(R.drawable.hero_ktm, Color(0xFF060709))
}

/**
 * A self-contained dark card with the brand's bike centred in it. Dark in
 * both themes on purpose: the art is white-on-dark, and a dark feature card
 * reads as intentional on the light Husqvarna screens too. Crossfades when
 * [brand] changes, so the brand picker's live preview animates.
 */
@Composable
fun BikeHeroCard(
    brand: Brand,
    modifier: Modifier = Modifier,
    model: BikeModel = BikeModel.ADVENTURE,
    corner: Dp = 20.dp,
    artAlignment: Alignment = Alignment.Center,
    artPadding: Dp = 14.dp,
    content: @Composable () -> Unit = {},
) {
    Crossfade(targetState = brand to model, animationSpec = tween(350), modifier = modifier, label = "bikeHero") { (b, m) ->
        val art = bikeArtFor(b, m)
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(corner))
                .background(art.cardColor),
        ) {
            Image(
                bitmap = BikeBitmaps.get(androidx.compose.ui.platform.LocalContext.current, art.drawable),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(artPadding),
                contentScale = ContentScale.Fit,
                alignment = artAlignment,
            )
            content()
        }
    }
}
