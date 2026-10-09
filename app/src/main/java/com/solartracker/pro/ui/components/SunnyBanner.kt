package com.solartracker.pro.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solartracker.pro.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sunny places drawn as vector illustrations (no photos: nothing to download, license or cache; sharp on every screen
 * and tiny in the APK). Purely decorative – they carry no data.
 */
enum class SunnyScene(@StringRes val caption: Int) {
    DOLOMITES(R.string.scene_dolomites),
    PROVENCE(R.string.scene_provence),
    TUSCANY(R.string.scene_tuscany),
    ATACAMA(R.string.scene_atacama),
    SANTORINI(R.string.scene_santorini),
    ALGARVE(R.string.scene_algarve),
}

/** Screen header on an illustration of a sunny place; the title stays real text (readable, findable by tests). */
@Composable
fun SunnyBanner(scene: SunnyScene, title: String, subtitle: String?, modifier: Modifier = Modifier) {
    val caption = stringResource(scene.caption)
    val shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 6f)
    Box(modifier.fillMaxWidth().heightIn(min = 136.dp).clip(RoundedCornerShape(22.dp))) {
        Canvas(Modifier.matchParentSize().semantics { contentDescription = caption }) {
            drawScene(scene)
            // Scrim at the bottom so white text is readable on any scene.
            drawRect(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)))
        }
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.28f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, top = 52.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall.merge(TextStyle(shadow = shadow)), fontWeight = FontWeight.Bold, color = Color.White)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium.merge(TextStyle(shadow = shadow)), color = Color.White.copy(alpha = 0.92f), lineHeight = 18.sp)
            }
        }
    }
}

private fun DrawScope.drawScene(scene: SunnyScene) {
    when (scene) {
        SunnyScene.DOLOMITES -> dolomites()
        SunnyScene.PROVENCE -> provence()
        SunnyScene.TUSCANY -> tuscany()
        SunnyScene.ATACAMA -> atacama()
        SunnyScene.SANTORINI -> santorini()
        SunnyScene.ALGARVE -> algarve()
    }
}

// ---- building blocks -------------------------------------------------------------------------------------------

private fun DrawScope.sky(top: Long, bottom: Long) =
    drawRect(Brush.verticalGradient(listOf(Color(top), Color(bottom))))

/** Sun with a soft glow and short rays; [fx]/[fy] are fractions of the banner, [r] of its height. */
private fun DrawScope.sun(fx: Float, fy: Float, r: Float = 0.13f, rays: Boolean = true) {
    val c = Offset(size.width * fx, size.height * fy)
    val radius = size.height * r
    drawCircle(Brush.radialGradient(listOf(Color(0xCCFFF6C8), Color(0x00FFF6C8)), c, radius * 4.2f), radius * 4.2f, c)
    if (rays) {
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 + 8.0)
            val from = Offset(c.x + cos(a).toFloat() * radius * 1.35f, c.y + sin(a).toFloat() * radius * 1.35f)
            val to = Offset(c.x + cos(a).toFloat() * radius * 1.85f, c.y + sin(a).toFloat() * radius * 1.85f)
            drawLine(Color(0x99FFF3B0), from, to, strokeWidth = radius * 0.16f, cap = StrokeCap.Round)
        }
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFFFF2), Color(0xFFFFE27A), Color(0xFFFFC23D)), c, radius), radius, c)
}

/** Ridge through evenly spaced points (y as fractions of the height), filled down to the bottom edge. */
private fun DrawScope.ridge(ys: List<Float>, brush: Brush, smooth: Boolean = true) {
    val w = size.width
    val h = size.height
    val step = w / (ys.size - 1)
    val p = Path().apply {
        moveTo(0f, h)
        lineTo(0f, ys[0] * h)
        if (smooth) {
            for (i in 1 until ys.size) {
                val x0 = (i - 1) * step
                val x1 = i * step
                val midX = (x0 + x1) / 2
                cubicTo(midX, ys[i - 1] * h, midX, ys[i] * h, x1, ys[i] * h)
            }
        } else {
            ys.forEachIndexed { i, y -> lineTo(i * step, y * h) }
        }
        lineTo(w, h)
        close()
    }
    drawPath(p, brush)
}

private fun DrawScope.ridge(ys: List<Float>, color: Long, smooth: Boolean = true) = ridge(ys, Brush.verticalGradient(listOf(Color(color), Color(color))), smooth)

private fun DrawScope.birds(vararg at: Pair<Float, Float>) {
    at.forEach { (fx, fy) ->
        val c = Offset(size.width * fx, size.height * fy)
        val s = size.height * 0.035f
        val p = Path().apply {
            moveTo(c.x - s * 1.6f, c.y)
            quadraticTo(c.x - s * 0.8f, c.y - s, c.x, c.y)
            quadraticTo(c.x + s * 0.8f, c.y - s, c.x + s * 1.6f, c.y)
        }
        drawPath(p, Color(0xAA2E3A46), style = Stroke(width = s * 0.35f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.sea(fromY: Float, toY: Float, top: Long, bottom: Long) {
    drawRect(Brush.verticalGradient(listOf(Color(top), Color(bottom)), startY = size.height * fromY, endY = size.height * toY),
        topLeft = Offset(0f, size.height * fromY), size = Size(size.width, size.height * (toY - fromY)))
}

/** Sun glitter on water. */
private fun DrawScope.glitter(fx: Float, fromY: Float, rows: Int) {
    for (i in 0 until rows) {
        val y = size.height * (fromY + i * 0.045f)
        val half = size.width * (0.025f + i * 0.012f)
        val x = size.width * fx + (if (i % 2 == 0) -1 else 1) * size.width * 0.01f
        drawLine(Color(0xCCFFFFFF), Offset(x - half, y), Offset(x + half, y), strokeWidth = size.height * 0.012f, cap = StrokeCap.Round)
    }
}

// ---- scenes ----------------------------------------------------------------------------------------------------

/** Dolomites in the morning: jagged limestone peaks with snow, a green alpine meadow. */
private fun DrawScope.dolomites() {
    sky(0xFF4E9DE0, 0xFFFFDDA8)
    sun(0.80f, 0.30f)
    ridge(listOf(0.62f, 0.36f, 0.50f, 0.20f, 0.42f, 0.30f, 0.48f, 0.26f, 0.56f), 0xFFB7C6DA, smooth = false)
    // Snow caps on the far peaks.
    listOf(0.375f to 0.20f, 0.75f to 0.26f, 0.125f to 0.36f).forEach { (fx, fy) ->
        val x = size.width * fx
        val y = size.height * fy
        val s = size.height * 0.07f
        drawPath(Path().apply { moveTo(x, y); lineTo(x - s * 0.9f, y + s); lineTo(x + s * 0.9f, y + s); close() }, Color(0xF2FFFFFF))
    }
    ridge(listOf(0.74f, 0.56f, 0.66f, 0.48f, 0.62f, 0.54f, 0.70f), 0xFF6F8BA8, smooth = false)
    ridge(listOf(0.86f, 0.80f, 0.84f, 0.77f, 0.83f), Brush.verticalGradient(listOf(Color(0xFF8BD05A), Color(0xFF4E9A35))))
    birds(0.30f to 0.22f, 0.36f to 0.17f)
}

/** Lavender fields of Provence: purple rows running to the horizon, a lone tree. */
private fun DrawScope.provence() {
    sky(0xFF73C2F0, 0xFFFFE6B8)
    sun(0.20f, 0.27f)
    ridge(listOf(0.60f, 0.54f, 0.58f, 0.52f, 0.57f), 0xFFA4BCA3)
    val horizon = 0.64f
    drawRect(Brush.verticalGradient(listOf(Color(0xFFA889DB), Color(0xFF7550B8)), startY = size.height * horizon, endY = size.height),
        topLeft = Offset(0f, size.height * horizon), size = Size(size.width, size.height * (1 - horizon)))
    val vanish = Offset(size.width * 0.55f, size.height * horizon)
    for (i in -10..10) {
        val bottomX = size.width * (0.55f + i * 0.09f)
        drawLine(Color(0xFF5B3A9A), vanish, Offset(bottomX, size.height), strokeWidth = size.height * 0.022f, cap = StrokeCap.Round)
    }
    // Tree on the horizon.
    val tx = size.width * 0.82f
    val ty = size.height * horizon
    drawLine(Color(0xFF4A3A2A), Offset(tx, ty), Offset(tx, ty - size.height * 0.08f), strokeWidth = size.height * 0.02f)
    drawCircle(Color(0xFF3F6B35), size.height * 0.07f, Offset(tx, ty - size.height * 0.12f))
    drawCircle(Color(0xFF4F8040), size.height * 0.05f, Offset(tx - size.height * 0.04f, ty - size.height * 0.09f))
}

/** Tuscany at golden hour: rolling hills and cypress trees. */
private fun DrawScope.tuscany() {
    sky(0xFFFFA95E, 0xFFFFEBC9)
    sun(0.70f, 0.46f, r = 0.16f, rays = false)
    ridge(listOf(0.60f, 0.50f, 0.58f, 0.48f, 0.60f), 0xFFD8BE7A)
    ridge(listOf(0.74f, 0.64f, 0.70f, 0.62f, 0.72f), 0xFFA9A85C)
    ridge(listOf(0.88f, 0.78f, 0.84f, 0.80f, 0.86f), Brush.verticalGradient(listOf(Color(0xFF7E9A43), Color(0xFF5C7A2F))))
    // Cypresses along the middle hill.
    listOf(0.16f to 0.66f, 0.21f to 0.645f, 0.26f to 0.635f, 0.58f to 0.665f, 0.62f to 0.66f).forEach { (fx, fy) ->
        val x = size.width * fx
        val base = size.height * fy
        val hgt = size.height * 0.22f
        val wid = size.height * 0.05f
        drawOval(Color(0xFF2F4A28), topLeft = Offset(x - wid / 2, base - hgt), size = Size(wid, hgt))
    }
    birds(0.40f to 0.24f, 0.45f to 0.20f, 0.49f to 0.26f)
}

/** Atacama desert – one of the sunniest places on Earth – with a row of solar panels on the dunes. */
private fun DrawScope.atacama() {
    sky(0xFF2B7FD8, 0xFFF8D2A0)
    sun(0.50f, 0.22f, r = 0.12f)
    ridge(listOf(0.66f, 0.58f, 0.64f, 0.56f, 0.62f), 0xFFC98F63)
    ridge(listOf(0.78f, 0.70f, 0.76f, 0.68f, 0.74f), 0xFFE6B07A)
    ridge(listOf(0.92f, 0.86f, 0.90f, 0.84f, 0.90f), Brush.verticalGradient(listOf(Color(0xFFF0C48C), Color(0xFFD9975A))))
    // Solar panels: tilted dark-blue rectangles with cell lines, on short legs.
    val h = size.height
    val panelW = h * 0.30f
    val panelH = h * 0.10f
    var x = size.width * 0.04f
    while (x < size.width - panelW * 0.5f) {
        val top = h * 0.70f
        val p = Path().apply {
            moveTo(x, top + panelH)
            lineTo(x + panelW * 0.12f, top)
            lineTo(x + panelW * 1.12f, top)
            lineTo(x + panelW, top + panelH)
            close()
        }
        drawLine(Color(0xFF6B6F78), Offset(x + panelW * 0.5f, top + panelH), Offset(x + panelW * 0.5f, top + panelH * 1.6f), strokeWidth = h * 0.012f)
        drawPath(p, Brush.verticalGradient(listOf(Color(0xFF3D6CC0), Color(0xFF1C3770)), startY = top, endY = top + panelH))
        for (k in 1..3) {
            val fx = x + panelW * k / 4f
            drawLine(Color(0x66BFD8FF), Offset(fx + panelW * 0.12f, top), Offset(fx, top + panelH), strokeWidth = h * 0.005f)
        }
        drawLine(Color(0x99FFFFFF), Offset(x + panelW * 0.14f, top + h * 0.006f), Offset(x + panelW * 0.6f, top + h * 0.006f), strokeWidth = h * 0.006f)
        x += panelW * 1.35f
    }
}

/** Santorini: white houses with blue domes on the caldera cliff above the Aegean. */
private fun DrawScope.santorini() {
    sky(0xFF3A9EF2, 0xFFC6EAFF)
    sun(0.84f, 0.24f)
    sea(0.58f, 1f, 0xFF2C8AD6, 0xFF114E92)
    glitter(0.84f, 0.64f, 6)
    val w = size.width
    val h = size.height
    // Cliff.
    drawPath(Path().apply {
        moveTo(0f, h * 0.48f)
        lineTo(w * 0.30f, h * 0.50f)
        cubicTo(w * 0.42f, h * 0.52f, w * 0.48f, h * 0.70f, w * 0.56f, h)
        lineTo(0f, h)
        close()
    }, Brush.verticalGradient(listOf(Color(0xFFC9A57E), Color(0xFF8E6A4A))))
    // Houses (fractions: x, y-top, width, height).
    val houses = listOf(
        floatArrayOf(0.02f, 0.40f, 0.07f, 0.12f), floatArrayOf(0.09f, 0.36f, 0.06f, 0.16f), floatArrayOf(0.15f, 0.41f, 0.08f, 0.11f),
        floatArrayOf(0.23f, 0.38f, 0.06f, 0.14f), floatArrayOf(0.04f, 0.53f, 0.08f, 0.10f), floatArrayOf(0.13f, 0.55f, 0.07f, 0.10f),
        floatArrayOf(0.21f, 0.56f, 0.09f, 0.10f), floatArrayOf(0.31f, 0.58f, 0.06f, 0.10f), floatArrayOf(0.08f, 0.68f, 0.09f, 0.10f),
        floatArrayOf(0.19f, 0.70f, 0.08f, 0.10f), floatArrayOf(0.29f, 0.72f, 0.07f, 0.10f), floatArrayOf(0.37f, 0.74f, 0.06f, 0.10f),
    )
    houses.forEach { (fx, fy, fw, fh) ->
        drawRect(Color(0xFFFDFDFB), Offset(w * fx, h * fy), Size(w * fw, h * fh))
        drawRect(Color(0x22000000), Offset(w * (fx + fw * 0.7f), h * fy), Size(w * fw * 0.3f, h * fh))
        drawRect(Color(0xFF2B5FD9), Offset(w * (fx + fw * 0.35f), h * (fy + fh * 0.45f)), Size(w * fw * 0.22f, h * fh * 0.35f))
    }
    // Blue domes.
    listOf(floatArrayOf(0.12f, 0.36f, 0.06f), floatArrayOf(0.26f, 0.38f, 0.05f), floatArrayOf(0.245f, 0.56f, 0.05f)).forEach { (fx, fy, fw) ->
        val r = w * fw / 2
        drawArc(Color(0xFF1F55C9), 180f, 180f, true, Offset(w * fx - r, h * fy - r), Size(r * 2, r * 2))
    }
    birds(0.55f to 0.20f, 0.60f to 0.15f)
}

/** Algarve: golden cliffs, a turquoise cove, a sandy beach and a palm tree. */
private fun DrawScope.algarve() {
    sky(0xFF45B3F0, 0xFFE4F6FF)
    sun(0.22f, 0.25f)
    sea(0.56f, 0.80f, 0xFF1AA6C9, 0xFF2FD0C8)
    glitter(0.22f, 0.60f, 4)
    val w = size.width
    val h = size.height
    // Cliffs on the right with a sea stack.
    drawPath(Path().apply {
        moveTo(w * 0.62f, h * 0.80f)
        cubicTo(w * 0.66f, h * 0.50f, w * 0.72f, h * 0.36f, w * 0.80f, h * 0.34f)
        lineTo(w, h * 0.30f)
        lineTo(w, h * 0.80f)
        close()
    }, Brush.verticalGradient(listOf(Color(0xFFF0B062), Color(0xFFC9772F))))
    drawRoundRect(Color(0xFFDD9446), Offset(w * 0.53f, h * 0.48f), Size(w * 0.05f, h * 0.30f), androidx.compose.ui.geometry.CornerRadius(w * 0.02f))
    // Beach.
    ridge(listOf(0.84f, 0.80f, 0.82f, 0.79f, 0.83f), Brush.verticalGradient(listOf(Color(0xFFF6DFAE), Color(0xFFE6C487))))
    // Palm tree.
    val base = Offset(w * 0.10f, h * 0.98f)
    val top = Offset(w * 0.15f, h * 0.42f)
    drawPath(Path().apply {
        moveTo(base.x, base.y)
        quadraticTo(w * 0.09f, h * 0.65f, top.x, top.y)
    }, Color(0xFF7A5534), style = Stroke(width = h * 0.035f, cap = StrokeCap.Round))
    listOf(-150f, -110f, -60f, -20f, 20f, 160f).forEach { deg ->
        val a = Math.toRadians(deg.toDouble())
        val len = h * 0.24f
        val end = Offset(top.x + cos(a).toFloat() * len, top.y + sin(a).toFloat() * len + len * 0.35f)
        val ctrl = Offset(top.x + cos(a).toFloat() * len * 0.6f, top.y + sin(a).toFloat() * len * 0.6f - len * 0.12f)
        drawPath(Path().apply { moveTo(top.x, top.y); quadraticTo(ctrl.x, ctrl.y, end.x, end.y) },
            Color(0xFF2F8A3C), style = Stroke(width = h * 0.045f, cap = StrokeCap.Round))
    }
    birds(0.42f to 0.18f, 0.47f to 0.24f)
}
