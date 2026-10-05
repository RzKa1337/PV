package com.solartracker.pro.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.solartracker.pro.core.shading.Foliage
import com.solartracker.pro.core.shading.HeightSource
import com.solartracker.pro.core.shading.HeightValue
import com.solartracker.pro.core.shading.Obstacle
import com.solartracker.pro.core.shading.ObstacleShape
import com.solartracker.pro.core.shading.ObstacleType
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class HeightMode(val label: String) { METERS("Wysokość [m]"), ABSOLUTE("Wysokość n.p.m. [m]"), LEVELS("Kondygnacje") }

/**
 * Edit an obstacle: type, height (relative, absolute or from storeys), confirmed vs estimated,
 * tree foliage, enable/disable. Automatic obstacles are saved as a user override (history kept).
 */
@Composable
fun ObstacleEditorDialog(
    obstacle: Obstacle,
    siteElevationM: Double,
    onDismiss: () -> Unit,
    onSave: (Obstacle, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(obstacle.name.orEmpty()) }
    var type by remember { mutableStateOf(obstacle.type) }
    var mode by remember { mutableStateOf(if (obstacle.height.source == HeightSource.FROM_LEVELS) HeightMode.LEVELS else HeightMode.METERS) }
    var meters by remember { mutableStateOf(obstacle.height.meters?.let { "%.1f".format(it).replace(',', '.') }.orEmpty()) }
    var absolute by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(obstacle.baseElevationM?.toString().orEmpty()) }
    var levels by remember { mutableStateOf("") }
    var storey by remember { mutableStateOf(HeightValue.DEFAULT_STOREY_M.toString()) }
    var roof by remember { mutableStateOf("0") }
    var confirmed by remember { mutableStateOf(obstacle.height.source == HeightSource.USER_CONFIRMED) }
    var enabled by remember { mutableStateOf(obstacle.enabled) }
    var radius by remember { mutableStateOf((obstacle.shape as? ObstacleShape.Point)?.radiusM?.toString().orEmpty()) }
    val bearing = obstacle.shape as? ObstacleShape.Bearing
    var distance by remember { mutableStateOf(bearing?.distanceM?.toString().orEmpty()) }
    var azFrom by remember { mutableStateOf(bearing?.azimuthFromDeg?.toString().orEmpty()) }
    var azTo by remember { mutableStateOf(bearing?.azimuthToDeg?.toString().orEmpty()) }
    val foliage = obstacle.foliage ?: Foliage()
    var leafOn by remember { mutableStateOf(foliage.transmittanceLeafOn.toString()) }
    var leafOff by remember { mutableStateOf(foliage.transmittanceLeafOff.toString()) }
    var crown by remember { mutableStateOf(foliage.crownBaseM?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun num(s: String) = s.replace(',', '.').trim().toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(obstacle.name ?: obstacle.type.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Źródło: ${obstacle.source} · obecna wysokość: ${obstacle.height.meters?.let { "%.1f m".format(it) } ?: "NIEZNANA"} (${obstacle.height.source.label})" +
                    (obstacle.height.note?.let { " – $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Nazwa") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Typ")
                Column { ObstacleType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) } }
                Text("Wysokość")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { HeightMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }) } }
                when (mode) {
                    HeightMode.METERS -> OutlinedTextField(meters, { meters = it }, label = { Text("Wysokość nad gruntem przy przeszkodzie [m]") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    HeightMode.ABSOLUTE -> OutlinedTextField(absolute, { absolute = it }, label = { Text("Wierzchołek n.p.m. [m]") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    HeightMode.LEVELS -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(levels, { levels = it }, label = { Text("Kondygnacje") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(storey, { storey = it }, label = { Text("Wys. kond. [m]") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(roof, { roof = it }, label = { Text("Dach [m]") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                OutlinedTextField(base, { base = it }, label = { Text("Teren przy przeszkodzie n.p.m. [m] (puste = jak instalacja ${"%.0f".format(siteElevationM)} m)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (mode != HeightMode.LEVELS) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wysokość zmierzona / potwierdzona", Modifier.weight(1f))
                    Switch(checked = confirmed, onCheckedChange = { confirmed = it })
                }
                if (mode == HeightMode.LEVELS) Text("Wysokość z kondygnacji jest zawsze SZACUNKIEM (niepewność ±1 m + 0,5 m na kondygnację).", style = MaterialTheme.typography.bodySmall)
                if (obstacle.shape is ObstacleShape.Point) OutlinedTextField(radius, { radius = it }, label = { Text("Promień [m] (korona drzewa, komin)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (bearing != null) {
                    OutlinedTextField(distance, { distance = it }, label = { Text("Odległość od paneli [m]") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(azFrom, { azFrom = it }, label = { Text("Azymut od [°]") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(azTo, { azTo = it }, label = { Text("Azymut do [°]") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                if (type == ObstacleType.TREE) {
                    Text("Drzewo: przepuszczalność korony 0–1 (0 = pełny cień). Liście: maj–październik.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(leafOn, { leafOn = it }, label = { Text("Z liśćmi") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(leafOff, { leafOff = it }, label = { Text("Bez liści") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(crown, { crown = it }, label = { Text("Korona od [m]") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Uwzględniaj w obliczeniach", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (obstacle.history.isNotEmpty()) {
                    Text("Historia zmian:", style = MaterialTheme.typography.labelLarge)
                    val f = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
                    obstacle.history.takeLast(10).forEach { Text("${f.format(it.at)} – ${it.description}", style = MaterialTheme.typography.bodySmall) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val baseElevation = base.takeIf { it.isNotBlank() }?.let(::num)
                val height: HeightValue? = runCatching {
                    when (mode) {
                        HeightMode.METERS -> num(meters)?.let { HeightValue(it, if (confirmed) HeightSource.USER_CONFIRMED else HeightSource.USER_ESTIMATED, if (confirmed) 0.3 else 2.0) }
                        HeightMode.ABSOLUTE -> num(absolute)?.let { top -> HeightValue(top - (baseElevation ?: siteElevationM), if (confirmed) HeightSource.USER_CONFIRMED else HeightSource.USER_ESTIMATED, if (confirmed) 0.5 else 2.0, note = "n.p.m. ${"%.1f".format(top)} m") }
                        HeightMode.LEVELS -> num(levels)?.let { HeightValue.fromLevels(it, num(storey) ?: HeightValue.DEFAULT_STOREY_M, num(roof) ?: 0.0) }
                    }
                }.getOrNull()
                if (height == null) { error = "Podaj poprawną wysokość (0–1000 m)"; return@TextButton }
                val shape = when (val s = obstacle.shape) {
                    is ObstacleShape.Point -> s.copy(radiusM = num(radius)?.coerceIn(0.05, 30.0) ?: s.radiusM)
                    is ObstacleShape.Bearing -> {
                        val d = num(distance); val a1 = num(azFrom); val a2 = num(azTo)
                        if (d == null || d <= 0 || a1 == null || a2 == null) { error = "Podaj odległość i azymuty"; return@TextButton }
                        ObstacleShape.Bearing(d, a1, a2)
                    }
                    else -> s
                }
                val foliageNew = if (type == ObstacleType.TREE) Foliage(
                    transmittanceLeafOn = num(leafOn)?.coerceIn(0.0, 1.0) ?: 0.2, transmittanceLeafOff = num(leafOff)?.coerceIn(0.0, 1.0) ?: 0.7,
                    crownBaseM = num(crown),
                ) else null
                val updated = obstacle.copy(name = name.ifBlank { null }, type = type, height = height, baseElevationM = baseElevation, shape = shape, foliage = foliageNew, enabled = enabled)
                val change = buildString {
                    append("Wysokość ${"%.1f".format(height.meters)} m (${height.source.label})")
                    if (!enabled) append(", wyłączona")
                    if (type != obstacle.type) append(", typ ${type.label}")
                }
                onSave(updated, change)
            }) { Text("Zapisz") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Usuń") } }
                TextButton(onClick = onDismiss) { Text("Anuluj") }
            }
        },
    )
}
