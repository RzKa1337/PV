package com.solartracker.pro.core.shading

/** Test doubles for map, terrain and building data (no network). */
class FakeMapLocationProvider(private val results: Map<String, List<GeocodeResult>>) : MapLocationProvider {
    val queries = mutableListOf<String>()
    override fun search(query: String, limit: Int): List<GeocodeResult> {
        queries += query
        return results[query.trim().lowercase()].orEmpty().take(limit)
    }
}

class FakeTerrainDataProvider(private val elevation: (LatLon) -> Double?) : TerrainDataProvider {
    var calls = 0
    override val sourceName = "fake DEM"
    override val resolutionM = 30.0
    override fun elevations(points: List<LatLon>): List<Double?> {
        calls++
        return points.map(elevation)
    }
}

class FakeBuildingDataProvider(private val obstacles: List<Obstacle>) : BuildingDataProvider {
    override val sourceName = "fake buildings"
    override fun obstaclesAround(center: LatLon, radiusM: Int): List<Obstacle> = obstacles
}
