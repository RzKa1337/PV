package com.solartracker.pro.ui.radar

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.solartracker.pro.BuildConfig
import com.solartracker.pro.core.radar.RadarFrame
import com.solartracker.pro.core.radar.RainViewer
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/** OpenStreetMap base with the radar frame as a tile overlay and the installation marker. */
@Composable
fun RadarMap(lat: Double, lon: Double, frame: RadarFrame?, maxZoom: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = "SolarTrackerPRO/${BuildConfig.VERSION_NAME} (Android)"
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            // Radar tiles exist up to maxZoom; allow a little over-zoom for orientation only.
            maxZoomLevel = (maxZoom + 3).toDouble()
            controller.setZoom(maxZoom.toDouble())
            controller.setCenter(GeoPoint(lat, lon))
            tag = arrayOfNulls<Any>(2) // [radar overlay, its provider]
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier, update = { map ->
        @Suppress("UNCHECKED_CAST")
        val held = map.tag as Array<Any?>
        (held[0] as? TilesOverlay)?.let { map.overlays.remove(it) }
        (held[1] as? MapTileProviderBasic)?.detach()
        held[0] = null; held[1] = null
        map.overlays.removeAll { it is Marker }
        if (frame != null) {
            val source = object : OnlineTileSourceBase("RainViewer-${frame.time.epochSecond}", 0, maxZoom, 256, ".png", arrayOf("https://tilecache.rainviewer.com/")) {
                override fun getTileURLString(index: Long): String =
                    RainViewer.tileUrl(frame, MapTileIndex.getZoom(index), MapTileIndex.getX(index).toLong(), MapTileIndex.getY(index).toLong())
            }
            val provider = MapTileProviderBasic(context, source)
            val overlay = TilesOverlay(provider, context).apply {
                loadingBackgroundColor = Color.TRANSPARENT
                loadingLineColor = Color.TRANSPARENT
            }
            map.overlays.add(overlay)
            held[0] = overlay; held[1] = provider
        }
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(lat, lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Instalacja PV"
        })
        map.invalidate()
    })
}
