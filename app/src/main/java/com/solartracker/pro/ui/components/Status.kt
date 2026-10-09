package com.solartracker.pro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** State colours reserved for status (never used for chart series); always shown with an icon and a label. */
enum class StatusLevel(val icon: ImageVector) {
    GOOD(Icons.Outlined.CheckCircle),
    WARNING(Icons.Outlined.Warning),
    CRITICAL(Icons.Outlined.ErrorOutline),
    INFO(Icons.Outlined.Info),
    UNKNOWN(Icons.Outlined.HelpOutline),
}

object StatusColors {
    val good = Color(0xFF2E9E5B)
    val warning = Color(0xFFE09A1A)

    @Composable
    fun of(level: StatusLevel): Color = when (level) {
        StatusLevel.GOOD -> good
        StatusLevel.WARNING -> warning
        StatusLevel.CRITICAL -> MaterialTheme.colorScheme.error
        StatusLevel.INFO -> MaterialTheme.colorScheme.secondary
        StatusLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Icon + text in the status colour (colour is never the only signal). */
@Composable
fun StatusLabel(
    level: StatusLevel,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight? = FontWeight.SemiBold,
    textColor: Color? = null,
) {
    val color = StatusColors.of(level)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(level.icon, contentDescription = null, tint = color, modifier = Modifier.size(if (style.fontSize.value >= 20f) 24.dp else 18.dp))
        Text(text, style = style, fontWeight = fontWeight, color = textColor ?: color)
    }
}
