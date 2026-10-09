package com.solartracker.pro.core.geo

import com.solartracker.pro.core.update.HttpClient
import com.solartracker.pro.core.update.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant

class PlaceSearchTest {
    /** Records requests and answers from a script; responses are recorded provider formats, not invented places. */
    private class FakeHttp(private val answer: (method: String, url: String, headers: Map<String, String>, body: String?) -> Pair<Int, String>) : HttpClient {
        val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
        var lastBody: String? = null
        override fun get(url: String, headers: Map<String, String>): HttpResponse = respond("GET", url, headers, null)
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse = respond("POST", url, headers, body)
        private fun respond(m: String, url: String, h: Map<String, String>, body: String?): HttpResponse {
            calls += Triple(m, url, h); lastBody = body
            val (code, text) = answer(m, url, h, body)
            return HttpResponse(code, emptyMap(), text.byteInputStream())
        }
    }

    // Shape of https://geocoding-api.open-meteo.com/v1/search?name=Warsz&language=pl (fields used by the app).
    private val openMeteoWarsz = """{"results":[
        {"id":756135,"name":"Warszawa","latitude":52.22977,"longitude":21.01178,"elevation":113.0,"timezone":"Europe/Warsaw","admin1":"Mazowieckie","country":"Polska"},
        {"id":3082707,"name":"Warszowice","latitude":49.9,"longitude":18.7,"elevation":260.0,"timezone":"Europe/Warsaw","admin1":"Śląskie","country":"Polska"},
        {"id":1,"name":"Broken","latitude":"x"}],"generationtime_ms":0.5}"""

    // Shape of POST https://places.googleapis.com/v1/places:autocomplete (Places API New).
    private val googleWarsz = """{"suggestions":[
        {"placePrediction":{"placeId":"ChIJAZ-GmmbMHkcR_NPqiCq-8HI","text":{"text":"Warszawa, Polska","matches":[{"endOffset":5}]},
         "structuredFormat":{"mainText":{"text":"Warszawa","matches":[{"endOffset":5}]},"secondaryText":{"text":"Polska"}}}},
        {"queryPrediction":{"text":{"text":"warsztat"}}}]}"""

    @Test
    fun textNormalisationAndDiacriticsInsensitiveHighlight() {
        assertNull(PlaceText.normalize(" W "))
        assertEquals("Nowy Sącz", PlaceText.normalize("  Nowy   Sącz "))
        assertEquals(listOf(0 until 4), PlaceText.matches("Łódź", "lodz"))
        assertEquals(listOf(5 until 8), PlaceText.matches("Nowy Sącz", "sąc"))
        // A word start wins over a match inside a word.
        assertEquals(listOf(12 until 15), PlaceText.matches("Pogorze nad Gor", "gor"))
        assertEquals(emptyList<IntRange>(), PlaceText.matches("Kraków", "xyz"))
        assertEquals("zurich", PlaceText.fold("Zürich"))
    }

    @Test
    fun openMeteoSuggestionsCarryRegionCountryAndCoordinates() {
        val http = FakeHttp { _, _, _, _ -> 200 to openMeteoWarsz }
        val s = OpenMeteoPlaceProvider(http, "test").suggest("Warsz", "s1", 6)
        assertEquals(2, s.size) // the malformed entry is dropped, nothing is made up
        assertEquals("Warszawa", s[0].primary)
        assertEquals("Mazowieckie, Polska", s[0].secondary)
        assertEquals(listOf(0 until 5), s[0].primaryMatches)
        assertEquals(52.22977, s[0].point!!.lat, 1e-9)
        assertEquals(113.0, s[0].elevationM!!, 0.0)
        assertTrue(http.calls.single().second.contains("name=Warsz") && http.calls.single().second.contains("language=pl"))
        val r = OpenMeteoPlaceProvider(http, "test").resolve(s[0], "s1")
        assertEquals("Europe/Warsaw", r.timezone)
        assertEquals(21.01178, r.point.lon, 1e-9)
    }

    @Test
    fun emptyAndErrorResponses() {
        assertTrue(OpenMeteoPlaceProvider.parse("""{"generationtime_ms":0.3}""", "qq").isEmpty())
        try { OpenMeteoPlaceProvider.parse("<html>", "qq"); fail() } catch (e: PlaceSearchException) { assertEquals(PlaceErrorKind.BAD_RESPONSE, e.kind) }
        val offline = HttpClient { _, _ -> throw IOException("Unable to resolve host") }
        try { OpenMeteoPlaceProvider(offline, "t").suggest("Warsz", "s", 6); fail() } catch (e: PlaceSearchException) { assertEquals(PlaceErrorKind.NETWORK, e.kind) }
        val limited = FakeHttp { _, _, _, _ -> 429 to "" }
        try { OpenMeteoPlaceProvider(limited, "t").suggest("Warsz", "s", 6); fail() } catch (e: PlaceSearchException) { assertEquals(PlaceErrorKind.QUOTA, e.kind) }
    }

    @Test
    fun googleAutocompleteAndDetailsWithAndroidRestrictionHeaders() {
        val http = FakeHttp { m, url, _, _ ->
            if (m == "POST") 200 to googleWarsz
            else { assertTrue(url.contains("/v1/places/ChIJAZ-GmmbMHkcR_NPqiCq-8HI")); 200 to """{"location":{"latitude":52.2296756,"longitude":21.0122287},"formattedAddress":"Warszawa, Polska"}""" }
        }
        val g = GooglePlacesProvider(http, "KEY123", "com.solartracker.pro", "ABCDEF0123")
        val s = g.suggest("Warsz", "sess-1", 6)
        assertEquals(1, s.size) // query predictions are not places
        assertEquals("Warszawa", s[0].primary)
        assertEquals("Polska", s[0].secondary)
        assertEquals(listOf(0 until 5), s[0].primaryMatches)
        assertNull(s[0].point)
        val (method, url, headers) = http.calls[0]
        assertEquals("POST", method)
        assertTrue(url.endsWith("/v1/places:autocomplete"))
        assertEquals("KEY123", headers["X-Goog-Api-Key"])
        assertEquals("com.solartracker.pro", headers["X-Android-Package"])
        assertEquals("ABCDEF0123", headers["X-Android-Cert"])
        assertFalse("key never in the URL", url.contains("KEY123"))
        assertTrue(http.lastBody!!.contains("\"sessionToken\":\"sess-1\"") && http.lastBody!!.contains("\"input\":\"Warsz\""))
        val r = g.resolve(s[0], "sess-1")
        assertEquals(52.2296756, r.point.lat, 1e-9)
        assertEquals("location,formattedAddress", http.calls[1].third["X-Goog-FieldMask"])
        assertTrue(http.calls[1].second.contains("sessionToken=sess-1"))
    }

    @Test
    fun googleErrorsAreClassifiedWithoutLeakingTheKey() {
        val denied = GooglePlacesProvider.errorFor(403, """{"error":{"code":403,"message":"Requests from this Android client application <empty> are blocked.","status":"PERMISSION_DENIED"}}""")
        assertEquals(PlaceErrorKind.KEY_REJECTED, denied.kind)
        assertEquals(PlaceErrorKind.QUOTA, GooglePlacesProvider.errorFor(429, "{}").kind)
        assertEquals(PlaceErrorKind.KEY_REJECTED, GooglePlacesProvider.errorFor(400, """{"error":{"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""").kind)
        assertEquals(PlaceErrorKind.BAD_RESPONSE, GooglePlacesProvider.errorFor(500, "oops").kind)
        try { GooglePlacesProvider(FakeHttp { _, _, _, _ -> 200 to "" }, " ", null, null); fail() } catch (e: PlaceSearchException) { assertEquals(PlaceErrorKind.KEY_MISSING, e.kind) }
    }

    @Test
    fun searcherFallsBackWhenGoogleRejectsAndCachesAnswers() {
        val googleHttp = FakeHttp { _, _, _, _ -> 403 to """{"error":{"status":"PERMISSION_DENIED"}}""" }
        val omHttp = FakeHttp { _, _, _, _ -> 200 to openMeteoWarsz }
        var now = Instant.parse("2026-10-09T12:00:00Z")
        val searcher = PlaceSearcher(listOf(GooglePlacesProvider(googleHttp, "K", null, null), OpenMeteoPlaceProvider(omHttp, "t")), clock = { now })
        val r = searcher.suggest("Warsz", "s")
        assertEquals(PlaceSource.OPEN_METEO, r.source)
        assertEquals(PlaceErrorKind.KEY_REJECTED, r.notice!!.kind)
        assertEquals("Warszawa", r.suggestions.first().primary)
        // Same text (other case / spaces) within 10 min: from cache, no new requests.
        searcher.suggest("  warsz ", "s")
        assertEquals(1, omHttp.calls.size)
        now = now.plusSeconds(11 * 60)
        searcher.suggest("Warsz", "s")
        assertEquals(2, omHttp.calls.size)
        // Too short: no request at all.
        assertTrue(searcher.suggest("W", "s").suggestions.isEmpty())
        assertEquals(2, omHttp.calls.size)
    }

    @Test
    fun allProvidersOfflineGivesNetworkError() {
        val offline = HttpClient { _, _ -> throw IOException("offline") }
        try { PlaceSearcher(listOf(OpenMeteoPlaceProvider(offline, "t"))).suggest("Kraków", "s"); fail() }
        catch (e: PlaceSearchException) { assertEquals(PlaceErrorKind.NETWORK, e.kind) }
    }

    @Test
    fun recentPlacesDeduplicateAndSurviveDamagedData() {
        val waw = SavedPlace("Warszawa", "Mazowieckie, Polska", 52.2298, 21.0118, 113.0, "OPEN_METEO")
        val krk = SavedPlace("Kraków", "Małopolskie, Polska", 50.0614, 19.9366, null, "GOOGLE")
        var list = RecentPlaces.push(emptyList(), waw)
        list = RecentPlaces.push(list, krk)
        list = RecentPlaces.push(list, waw.copy(lat = 52.2297, name = "Warsaw")) // same place, other spelling
        assertEquals(listOf("Warsaw", "Kraków"), list.map { it.name })
        val decoded = RecentPlaces.decode(RecentPlaces.encode(list))
        assertEquals(list, decoded)
        assertTrue(RecentPlaces.decode("not json").isEmpty())
        assertTrue(RecentPlaces.decode("""[{"name":"X","lat":95,"lon":0}]""").isEmpty())
        var many = emptyList<SavedPlace>()
        repeat(10) { i -> many = RecentPlaces.push(many, SavedPlace("P$i", "", 40.0 + i, 10.0, null, "")) }
        assertEquals(RecentPlaces.MAX, many.size)
        assertEquals("P9", many.first().name)
    }
}
