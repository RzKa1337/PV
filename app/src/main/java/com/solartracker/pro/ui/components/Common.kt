package com.solartracker.pro.ui.components

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector

/** Card header: a tinted icon and an upper-case label (same style on every screen). */
@Composable
fun SectionHeader(text: String, icon: ImageVector? = null, tint: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small label marking a value as a model estimate, never a measurement. */
@Composable
fun EstimateBadge(modifier: Modifier = Modifier, text: String = stringResource(R.string.estimate_badge), onImage: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (onImage) Color.Black.copy(alpha = 0.35f) else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
        contentColor = if (onImage) Color(0xFFFFD98A) else MaterialTheme.colorScheme.tertiary,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Content card over the scenic backdrop: translucent ("glass") surface with a hairline border; it fades in once when
 * the screen opens. Status cards pass their own (opaque) container colour.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = glassColor(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().gentleEnter(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Tile with a caption, a large value and an optional footnote. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    estimate: Boolean = false,
) {
    SectionCard(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (footnote != null) {
            Text(
                text = footnote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (estimate) EstimateBadge()
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, scene: SunnyScene? = null) {
    if (scene != null) {
        SunnyBanner(scene, title, subtitle)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
