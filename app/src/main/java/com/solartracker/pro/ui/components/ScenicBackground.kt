package com.solartracker.pro.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solartracker.pro.R
import com.solartracker.pro.core.scenery.DayPhase
import com.solartracker.pro.core.scenery.SceneTheme
import com.solartracker.pro.core.scenery.ScenicPhoto
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.data.SceneryRepository
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Which photos a screen shows: its theme, its position among screens of that theme (so they differ) and the
 * built-in illustrations used offline or with photos turned off.
 */
enum class SceneSlot(val theme: SceneTheme, val slot: Int, val fallbacks: List<SunnyScene>) {
    DASHBOARD(SceneTheme.COAST, 0, listOf(SunnyScene.SANTORINI, SunnyScene.ALGARVE)),
    LIVE(SceneTheme.SOLAR, 0, listOf(SunnyScene.ATACAMA, SunnyScene.PROVENCE)),
    ANGLES(SceneTheme.VILLAGES, 0, listOf(SunnyScene.DOLOMITES, SunnyScene.TUSCANY)),
    MONTHLY(SceneTheme.VILLAGES, 1, listOf(SunnyScene.PROVENCE, SunnyScene.TUSCANY)),
    ENERGY(SceneTheme.ISLANDS, 0, listOf(SunnyScene.TUSCANY, SunnyScene.ALGARVE)),
    CENTER(SceneTheme.SOLAR, 1, listOf(SunnyScene.ALGARVE, SunnyScene.ATACAMA)),
    RADAR(SceneTheme.COAST, 1, listOf(SunnyScene.ALGARVE, SunnyScene.SANTORINI)),
    TOOLS(SceneTheme.DESERT, 0, listOf(SunnyScene.ATACAMA, SunnyScene.DOLOMITES)),
    SETTINGS(SceneTheme.ISLANDS, 1, listOf(SunnyScene.SANTORINI, SunnyScene.PROVENCE)),
}

/** What the backdrop shows now. Equality ignores the bitmap object, so the same photo never cross-fades into itself. */
@Stable
class SceneryUi(val photo: ScenicPhoto?, val image: ImageBitmap?, val fallback: SunnyScene, val phase: DayPhase) {
    private val identity get() = (photo?.title ?: "") + "|" + fallback.name + "|" + (image != null)
    override fun equals(other: Any?) = other is SceneryUi && other.identity == identity
    override fun hashCode() = identity.hashCode()
}

val LocalScenery = staticCompositionLocalOf { SceneryUi(null, null, SunnyScene.SANTORINI, DayPhase.DAY) }

/** True when the system "remove animations" setting is on; decorative motion is then skipped. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/**
 * The picture for [slot]: from memory at once when it was shown before, otherwise loaded in the background while
 * the previous picture stays on screen (no flicker). The time of day is re-checked every few minutes, never the photo.
 */
@Composable
fun rememberScenery(slot: SceneSlot, location: GeoLocation?): SceneryUi {
    val context = LocalContext.current
    val repo = remember { SceneryRepository.get(context) }
    val seed by repo.seed.collectAsState()
    val enabled by repo.enabled.collectAsState()
    val phase by produceState(phaseNow(location), location) {
        while (true) {
            value = phaseNow(location)
            delay(PHASE_CHECK_MS)
        }
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val widthPx = (configuration.screenWidthDp * density).toInt().coerceAtLeast(320)
    val heightPx = (configuration.screenHeightDp * density).toInt().coerceAtLeast(320)
    val fallback = slot.fallbacks[Math.floorMod(seed, slot.fallbacks.size.toLong()).toInt()]
    var ui by remember { mutableStateOf(SceneryUi(null, null, fallback, phase)) }
    LaunchedEffect(slot, seed, enabled, phase) {
        if (!enabled) {
            ui = SceneryUi(null, null, fallback, phase)
            return@LaunchedEffect
        }
        repo.cached(slot.name, phase)?.let {
            ui = SceneryUi(it.photo, it.bitmap.asImageBitmap(), fallback, phase)
            return@LaunchedEffect
        }
        val loaded = repo.load(slot.name, slot.theme, slot.slot, phase, widthPx, heightPx)
        ui = when {
            loaded != null -> SceneryUi(loaded.photo, loaded.bitmap.asImageBitmap(), fallback, phase)
            // Nothing new (offline): keep a photo already on screen rather than swapping to the illustration.
            ui.image != null -> ui
            else -> SceneryUi(null, null, fallback, phase)
        }
    }
    return ui
}

private fun phaseNow(location: GeoLocation?): DayPhase =
    location?.let { runCatching { DayPhase.at(it, Instant.now()) }.getOrNull() } ?: DayPhase.DAY

private const val PHASE_CHECK_MS = 5 * 60_000L

/** Veil over the photo: with it the regular text colours keep ≥ 4.5:1 even on a pure black or white picture. */
private const val VEIL_ALPHA = 0.74f

/**
 * The photo (or illustration) cropped to fill its box. With motion allowed the photo slowly zooms in once after it
 * appears (24 s, then still) – a gentle "Ken Burns" effect that does not keep the screen redrawing.
 */
@Composable
fun ScenicPicture(scenery: SceneryUi, modifier: Modifier = Modifier, zoom: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val scale = remember(scenery) { Animatable(1f) }
    if (zoom && !reduced) {
        LaunchedEffect(scenery) { scale.animateTo(1.07f, tween(24_000, easing = LinearOutSlowInEasing)) }
    }
    val image = scenery.image
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Medium,
            modifier = modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
    } else {
        Canvas(modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
            // The illustrations are drawn for a wide banner; on a tall box draw them in a top band and continue below.
            val band = minOf(size.height, size.width * 0.62f)
            drawRect(Brush.verticalGradient(listOf(Color(0xFF29486B), Color(0xFF0F1B2D)), startY = band * 0.8f, endY = size.height))
            clipRect(bottom = band) { inset(0f, 0f, 0f, size.height - band) { drawSunnyScene(scenery.fallback) } }
        }
    }
}

/**
 * Full-screen backdrop behind every tab: the picture with a theme-coloured veil, strong enough for body text outside
 * cards to stay readable on any photo (≥ 4.5:1 for the regular text colours, also on a black or white picture).
 */
@Composable
fun ScenicBackdrop(scenery: SceneryUi, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val background = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize().background(background).testTag("scenic_backdrop")) {
        if (reduced) {
            ScenicPicture(scenery, Modifier.fillMaxSize())
        } else {
            Crossfade(targetState = scenery, animationSpec = tween(700), label = "scenery") { s ->
                ScenicPicture(s, Modifier.fillMaxSize(), zoom = true)
            }
        }
        Box(Modifier.fillMaxSize().background(background.copy(alpha = VEIL_ALPHA)))
        // A touch of warm light in the top corner by day; nothing at night.
        if (scenery.phase != DayPhase.NIGHT) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width * 0.92f, 0f)
                drawCircle(Brush.radialGradient(listOf(Color(0x33FFC857), Color(0x00FFC857)), c, size.width * 0.75f), size.width * 0.75f, c)
            }
        }
    }
}

/**
 * Slim bar above every tab: app name, the photo credit (author · license · Wikimedia Commons, tap = file page with
 * the full license), the data-update status and the "change scenery" button.
 */
@Composable
fun SceneryBar(scenery: SceneryUi, updating: Boolean, updateText: String?, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val photo = scenery.photo
    val credit = photo?.let { stringResource(R.string.scenery_credit, it.author, it.license) } ?: stringResource(scenery.fallback.caption)
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.WbSunny, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text("Solar Tracker PRO", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            val creditDescription = photo?.let { stringResource(R.string.scenery_credit_desc, it.author, it.license) }
            Text(
                credit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("scenery_credit").let { m ->
                    if (photo == null) m else m.clickable(role = Role.Button) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.semantics { contentDescription = creditDescription.orEmpty() }
                },
            )
        }
        if (updating || updateText != null) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (updating) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Text(if (updating) stringResource(R.string.scenery_updating) else updateText.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        val shuffleLabel = stringResource(R.string.scenery_change)
        IconButton(onClick = onShuffle, modifier = Modifier.testTag("scenery_shuffle")) {
            Icon(Icons.Outlined.Landscape, contentDescription = shuffleLabel)
        }
    }
}

/** Cards slide in and fade up once when a screen opens (skipped with reduced motion). */
@Composable
fun Modifier.gentleEnter(): Modifier {
    if (LocalReducedMotion.current) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(360, easing = FastOutSlowInEasing)) }
    return graphicsLayer {
        alpha = 0.35f + 0.65f * progress.value
        translationY = (1f - progress.value) * 14.dp.toPx()
    }
}

/** Charts draw from left to right once when they first appear (skipped with reduced motion). */
@Composable
fun Modifier.revealOnAppear(): Modifier {
    if (LocalReducedMotion.current) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    return drawWithContent { clipRect(right = size.width * progress.value) { this@drawWithContent.drawContent() } }
}

/** Translucent card colour on top of the backdrop. */
@Composable
fun glassColor(): Color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.86f)

/** A clickable-free picture window used by headers: the current scenery with a dark veil for white text. */
@Composable
fun SceneryWindow(modifier: Modifier = Modifier, veil: Float = 0.45f, content: @Composable () -> Unit) {
    val scenery = LocalScenery.current
    Box(modifier.clip(RoundedCornerShape(22.dp))) {
        ScenicPicture(scenery, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = veil * 0.6f), 1f to Color.Black.copy(alpha = (veil + 0.25f).coerceAtMost(0.85f)))))
        content()
    }
}
