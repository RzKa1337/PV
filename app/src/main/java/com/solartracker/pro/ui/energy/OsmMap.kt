package com.solartracker.pro.ui.energy

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
import com.solartracker.pro.core.shading.LatLon
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** A polygon/line/point drawn on the map. Colors are ARGB ints. */
data class MapShape(val points: List<LatLon>, val stroke: Int, val fill: Int, val closed: Boolean = true, val width: Float = 3f)

/**
 * OpenStreetMap view (osmdroid) with the installation marker and overlays. Tiles are cached in the
 * app's cache directory; [onTap] receives map taps (to pick a location or draw an obstacle).
 */
@Composable
fun OsmMap(
    center: LatLon,
    marker: LatLon?,
    shapes: List<MapShape>,
    modifier: Modifier = Modifier,
    zoom: Double = 18.0,
    onTap: ((LatLon) -> Unit)? = null,
) {
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
            controller.setZoom(zoom)
            controller.setCenter(GeoPoint(center.lat, center.lon))
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
        map.overlays.clear()
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onTap?.invoke(LatLon(p.latitude, p.longitude))
                return onTap != null
            }

            override fun longPressHelper(p: GeoPoint) = false
        }))
        shapes.forEach { s ->
            val pts = s.points.map { GeoPoint(it.lat, it.lon) }
            if (s.closed && pts.size >= 3) {
                map.overlays.add(Polygon(map).apply {
                    setPoints(pts + pts.first())
                    outlinePaint.color = s.stroke
                    outlinePaint.strokeWidth = s.width
                    fillPaint.color = s.fill
                    setOnClickListener { _, _, _ -> false }
                })
            } else if (pts.size >= 2) {
                map.overlays.add(Polyline(map).apply {
                    setPoints(pts)
                    outlinePaint.color = s.stroke
                    outlinePaint.strokeWidth = s.width
                })
            }
        }
        marker?.let {
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(it.lat, it.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Instalacja PV"
            })
        }
        map.invalidate()
    })
}

object MapColors {
    val PANELS = Color.argb(255, 25, 118, 210) to Color.argb(90, 25, 118, 210)
    val CONFIRMED = Color.argb(255, 46, 125, 50) to Color.argb(70, 46, 125, 50)
    val ESTIMATED = Color.argb(255, 245, 124, 0) to Color.argb(70, 245, 124, 0)
    val UNKNOWN = Color.argb(255, 211, 47, 47) to Color.argb(60, 211, 47, 47)
    val TREE = Color.argb(255, 56, 142, 60) to Color.argb(80, 129, 199, 132)
    val SHADOW = Color.argb(0, 0, 0, 0) to Color.argb(90, 0, 0, 0)
    val SUN = Color.argb(255, 255, 179, 0) to Color.argb(0, 0, 0, 0)
    val DRAFT = Color.argb(255, 142, 36, 170) to Color.argb(60, 142, 36, 170)
}
