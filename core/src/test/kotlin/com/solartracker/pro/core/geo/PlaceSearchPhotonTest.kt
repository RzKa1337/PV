package com.solartracker.pro.core.geo

import com.solartracker.pro.core.update.HttpClient
import com.solartracker.pro.core.update.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photon (OSM) search, typed coordinates / map links and the provider fallback chain. */
class PlaceSearchPhotonTest {
    // Shape of https://photon.komoot.io/api/?q=atacama (GeoJSON, coordinates [lon, lat]); values typed for the test.
    private val photonAtacama = """{"type":"FeatureCollection","features":[
      {"type":"Feature","geometry":{"type":"Point","coordinates":[-69.25,-24.5]},
       "properties":{"osm_type":"R","osm_id":123,"name":"Desierto de Atacama","country":"Chile","state":"Región de Antofagasta","osm_key":"natural","osm_value":"desert","type":"other"}},
      {"type":"Feature","geometry":{"type":"Point","coordinates":[-70.0,-27.37]},
       "properties":{"osm_type":"R","osm_id":456,"name":"Región de Atacama","country":"Chile","type":"state"}},
      {"type":"Feature","geometry":{"type":"Point","coordinates":[-69.2501,-24.5001]},
       "properties":{"osm_type":"W","osm_id":789,"name":"Desierto de Atacama","country":"Chile"}},
      {"type":"Feature","geometry":{"type":"Point","coordinates":[21.0,52.2]},
       "properties":{"osm_type":"N","osm_id":1,"street":"Marszałkowska","housenumber":"1","postcode":"00-624","city":"Warszawa","country":"Polska"}},
      {"type":"Feature","geometry":{"type":"Point","coordinates":["x",1]},"properties":{"name":"Broken"}}]}"""

    private class Fake(val code: Int, val body: String) : HttpClient {
        var calls = 0
        var lastUrl = ""
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            calls++; lastUrl = url
            return HttpResponse(code, emptyMap(), body.byteInputStream())
        }
    }

    @Test
    fun photonFindsNaturalFeaturesRegionsAndAddresses() {
        val http = Fake(200, photonAtacama)
        val s = PhotonPlaceProvider(http, "test", bias = com.solartracker.pro.core.shading.LatLon(52.2, 21.0)).suggest("atacama", "s", 6)
        assertEquals(listOf("Desierto de Atacama", "Región de Atacama", "Marszałkowska 1"), s.map { it.primary }) // duplicate + broken dropped
        assertEquals("Región de Antofagasta, Chile", s[0].secondary)
        assertEquals(-24.5, s[0].point!!.lat, 1e-9)
        assertEquals(-69.25, s[0].point!!.lon, 1e-9) // [lon, lat] order respected
        assertEquals("00-624, Warszawa, Polska", s[2].secondary)
        assertEquals(listOf(12 until 19), s[0].primaryMatches)
        assertTrue(http.lastUrl.contains("q=atacama") && http.lastUrl.contains("lat=52.2000") && http.lastUrl.contains("lon=21.0000"))
    }

    @Test
    fun coordinatesAndMapLinksAreRecognised() {
        fun p(t: String) = CoordinateParser.parse(t)?.point?.let { it.lat to it.lon }
        assertEquals(52.23 to 21.01, p("52.23, 21.01"))
        assertEquals(-24.5 to -69.25, p("-24.5 -69.25"))
        assertEquals(52.23 to 21.01, p("52,23, 21,01"))
        assertEquals(-24.5 to -69.25, p("24.5S, 69.25W"))
        // 52°13'48" N 21°0'36" E = 52 + 13/60 + 48/3600 = 52.23, 21 + 0 + 36/3600 = 21.01
        val dms = p("52°13'48\"N 21°0'36\"E")!!
        assertEquals(52.23, dms.first, 1e-9); assertEquals(21.01, dms.second, 1e-9)
        assertEquals(-24.5 to -69.25, p("https://www.google.com/maps/place/Atacama/@-24.5,-69.25,8z/data=!3m1"))
        assertEquals(-24.5 to -69.25, p("https://www.google.com/maps/place/x/data=!4m2!3d-24.5!4d-69.25"))
        assertEquals(52.23 to 21.01, p("https://maps.google.com/?q=52.23,21.01"))
        assertEquals(52.23 to 21.01, p("https://www.openstreetmap.org/?mlat=52.23&mlon=21.01#map=12/52.23/21.01"))
        assertEquals(52.23 to 21.01, p("geo:52.23,21.01"))
        // Not coordinates: names, postcodes, out-of-range numbers.
        assertNull(p("Atakama Chile"))
        assertNull(p("00-001"))
        assertNull(p("95, 200"))
    }

    @Test
    fun searcherUsesCoordinatesWithoutNetworkAndFallsBackWhenAProviderFindsNothing() {
        val photon = Fake(200, """{"type":"FeatureCollection","features":[]}""")
        val om = Fake(200, """{"results":[{"id":1,"name":"Atacama","latitude":-27.0,"longitude":-70.0,"country":"Chile"}]}""")
        val searcher = PlaceSearcher(listOf(PhotonPlaceProvider(photon, "t"), OpenMeteoPlaceProvider(om, "t")))
        val coords = searcher.suggest("-24.5, -69.25", "s")
        assertEquals(PlaceSource.COORDINATES, coords.source)
        assertEquals(0, photon.calls + om.calls)
        assertEquals(-69.25, searcher.resolve(coords.suggestions.single(), "s").point.lon, 1e-9)
        // Photon empty → Open-Meteo asked.
        val r = searcher.suggest("Atacama", "s")
        assertEquals(PlaceSource.OPEN_METEO, r.source)
        assertEquals("Atacama", r.suggestions.single().primary)
        // Both empty → an empty result (not an error).
        val none = PlaceSearcher(listOf(PhotonPlaceProvider(Fake(200, """{"features":[]}"""), "t"), OpenMeteoPlaceProvider(Fake(200, "{}"), "t"))).suggest("qwxz", "s")
        assertTrue(none.suggestions.isEmpty())
    }
}
