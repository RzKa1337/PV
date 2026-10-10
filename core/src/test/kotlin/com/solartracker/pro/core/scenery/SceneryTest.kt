package com.solartracker.pro.core.scenery

import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.update.HttpClient
import com.solartracker.pro.core.update.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SceneryTest {
    /** Shape of a MediaWiki API answer (formatversion=2, generator=search, prop=imageinfo with extmetadata). */
    private fun page(
        index: Int,
        title: String,
        license: String,
        artist: String? = "<a href=\"//commons.wikimedia.org/wiki/User:Jan\">Jan Kowalski</a>",
        mime: String = "image/jpeg",
        width: Int = 4000,
        height: Int = 2667,
    ) = """
        {"pageid":${1000 + index},"ns":6,"title":"$title","index":$index,"imagerepository":"local",
         "imageinfo":[{"size":5123456,"width":$width,"height":$height,
           "thumburl":"https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/${title.removePrefix("File:")}/1920px-${title.removePrefix("File:")}",
           "thumbwidth":1920,"thumbheight":${height * 1920 / width},
           "url":"https://upload.wikimedia.org/wikipedia/commons/a/ab/${title.removePrefix("File:")}",
           "descriptionurl":"https://commons.wikimedia.org/wiki/${title.replace(' ', '_')}",
           "mime":"$mime",
           "extmetadata":{
             "LicenseShortName":{"value":"$license","source":"commons-desc-page","hidden":""},
             ${if (artist != null) "\"Artist\":{\"value\":\"${artist.replace("\"", "\\\"")}\",\"source\":\"commons-desc-page\"}," else ""}
             "LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0","source":"commons-desc-page","hidden":""}
           }}]}
    """.trimIndent()

    private fun answer(vararg pages: String) = """{"batchcomplete":true,"query":{"pages":[${pages.joinToString(",")}]}}"""

    @Test
    fun parse_keepsFreelyLicensedJpegsInSearchOrderWithCredit() {
        val photos = WikimediaPhotoSource.parse(
            answer(
                page(2, "File:Oia sunset.jpg", "CC BY-SA 4.0"),
                page(1, "File:Santorini caldera.jpg", "CC0"),
                page(3, "File:Logo.png", "CC0", mime = "image/png"),
                page(4, "File:Tiny.jpg", "CC BY 2.0", width = 800, height = 600),
                page(5, "File:Noncommercial.jpg", "CC BY-NC-SA 2.0"),
                page(6, "File:NoDerivs.jpg", "CC BY-ND 4.0"),
                page(7, "File:Gfdl.jpg", "GFDL"),
                page(8, "File:Anonymous.jpg", "CC BY-SA 3.0", artist = null),
                page(9, "File:Old postcard.jpg", "Public domain", artist = null),
            ),
        )
        assertEquals(listOf("File:Santorini caldera.jpg", "File:Oia sunset.jpg", "File:Old postcard.jpg"), photos.map { it.title })
        val oia = photos[1]
        assertEquals("Jan Kowalski", oia.author)
        assertEquals("CC BY-SA 4.0", oia.license)
        assertEquals("Jan Kowalski · CC BY-SA 4.0 · Wikimedia Commons", oia.credit)
        assertEquals(1920, oia.width)
        assertTrue(oia.imageUrl.startsWith("https://upload.wikimedia.org/") && "1920px-" in oia.imageUrl)
        assertEquals("https://commons.wikimedia.org/wiki/File:Oia_sunset.jpg", oia.pageUrl)
        assertEquals("—", photos[2].author)
    }

    @Test
    fun parse_brokenOrEmptyAnswersGiveNoPhotos() {
        assertTrue(WikimediaPhotoSource.parse("not json").isEmpty())
        assertTrue(WikimediaPhotoSource.parse("""{"batchcomplete":true}""").isEmpty())
        assertTrue(WikimediaPhotoSource.parse("""{"query":{"pages":[{"title":"File:x.jpg"}]}}""").isEmpty())
    }

    @Test
    fun licenses() {
        listOf("CC0", "CC BY 2.0", "CC BY-SA 3.0", "CC-BY-SA-4.0", "Public domain", "PD-self").forEach { assertTrue(it, WikimediaPhotoSource.isFreeLicense(it)) }
        listOf("CC BY-NC 2.0", "CC BY-NC-SA 2.0", "CC BY-ND 4.0", "GFDL", "All rights reserved", "").forEach { assertFalse(it, WikimediaPhotoSource.isFreeLicense(it)) }
    }

    @Test
    fun plainText_stripsHtmlAndEntities() {
        assertEquals("A & B 'C'", WikimediaPhotoSource.plainText("<span class=\"x\"><a href=\"y\">A</a> &amp; B</span> &#039;C&#039;"))
    }

    @Test
    fun searchUrl_isEncodedAndAsksForThumbnailsAndLicenses() {
        val url = WikimediaPhotoSource(HttpClient { _, _ -> error("unused") }, "ua")
            .searchUrl(SceneTheme.COAST.searchText("Didim Altınkum beach", DayPhase.SUNSET), 1280)
        assertTrue(url.startsWith("https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search"))
        assertTrue("gsrsearch=Didim%20Alt%C4%B1nkum%20beach%20sunset%20incategory%3AQuality_images%20filetype%3Abitmap" in url)
        assertTrue("iiurlwidth=1280" in url && "LicenseShortName" in url && "gsrnamespace=6" in url)
    }

    @Test
    fun search_sendsUserAgentAndReportsHttpErrors() {
        var headers: Map<String, String> = emptyMap()
        val ok = WikimediaPhotoSource(HttpClient { _, h -> headers = h; HttpResponse(200, emptyMap(), answer(page(1, "File:A.jpg", "CC0")).byteInputStream()) }, "SolarTrackerPRO/test")
        assertEquals(1, ok.search("x", 1920).size)
        assertEquals("SolarTrackerPRO/test", headers["User-Agent"])
        val limited = WikimediaPhotoSource(HttpClient { _, _ -> HttpResponse(429, emptyMap(), "".byteInputStream()) }, "ua")
        val e = runCatching { limited.search("x", 1920) }.exceptionOrNull()
        assertTrue(e is ScenerySearchException)
        val offline = WikimediaPhotoSource(HttpClient { _, _ -> throw java.net.UnknownHostException("commons.wikimedia.org") }, "ua")
        assertTrue(runCatching { offline.search("x", 1920) }.exceptionOrNull() is ScenerySearchException)
    }

    @Test
    fun searchText_phaseWords() {
        assertEquals("Atacama desert incategory:Quality_images filetype:bitmap", SceneTheme.DESERT.searchText("Atacama desert", DayPhase.DAY))
        assertEquals("Atacama desert night incategory:Quality_images filetype:bitmap", SceneTheme.DESERT.searchText("Atacama desert", DayPhase.NIGHT))
        assertEquals("solar park incategory:Quality_images filetype:bitmap", SceneTheme.SOLAR.searchText("solar park", DayPhase.NIGHT))
    }

    @Test
    fun dayPhase_followsTheSunAtTheLocation() {
        val zg = GeoLocation(51.94, 15.50)
        val zone = ZoneId.of("Europe/Warsaw")
        fun at(h: Int, m: Int = 0) = DayPhase.at(zg, ZonedDateTime.of(2026, 10, 13, h, m, 0, 0, zone).toInstant())
        // Sunrise ≈ 07:19, sunset ≈ 18:09 local time on 13 Oct 2026.
        assertEquals(DayPhase.NIGHT, at(3))
        assertEquals(DayPhase.DAWN, at(7, 30))
        assertEquals(DayPhase.DAY, at(12, 30))
        assertEquals(DayPhase.AFTERNOON, at(16))
        assertEquals(DayPhase.SUNSET, at(18, 0))
        assertEquals(DayPhase.NIGHT, at(22))
    }

    @Test
    fun picker_isStablePerSeedAndChangesWithANewSeed() {
        val photos = (1..12).map { ScenicPhoto("File:$it.jpg", "https://u/$it", 1920, 1280, "https://p/$it", "a", "CC0", null) }
        val a = SceneryPicker.pick(42L, "DASHBOARD", photos)
        assertEquals(a, SceneryPicker.pick(42L, "DASHBOARD", photos))
        val others = (1L..20L).map { SceneryPicker.pick(42L + it, "DASHBOARD", photos) }.toSet()
        assertTrue("a new seed must be able to give another photo", others.size > 3)
        val b = SceneryPicker.pick(42L, "LIVE", photos, avoid = setOf(a!!.title))
        assertNotEquals(a, b)
        assertNull(SceneryPicker.pick(1L, "X", emptyList()))
        assertEquals(SceneryPicker.queryFor(7L, SceneTheme.COAST, 0), SceneryPicker.queryFor(7L, SceneTheme.COAST, 0))
        assertTrue(SceneryPicker.queryFor(7L, SceneTheme.COAST, 1) in SceneTheme.COAST.queries)
    }

    @Test
    fun codec_roundTrip() {
        val list = WikimediaPhotoSource.parse(answer(page(1, "File:A b.jpg", "CC BY-SA 4.0"), page(2, "File:C.jpg", "CC0")))
        assertEquals(list, ScenicPhotoCodec.decode(ScenicPhotoCodec.encode(list)))
        assertTrue(ScenicPhotoCodec.decode("garbage").isEmpty())
        assertTrue(ScenicPhotoCodec.decode(null).isEmpty())
    }
}
