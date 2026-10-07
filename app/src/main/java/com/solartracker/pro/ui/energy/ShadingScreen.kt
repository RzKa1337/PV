package com.solartracker.pro.ui.energy

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.core.shading.DayShading
import com.solartracker.pro.core.shading.HeightSource
import com.solartracker.pro.core.shading.LatLon
import com.solartracker.pro.core.shading.Local
import com.solartracker.pro.core.shading.LocalShape
import com.solartracker.pro.core.shading.Obstacle
import com.solartracker.pro.core.shading.ObstacleGeometry
import com.solartracker.pro.core.shading.ObstacleGeometryService
import com.solartracker.pro.core.shading.ObstacleShape
import com.solartracker.pro.core.shading.ObstacleType
import com.solartracker.pro.core.shading.ShadowFootprint
import com.solartracker.pro.core.shading.ShadowForecast
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.uiLabel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val hmFmt = DateTimeFormatter.ofPattern("HH:mm")

private enum class DrawMode { NONE, POINT, POLYGON }

@Composable
fun ShadingScreen(vm: EnergyCenterViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val shading by vm.shading.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val site by vm.siteConfig.collectAsStateWithLifecycle()
    val map by vm.mapData.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var dateOffset by rememberSaveable { mutableStateOf(0L) }
    val date = LocalDate.now(zone).plusDays(dateOffset)
    var minute by rememberSaveable { mutableStateOf(12f * 60f) }
    var dayResult by remember { mutableStateOf<Pair<DayShading, List<ShadowForecast>>?>(null) }
    var layers by rememberSaveable { mutableStateOf(setOf("buildings", "trees", "manual", "current", "forecast", "uncertainty")) }
    var drawMode by rememberSaveable { mutableStateOf(DrawMode.NONE) }
    val draft = remember { mutableStateListOf<LatLon>() }
    // System Back cancels drawing on the map before leaving the page.
    BackHandler(enabled = drawMode != DrawMode.NONE) { drawMode = DrawMode.NONE; draft.clear() }
    var editing by remember { mutableStateOf<Obstacle?>(null) }
    var lossBefore by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(shading.engine, dateOffset) { dayResult = vm.shadingFor(date) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back_energy_center)) }
        ScreenTitle(stringResource(R.string.ec_shading_analysis), stringResource(R.string.sh_subtitle))
        message?.let { SectionCard { Text(it); TextButton(onClick = vm::dismissMessage) { Text("OK") } } }

        val engine = shading.engine
        val location = settings?.location
        if (site?.locationConfirmed != true || engine == null || location == null) {
            SectionCard {
                Text(shading.reason ?: if (shading.computing) stringResource(R.string.ec_computing) else stringResource(R.string.sh_unavailable))
                Text(stringResource(R.string.sh_set_location), style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        // DATA + CONFIDENCE
        SectionCard {
            Text(stringResource(R.string.sh_data_confidence), fontWeight = FontWeight.Bold)
            val conf = shading.confidence
            Text(stringResource(R.string.sh_result_confidence, Fmt.conf(conf?.score), conf?.kind?.uiLabel ?: ""), fontWeight = FontWeight.SemiBold)
            map?.let { m ->
                Text("Budynki/drzewa: ${if (m.loaded) "OpenStreetMap (Overpass), pobrano ${m.fetchedAt?.atZone(zone)?.toLocalDate()}, ${m.automatic.size} obiektów" else "nie pobrano"}", style = MaterialTheme.typography.bodySmall)
                Text("Teren: ${m.terrain?.let { "${it.source}, rozdzielczość ~${it.resolutionM?.toInt() ?: "?"} m" } ?: "brak danych"}", style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.sh_user_obstacles, m.user.size.toString()), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.sh_sun_algorithm, zone.id.toString()), style = MaterialTheme.typography.bodySmall)
            conf?.missing?.takeIf { it.isNotEmpty() }?.let { list ->
                Text(stringResource(R.string.sh_missing), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                list.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            Text(stringResource(R.string.sh_not_included),
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = vm::downloadMapData, enabled = !busy) { Text(if (busy) stringResource(R.string.downloading) else stringResource(R.string.sh_download_map)) }
                OutlinedButton(onClick = vm::clearMapData, enabled = !busy) { Text(stringResource(R.string.sh_delete_map)) }
            }
        }

        // DATE + TIME
        val snapshot = remember(engine, date, minute) { vm.snapshotAt(date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant()) }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { dateOffset-- }) { Text("◀") }
                Text(if (dateOffset == 0L) "Dziś, $date" else if (dateOffset == 1L) "Jutro, $date" else date.toString(), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { dateOffset++ }) { Text("▶") }
                TextButton(onClick = { dateOffset = 0 }) { Text(stringResource(R.string.today)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(-1L to "-1 mies.", 1L to "+1 mies.").forEach { (m, label) ->
                    OutlinedButton(onClick = { dateOffset = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), date.plusMonths(m)) }) { Text(label) }
                }
                OutlinedButton(onClick = { dateOffset = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), LocalDate.of(date.year, 12, 21)) }) { Text(stringResource(R.string.sh_dec21)) }
                OutlinedButton(onClick = { dateOffset = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), LocalDate.of(date.year, 6, 21)) }) { Text(stringResource(R.string.sh_jun21)) }
            }
            Text("Godzina: ${"%02d:%02d".format((minute / 60).toInt(), (minute % 60).toInt())}")
            Slider(value = minute, onValueChange = { minute = it }, valueRange = 0f..(24 * 60 - 1f))
            snapshot?.let { s ->
                Text("Słońce: azymut ${Fmt.deg(s.sunAzimuthDeg)}, wysokość ${Fmt.deg(s.sunElevationDeg)}")
                if (s.sunElevationDeg <= 0) Text(stringResource(R.string.sun_below_horizon))
                else {
                    Text("Zacienione: ${Fmt.pct(s.shadedAreaFraction * 100)} powierzchni · moc ${Fmt.pct(s.powerFactor * 100)} wartości bez cienia" +
                        if (s.total) " (cień całkowity)" else if (s.partial) " (cień częściowy)" else "")
                    if (s.terrainBlocked) Text(stringResource(R.string.sh_terrain_blocked))
                    if (s.blockingObstacleIds.isNotEmpty()) Text("Przeszkody: ${s.blockingObstacleIds.joinToString { id -> engine.site.allObstacles.firstOrNull { it.obstacle.id == id }?.obstacle?.let { it.name ?: it.type.label } ?: id }}")
                    PanelGrid(s.panelShadedFraction, engine.site.array.columns)
                    if (s.stringPowerFactor.size > 1) Text("Stringi: ${s.stringPowerFactor.mapIndexed { i, f -> "S${i + 1} ${Fmt.pct(f * 100)}" }.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // MAP
        SectionCard {
            Text(stringResource(R.string.sh_map), fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("buildings" to stringResource(R.string.sh_layer_buildings), "trees" to stringResource(R.string.sh_layer_trees), "manual" to stringResource(R.string.sh_layer_manual),
                    "terrain" to stringResource(R.string.sh_layer_terrain), "current" to stringResource(R.string.sh_layer_current),
                    "forecast" to stringResource(R.string.sh_layer_forecast), "loss" to stringResource(R.string.sh_layer_loss), "uncertainty" to stringResource(R.string.sh_layer_uncertainty)).forEach { (k, label) ->
                    FilterChip(selected = k in layers, onClick = { layers = if (k in layers) layers - k else layers + k }, label = { Text(label) })
                }
            }
            val origin = LatLon(location.latitude, location.longitude)
            val frame = engine.site.frame
            val shapes = buildList {
                // Panels
                val hull = ObstacleGeometryService.convexHull(engine.site.samplePoints.map { Local(it.east, it.north) })
                add(MapShape(hull.map(frame::toLatLon), MapColors.PANELS.first, MapColors.PANELS.second))
                // Obstacles
                engine.site.allObstacles.filter { g ->
                    val o = g.obstacle
                    when {
                        o.userDefined -> "manual" in layers
                        o.type == ObstacleType.TREE -> "trees" in layers
                        else -> "buildings" in layers
                    }
                }.forEach { g -> add(obstacleShape(g, frame::toLatLon, "uncertainty" in layers)) }
                // Shadows: now and at the selected time
                val now = java.time.Instant.now()
                if ("current" in layers) engine.site.usableObstacles.forEach { g ->
                    val sun = com.solartracker.pro.core.solar.SolarCalculator.position(location, now)
                    ShadowFootprint.of(g, sun.azimuthDeg, sun.elevationDeg)?.let { add(MapShape(it.map(frame::toLatLon), MapColors.SHADOW.first, MapColors.SHADOW.second)) }
                }
                if ("forecast" in layers) snapshot?.let { s ->
                    engine.site.usableObstacles.forEach { g ->
                        ShadowFootprint.of(g, s.sunAzimuthDeg, s.sunElevationDeg)?.let { add(MapShape(it.map(frame::toLatLon), MapColors.DRAFT.first, MapColors.DRAFT.second)) }
                    }
                    if (s.sunElevationDeg > 0) add(MapShape(listOf(origin, frame.toLatLon(Local(60 * kotlin.math.sin(Math.toRadians(s.sunAzimuthDeg)), 60 * kotlin.math.cos(Math.toRadians(s.sunAzimuthDeg))))), MapColors.SUN.first, 0, closed = false, width = 6f))
                }
                // Panel azimuth
                val az = engine.site.array.azimuthDeg
                add(MapShape(listOf(origin, frame.toLatLon(Local(15 * kotlin.math.sin(Math.toRadians(az)), 15 * kotlin.math.cos(Math.toRadians(az))))), MapColors.PANELS.first, 0, closed = false, width = 5f))
                if ("terrain" in layers) {
                    // Horizon profile drawn as a ring 80 m out (direction + height angle as label colour intensity).
                    val ring = (0 until 360 step 5).map { a -> frame.toLatLon(Local((80 + engine.site.horizon.elevationAt(a.toDouble()) * 4) * kotlin.math.sin(Math.toRadians(a.toDouble())), (80 + engine.site.horizon.elevationAt(a.toDouble()) * 4) * kotlin.math.cos(Math.toRadians(a.toDouble())))) }
                    add(MapShape(ring, MapColors.ESTIMATED.first, 0, closed = true, width = 2f))
                }
                if (draft.isNotEmpty()) add(MapShape(draft.toList(), MapColors.DRAFT.first, MapColors.DRAFT.second, closed = draft.size >= 3))
            }
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                fun onMapTap(p: LatLon) {
                    if (drawMode == DrawMode.POINT) {
                        editing = Obstacle("user-${UUID.randomUUID()}", ObstacleType.TREE, ObstacleShape.Point(p, ObstacleType.TREE.defaultRadiusM),
                            com.solartracker.pro.core.shading.HeightValue.UNKNOWN, source = "Użytkownik", userDefined = true)
                        drawMode = DrawMode.NONE
                    } else if (drawMode == DrawMode.POLYGON) {
                        draft.add(p)
                    }
                }
                OsmMap(center = origin, marker = origin, shapes = shapes, modifier = Modifier.fillMaxSize(), onTap = ::onMapTap)
                Text("N ↑", Modifier.align(Alignment.TopEnd).padding(8.dp), fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.sh_legend),
                style = MaterialTheme.typography.labelSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = drawMode == DrawMode.POINT, onClick = { drawMode = if (drawMode == DrawMode.POINT) DrawMode.NONE else DrawMode.POINT; draft.clear() }, label = { Text(stringResource(R.string.sh_add_point)) })
                FilterChip(selected = drawMode == DrawMode.POLYGON, onClick = { drawMode = if (drawMode == DrawMode.POLYGON) DrawMode.NONE else DrawMode.POLYGON; draft.clear() }, label = { Text(stringResource(R.string.sh_draw_building)) })
                if (drawMode == DrawMode.POLYGON && draft.size >= 2) Button(onClick = {
                    val shape = if (draft.size >= 3) ObstacleShape.Polygon(draft.toList()) else ObstacleShape.Line(draft.toList())
                    editing = Obstacle("user-${UUID.randomUUID()}", if (draft.size >= 3) ObstacleType.BUILDING else ObstacleType.FENCE, shape,
                        com.solartracker.pro.core.shading.HeightValue.UNKNOWN, source = "Użytkownik", userDefined = true)
                    draft.clear(); drawMode = DrawMode.NONE
                }) { Text(stringResource(R.string.sh_finish, draft.size.toString())) }
                OutlinedButton(onClick = {
                    editing = Obstacle("user-${UUID.randomUUID()}", ObstacleType.OTHER, ObstacleShape.Bearing(20.0, 170.0, 190.0),
                        com.solartracker.pro.core.shading.HeightValue.UNKNOWN, source = "Użytkownik", userDefined = true)
                }) { Text(stringResource(R.string.sh_bearing_obstacle)) }
            }
        }

        // HORIZON
        SectionCard {
            Text(stringResource(R.string.sh_horizon), fontWeight = FontWeight.Bold)
            HorizonChart(engine.site.horizon, location, date, zone, snapshot?.sunAzimuthDeg, snapshot?.sunElevationDeg)
            Text(stringResource(R.string.sh_horizon_hint), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.sh_sky_view, Fmt.pct(engine.site.horizon.skyViewFactor * 100).toString()), style = MaterialTheme.typography.bodySmall)
        }

        // DAY
        dayResult?.let { (day, events) ->
            SectionCard {
                Text(stringResource(R.string.sh_day), fontWeight = FontWeight.Bold)
                HourlyShadingChart(day, zone)
                Text(stringResource(R.string.sh_day_hint), style = MaterialTheme.typography.labelSmall)
                MetricRow(stringResource(R.string.sh_unshaded), Fmt.kwh(day.unshadedKwh), com.solartracker.pro.core.quality.DataKind.ESTIMATED)
                MetricRow(stringResource(R.string.ec_shading_loss), "${Fmt.kwh(day.lossKwh)} (${"%.1f".format(day.lossPercent)}%)", shading.confidence?.kind ?: com.solartracker.pro.core.quality.DataKind.ESTIMATED)
                MetricRow(stringResource(R.string.sh_shaded), Fmt.kwh(day.shadedKwh), com.solartracker.pro.core.quality.DataKind.ESTIMATED)
                lossBefore?.let { Text("Przed ostatnią korektą przeszkód (dziś): ${Fmt.kwh(it)} → teraz ${Fmt.kwh(shading.today?.lossKwh)}", fontWeight = FontWeight.SemiBold) }
                Text(stringResource(R.string.sh_shade_start_end), fontWeight = FontWeight.SemiBold)
                if (events.isEmpty()) Text(stringResource(R.string.sh_no_shade))
                events.forEach { e ->
                    Text("${hmFmt.format(e.event.start.atZone(zone))}–${hmFmt.format(e.event.end.atZone(zone))} (${e.event.duration.toMinutes()} min, ${e.dayPart.label}) · ${e.obstacleName} · wys. ${e.obstacleHeightLabel} · do ${Fmt.pct(e.event.maxShadedFraction * 100)} · panele ${e.event.panels.sorted().joinToString { "#${it + 1}" }} · −${Fmt.kwh(e.event.energyLossKwh)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // OBSTACLES
        SectionCard {
            Text(stringResource(R.string.sh_obstacles), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.sh_obstacles_hint), style = MaterialTheme.typography.bodySmall)
            engine.site.allObstacles.sortedWith(compareBy({ it.obstacle.height.known }, { it.distanceM })).take(80).forEach { g ->
                val o = g.obstacle
                TextButton(onClick = { editing = o }) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${o.name ?: o.type.label}${if (!o.enabled) " (wyłączona)" else ""}${if (o.userDefined) " · użytkownik" else ""}", fontWeight = FontWeight.SemiBold)
                        Text("${Fmt.m(g.distanceM)} · azymut ${Fmt.deg(g.azimuthDeg)} (${Fmt.deg(g.azimuthFromDeg)}–${Fmt.deg(g.azimuthToDeg)}) · " +
                            "wys. ${o.height.meters?.let { Fmt.m(it) } ?: "NIEZNANA"}${o.height.uncertaintyM?.let { " ±${Fmt.m(it)}" } ?: ""} [${o.height.source.label}]" +
                            (g.relativeHeightM?.let { " · względem paneli ${Fmt.m(it)} · kąt ${Fmt.deg(g.topElevationAngleDeg)}" } ?: "") +
                            " · źródło: ${o.source}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!o.height.known) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (engine.site.allObstacles.isEmpty()) Text(stringResource(R.string.sh_no_obstacles))
        }

        // YEAR
        if (shading.year.isNotEmpty()) SectionCard {
            Text(stringResource(R.string.sh_year), fontWeight = FontWeight.Bold)
            shading.year.forEach { m ->
                val pct = if (m.unshadedKwh > 0) m.lossKwh / m.unshadedKwh * 100 else 0.0
                Text("${m.month}: −${Fmt.kwh(m.lossKwh)} (${"%.1f".format(pct)}%), dni z cieniem ok. ${m.eventDays}", style = MaterialTheme.typography.bodySmall)
            }
            val total = shading.year.sumOf { it.lossKwh }
            Text(stringResource(R.string.sh_year_total, Fmt.kwh(total).toString()), fontWeight = FontWeight.SemiBold)
        }
    }

    editing?.let { o ->
        ObstacleEditorDialog(
            obstacle = o,
            siteElevationM = map?.terrain?.originElevationM ?: settings?.location?.elevationM ?: 0.0,
            onDismiss = { editing = null },
            onSave = { updated, change ->
                lossBefore = shading.today?.lossKwh
                val saved = if (o.userDefined) updated else updated.copy(id = "user-${o.id}", overridesId = o.id, userDefined = true, source = "Użytkownik (korekta: ${o.source})")
                vm.saveObstacle(saved, change)
                editing = null
            },
            onDelete = if (o.userDefined) ({ vm.deleteObstacle(o.id); editing = null }) else null,
        )
    }
}

private fun obstacleShape(g: ObstacleGeometry, toLatLon: (Local) -> LatLon, uncertainty: Boolean): MapShape {
    val o = g.obstacle
    val colors = when {
        o.type == ObstacleType.TREE -> MapColors.TREE
        !uncertainty -> MapColors.CONFIRMED
        !o.height.known -> MapColors.UNKNOWN
        o.height.source == HeightSource.USER_CONFIRMED || o.height.source == HeightSource.SURVEY || o.height.source == HeightSource.MAP_TAG -> MapColors.CONFIRMED
        else -> MapColors.ESTIMATED
    }
    val pts = when (val s = g.local) {
        is LocalShape.Prism -> s.footprint
        is LocalShape.Cylinder -> (0 until 12).map { i ->
            val a = 2 * Math.PI * i / 12
            Local(s.centre.east + s.radiusM * kotlin.math.sin(a), s.centre.north + s.radiusM * kotlin.math.cos(a))
        }
    }
    return MapShape(pts.map(toLatLon), colors.first, if (o.enabled) colors.second else 0)
}

@Composable
private fun PanelGrid(fractions: List<Double>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        fractions.chunked(columns.coerceAtLeast(1)).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEachIndexed { c, f ->
                    Box(
                        Modifier.size(width = 28.dp, height = 40.dp)
                            .background(Color(red = 0.1f, green = 0.45f * (1 - f.toFloat()) + 0.05f, blue = 0.9f * (1 - f.toFloat()) + 0.1f)),
                        contentAlignment = Alignment.Center,
                    ) { Text("${r * columns + c + 1}", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        Text(stringResource(R.string.sh_panels_legend), style = MaterialTheme.typography.labelSmall)
    }
}
