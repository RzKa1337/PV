package com.solartracker.pro.core.shading

import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.atan2

class ShadingTest {
    private val warsaw = GeoLocation(52.2297, 21.0122, 100.0)
    private val origin = LatLon(warsaw.latitude, warsaw.longitude)
    private val frame = LocalFrame(origin)
    private val zone = ZoneId.of("Europe/Warsaw")
    private val system = PvSystem(peakPowerKw = 4.0, tiltDeg = 35.0, azimuthDeg = 180.0)
    private val array = PvArrayGeometry(tiltDeg = 35.0, azimuthDeg = 180.0, baseHeightM = 0.5, rows = 1, columns = 4)

    private fun at(east: Double, north: Double) = frame.toLatLon(Local(east, north))
    private fun box(cx: Double, cy: Double, w: Double, d: Double) = ObstacleShape.Polygon(listOf(at(cx - w / 2, cy - d / 2), at(cx + w / 2, cy - d / 2), at(cx + w / 2, cy + d / 2), at(cx - w / 2, cy + d / 2)))
    private fun building(id: String, cx: Double, cy: Double, h: Double?, w: Double = 20.0, d: Double = 10.0, src: HeightSource = HeightSource.USER_CONFIRMED) = Obstacle(
        id, ObstacleType.BUILDING, box(cx, cy, w, d), if (h == null) HeightValue.UNKNOWN else HeightValue(h, src), source = "test", name = id,
    )
    private fun site(obstacles: List<Obstacle>, terrain: TerrainSamples? = null, arr: PvArrayGeometry = array) =
        ShadingSite(warsaw, LocationAccuracy.MAP_POINT, true, arr, obstacles, terrain, buildingDataLoaded = true)

    @Test
    fun localFrameRoundTripAndAzimuth() {
        val p = frame.toLatLon(Local(100.0, -200.0))
        val l = frame.toLocal(p)
        assertEquals(100.0, l.east, 1e-6)
        assertEquals(-200.0, l.north, 1e-6)
        assertEquals(90.0, Local(10.0, 0.0).azimuthDeg, 1e-9)
        assertEquals(180.0, Local(0.0, -5.0).azimuthDeg, 1e-9)
        // 1 km north ≈ 0.008983° at this latitude
        assertEquals(0.008983, frame.toLatLon(Local(0.0, 1000.0)).lat - origin.lat, 2e-5)
    }

    @Test
    fun heightsFromMapTagsLevelsOrUnknown() {
        assertEquals(HeightSource.MAP_TAG, OverpassBuildingProvider.heightFromTags(mapOf("height" to "12.5 m")).source)
        assertEquals(12.5, OverpassBuildingProvider.heightFromTags(mapOf("height" to "12,5")).meters!!, 1e-9)
        val levels = OverpassBuildingProvider.heightFromTags(mapOf("building:levels" to "4", "roof:levels" to "1"))
        assertEquals(HeightSource.FROM_LEVELS, levels.source)
        assertEquals(DataKind.ESTIMATED, levels.source.kind)
        assertEquals(14.5, levels.meters!!, 1e-9)
        assertEquals(3.0, levels.uncertaintyM!!, 1e-9)
        assertTrue(levels.note!!.contains("4 kond. × 3 m"))
        val unknown = OverpassBuildingProvider.heightFromTags(mapOf("building" to "yes"))
        assertNull("never invented", unknown.meters)
        assertEquals(HeightSource.UNKNOWN, unknown.source)
        assertEquals(12.192, OverpassBuildingProvider.parseMeters("40 ft")!!, 1e-3)
        assertNull(OverpassBuildingProvider.parseMeters("tall"))
    }

    @Test
    fun obstacleGeometryRelativeHeightAzimuthElevation() {
        // 10 m building centred 25 m south, terrain at its base 2 m higher than the site.
        val o = building("b", 0.0, -25.0, 10.0).copy(baseElevationM = 102.0)
        val g = ObstacleGeometryService(frame, 100.0, panelHeightM = 1.0).geometry(o)
        assertEquals(180.0, g.azimuthDeg, 0.5)
        assertEquals(20.0, g.distanceM, 0.1) // nearest face 20 m away
        assertEquals(11.0, g.relativeHeightM!!, 1e-9) // 2 + 10 − 1
        assertEquals(Math.toDegrees(atan2(11.0, 20.0)), g.topElevationAngleDeg, 0.05)
        assertTrue(g.azimuthFromDeg > 150 && g.azimuthToDeg < 210)
        assertEquals(112.0, g.topElevationM!!, 1e-9)
    }

    @Test
    fun sunPositionUsedForShadingIsAccurate() {
        // Solar noon in Warsaw at the June solstice: elevation 90 − 52.23 + 23.44 ≈ 61.2°
        val noon = SolarCalculator.sunTimes(warsaw, LocalDate.of(2026, 6, 21)).solarNoon
        val pos = SolarCalculator.position(warsaw, noon)
        assertEquals(61.2, pos.elevationDeg, 0.3)
        assertEquals(180.0, pos.azimuthDeg, 0.5)
    }

    @Test
    fun southBuildingShadesInWinterNotInSummer() {
        val engine = ShadingAnalysisEngine(site(listOf(building("dom", 0.0, -20.0, 12.0, w = 30.0))))
        val winter = engine.day(system, LocalDate.of(2026, 12, 21), zone)
        val summer = engine.day(system, LocalDate.of(2026, 6, 21), zone)
        assertTrue("winter loss ${winter.lossPercent}", winter.lossPercent > 50)
        // In June the sun at midday is far above the building; low morning/evening sun (SE/SW) may still be blocked.
        assertTrue(summer.steps.filter { it.time.atZone(zone).hour in 10..13 }.all { it.blockingObstacleIds.isEmpty() })
        assertTrue(summer.lossPercent < winter.lossPercent / 5)
        val event = winter.events.single { it.obstacleId == "dom" }
        val start = event.start.atZone(zone).toLocalTime()
        val end = event.end.atZone(zone).toLocalTime()
        assertTrue("shadow around noon: $start–$end", start.hour < 12 && end.hour >= 12)
        assertEquals(setOf(0, 1, 2, 3), event.panels)
        assertTrue(event.energyLossKwh > 0)
        assertTrue(winter.steps.any { it.total })
    }

    @Test
    fun poleCastsPartialShadowAndBypassLimitsLoss() {
        // Thin pole 6 m south-east-ish in front of panel 0 only.
        val pole = Obstacle("pole", ObstacleType.POLE, ObstacleShape.Point(at(-1.5, -4.0), 0.25), HeightValue(8.0, HeightSource.USER_CONFIRMED), source = "test")
        val engine = ShadingAnalysisEngine(site(listOf(pole)))
        val day = engine.day(system, LocalDate.of(2026, 3, 20), zone, stepMinutes = 2)
        val shadedSteps = day.steps.filter { "pole" in it.blockingObstacleIds }
        assertTrue(shadedSteps.isNotEmpty())
        assertTrue(shadedSteps.all { it.partial })
        // With bypass diodes a small shadow costs far less than the whole string.
        assertTrue(shadedSteps.all { it.powerFactor > 0.5 })
    }

    @Test
    fun bypassDiodeModelOneShadedGroup() {
        // A wall that hides exactly the bottom part? Use a direct check of the string formula through a tiny geometry:
        val arr = PvArrayGeometry(35.0, 180.0, columns = 4, stringOfPanel = listOf(0, 0, 1, 1))
        assertEquals(2, arr.stringCount)
        assertEquals(48, arr.samplePoints().size) // 4 panels × 3 groups × 2×2
        val pts = arr.samplePoints()
        // Panel 0 lies to the east of panel 3 for a south facing row (row direction: −east).
        assertTrue(pts.filter { it.panel == 0 }.map { it.east }.average() > pts.filter { it.panel == 3 }.map { it.east }.average())
        assertTrue(pts.all { it.up in 0.5..(0.5 + 1.72 * kotlin.math.sin(Math.toRadians(35.0)) + 1e-9) })
    }

    @Test
    fun treeTransmittanceDependsOnSeason() {
        val tree = Obstacle("tree", ObstacleType.TREE, ObstacleShape.Point(at(0.0, -8.0), 4.0), HeightValue(15.0, HeightSource.USER_ESTIMATED),
            foliage = Foliage(transmittanceLeafOn = 0.2, transmittanceLeafOff = 0.7, crownBaseM = 3.0), source = "test")
        val engine = ShadingAnalysisEngine(site(listOf(tree)))
        val june = engine.day(system, LocalDate.of(2026, 6, 21), zone).lossPercent
        val dec = engine.day(system, LocalDate.of(2026, 12, 21), zone)
        assertTrue(dec.steps.filter { "tree" in it.blockingObstacleIds }.all { it.shadedAreaFraction <= 0.31 })
        assertTrue(june >= 0.0)
    }

    @Test
    fun unknownHeightIsExcludedAndLowersConfidence() {
        val known = ShadingAnalysisEngine(site(listOf(building("a", 0.0, -40.0, 9.0))))
        val withUnknown = ShadingAnalysisEngine(site(listOf(building("a", 0.0, -40.0, 9.0), building("b", 20.0, -30.0, null))))
        val c1 = known.confidence()
        val c2 = withUnknown.confidence()
        assertTrue(c2.score < c1.score)
        assertTrue(c2.missing.any { it.startsWith("Brak wysokości: b") })
        assertEquals(DataKind.ESTIMATED, c2.kind)
        assertEquals(DataKind.CALCULATED, c1.kind)
        assertEquals(1, withUnknown.site.usableObstacles.size)
        val estimated = ShadingAnalysisEngine(site(listOf(building("a", 0.0, -40.0, 9.0, src = HeightSource.FROM_LEVELS))))
        assertEquals(DataKind.ESTIMATED, estimated.confidence().kind)
        val approx = ShadingAnalysisEngine(ShadingSite(warsaw, LocationAccuracy.CITY, false, array, emptyList(), null, false)).confidence()
        assertTrue(approx.score < 0.2)
        assertTrue(approx.missing.any { it.contains("Potwierdź lokalizację") })
    }

    @Test
    fun manualCorrectionOverridesAutomaticAndChangesResult() {
        val auto = building("osm-1", 0.0, -20.0, null).copy(source = "OpenStreetMap")
        val user = auto.copy(id = "user-1", height = HeightValue(12.0, HeightSource.USER_CONFIRMED), userDefined = true, overridesId = "osm-1", source = "Użytkownik",
            history = listOf(ObstacleChange(Instant.EPOCH, "Wysokość 12 m (pomiar)")))
        val merged = ObstacleMerger.merge(listOf(auto), listOf(user))
        assertEquals(listOf("user-1"), merged.map { it.id })
        val before = ShadingAnalysisEngine(site(listOf(auto))).day(system, LocalDate.of(2026, 12, 21), zone).lossKwh
        val after = ShadingAnalysisEngine(site(merged)).day(system, LocalDate.of(2026, 12, 21), zone).lossKwh
        assertEquals(0.0, before, 1e-9)
        assertTrue(after > 0.0)
        val disabled = ShadingAnalysisEngine(site(listOf(user.copy(enabled = false)))).day(system, LocalDate.of(2026, 12, 21), zone).lossKwh
        assertEquals(0.0, disabled, 1e-9)
    }

    @Test
    fun terrainHorizonFromFakeDem() {
        // Ridge from 800 m south, 400 m above the site (≈ 21.8° at 1 km – above the winter sun, max 14.3°).
        val dem = FakeTerrainDataProvider { p ->
            val l = frame.toLocal(p)
            if (l.north < -800 && kotlin.math.abs(l.east) < 3000) 500.0 else 100.0
        }
        val terrain = TerrainSampler.sample(dem, origin)
        assertEquals(100.0, terrain.originElevationM, 0.0)
        val engine = ShadingAnalysisEngine(site(emptyList(), terrain))
        val southElev = engine.site.horizon.elevationAt(180.0)
        assertTrue("horizon south $southElev", southElev in 20.0..24.0)
        assertEquals(0.0, engine.site.horizon.elevationAt(0.0), 0.5)
        val dec = engine.day(system, LocalDate.of(2026, 12, 21), zone)
        assertTrue("winter sun (max ~14°) hidden by the ridge", dec.steps.filter { it.sunElevationDeg > 0 }.all { it.terrainBlocked })
        assertTrue(dec.events.any { it.obstacleId == HorizonBuilder.TERRAIN_ID })
        val jun = engine.day(system, LocalDate.of(2026, 6, 21), zone)
        assertFalse(jun.steps.filter { it.sunElevationDeg > 30 }.any { it.terrainBlocked })
        assertTrue(engine.site.horizon.skyViewFactor < 1.0)
    }

    @Test
    fun forecastServiceNextEventAndYear() {
        val engine = ShadingAnalysisEngine(site(listOf(building("dom", 0.0, -20.0, 12.0, w = 30.0))))
        val service = ShadingForecastService(engine, system, zone)
        val morning = LocalDate.of(2026, 12, 21).atTime(6, 0).atZone(zone).toInstant()
        val next = service.nextEvent(morning)
        assertNotNull(next)
        assertEquals("dom", next!!.obstacleName)
        assertTrue(next.obstacleHeightLabel.contains("12.0 m"))
        val months = service.year(2026)
        assertEquals(12, months.size)
        assertTrue(months.first { it.month.month == Month.DECEMBER }.lossKwh > months.first { it.month.month == Month.JUNE }.lossKwh)
    }

    @Test
    fun parsesGeocodingElevationAndOverpass() {
        val nominatim = """[{"lat":"52.2319","lon":"21.0067","display_name":"Marszałkowska 1, Warszawa","addresstype":"house"},
            {"lat":"52.23","lon":"21.01","display_name":"00-001 Warszawa","addresstype":"postcode"}]"""
        val r = NominatimLocationProvider.parse(nominatim)
        assertEquals(LocationAccuracy.ADDRESS, r[0].accuracy)
        assertEquals(LocationAccuracy.POSTCODE, r[1].accuracy)
        assertEquals(52.2319, r[0].point.lat, 1e-9)
        val om = """{"results":[{"name":"Kraków","latitude":50.06,"longitude":19.94,"elevation":219.0,"timezone":"Europe/Warsaw","admin1":"Małopolskie","country":"Polska"}]}"""
        val c = OpenMeteoGeocodingProvider.parse(om).single()
        assertEquals(LocationAccuracy.CITY, c.accuracy)
        assertEquals(219.0, c.elevationM!!, 0.0)
        assertEquals("Kraków, Małopolskie, Polska", c.name)
        assertTrue(OpenMeteoGeocodingProvider.parse("{}").isEmpty())
        assertEquals(listOf(101.5, null), OpenMeteoTerrainProvider.parse("""{"elevation":[101.5,-9999]}""", 2))
        val overpass = """{"elements":[
          {"type":"way","id":1,"tags":{"building":"yes","building:levels":"3"},"geometry":[{"lat":52.1,"lon":21.0},{"lat":52.1,"lon":21.001},{"lat":52.101,"lon":21.001},{"lat":52.1,"lon":21.0}]},
          {"type":"node","id":2,"lat":52.1,"lon":21.0,"tags":{"natural":"tree","height":"14","leaf_cycle":"evergreen"}},
          {"type":"node","id":3,"lat":52.1,"lon":21.0,"tags":{"man_made":"chimney"}},
          {"type":"node","id":4,"lat":52.1,"lon":21.0,"tags":{"amenity":"bench"}}]}"""
        val obs = OverpassBuildingProvider.parse(overpass, Instant.EPOCH)
        assertEquals(listOf(ObstacleType.BUILDING, ObstacleType.TREE, ObstacleType.CHIMNEY), obs.map { it.type })
        assertEquals(HeightSource.FROM_LEVELS, obs[0].height.source)
        assertTrue(obs[0].shape is ObstacleShape.Polygon)
        assertEquals(0.15, obs[1].foliage!!.transmittanceLeafOff, 0.0)
        assertEquals(HeightSource.UNKNOWN, obs[2].height.source)
        val fake = FakeMapLocationProvider(mapOf("warszawa" to r))
        assertEquals(2, fake.search(" Warszawa ").size)
        assertEquals(1, FakeBuildingDataProvider(obs).obstaclesAround(origin, 100).count { it.type == ObstacleType.TREE })
    }

    @Test
    fun obstacleCacheRoundTrip() {
        val o = listOf(
            building("a", 0.0, -20.0, 10.0).copy(history = listOf(ObstacleChange(Instant.parse("2026-10-05T10:00:00Z"), "dodano"))),
            Obstacle("t", ObstacleType.TREE, ObstacleShape.Point(origin, 3.0), HeightValue.fromLevels(2.0), foliage = Foliage(crownBaseM = 2.0), source = "u", userDefined = true),
            Obstacle("w", ObstacleType.FENCE, ObstacleShape.Line(listOf(origin, at(5.0, 0.0)), 0.3), HeightValue.UNKNOWN, source = "osm", enabled = false),
            Obstacle("m", ObstacleType.OTHER, ObstacleShape.Bearing(30.0, 170.0, 190.0), HeightValue(6.0, HeightSource.USER_ESTIMATED, 1.0), source = "u", overridesId = "x"),
        )
        assertEquals(o, ObstacleCodec.decode(ObstacleCodec.encode(o)))
        assertTrue(ObstacleCodec.decode("{\"version\":1}").isEmpty())
        val terrain = TerrainSamples(100.0, listOf(0.0, 90.0), listOf(100.0), listOf(listOf(101.0), listOf(null)), "x", 30.0)
        assertEquals(terrain, ObstacleCodec.decodeTerrain(ObstacleCodec.encodeTerrain(terrain)))
    }

    @Test
    fun azimuthRanges() {
        assertTrue(ShadingAnalysisEngine.azimuthWithin(5.0, 350.0, 20.0))
        assertFalse(ShadingAnalysisEngine.azimuthWithin(180.0, 350.0, 20.0))
        assertTrue(ShadingAnalysisEngine.azimuthWithin(348.0, 350.0, 20.0, margin = 3.0))
        val (from, to) = ObstacleGeometryService.angularRange(listOf(350.0, 10.0, 355.0))
        assertEquals(350.0, from, 0.0)
        assertEquals(10.0, to, 0.0)
        assertEquals(0, ZoneOffset.UTC.totalSeconds)
    }
}
