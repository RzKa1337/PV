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

/** Radar overlays kept per frame: switching frames (slider/animation) only toggles visibility – no reload, no flicker. */
private class RadarLayers {
    val byTime = LinkedHashMap<Long, Pair<TilesOverlay, MapTileProviderBasic>>()
    var shown: Long? = null
    var marker: Marker? = null
}

/** OpenStreetMap base with the radar frames as tile overlays and the installation marker. */
@Composable
fun RadarMap(lat: Double, lon: Double, frames: List<RadarFrame>, frame: RadarFrame?, maxZoom: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val layers = remember { RadarLayers() }
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = "SolarTrackerPRO/${BuildConfig.VERSION_NAME} (Android)"
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            // Radar tiles exist up to maxZoom; a little over-zoom for orientation only.
            maxZoomLevel = (maxZoom + 3).toDouble()
            controller.setZoom(maxZoom.toDouble())
            controller.setCenter(GeoPoint(lat, lon))
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
            layers.byTime.values.forEach { it.second.detach() }
            layers.byTime.clear()
            mapView.onDetach()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier, update = { map ->
        // Drop overlays of frames that left the provider's index.
        val keep = frames.map { it.time.epochSecond }.toSet()
        layers.byTime.keys.filter { it !in keep }.forEach { t ->
            layers.byTime.remove(t)?.let { (o, p) -> map.overlays.remove(o); p.detach() }
        }
        val target = frame?.time?.epochSecond
        if (target != layers.shown) {
            layers.byTime.values.forEach { it.first.isEnabled = false }
            if (frame != null && target != null) {
                val (overlay, _) = layers.byTime.getOrPut(target) {
                    val source = object : OnlineTileSourceBase("RainViewer-$target", 0, maxZoom, 256, ".png", arrayOf("https://tilecache.rainviewer.com/")) {
                        override fun getTileURLString(index: Long): String =
                            RainViewer.tileUrl(frame, MapTileIndex.getZoom(index), MapTileIndex.getX(index).toLong(), MapTileIndex.getY(index).toLong())
                    }
                    val provider = MapTileProviderBasic(context, source)
                    val o = TilesOverlay(provider, context).apply {
                        loadingBackgroundColor = Color.TRANSPARENT
                        loadingLineColor = Color.TRANSPARENT
                    }
                    map.overlays.add(0.coerceAtLeast(map.overlays.size - (if (layers.marker != null) 1 else 0)), o)
                    o to provider
                }
                overlay.isEnabled = true
            }
            layers.shown = target
        }
        if (layers.marker == null) {
            layers.marker = Marker(map).apply {
                position = GeoPoint(lat, lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "PV"
            }.also { map.overlays.add(it) }
        }
        map.invalidate()
    })
}
