package com.ft8ok.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ft8ok.R
import com.ft8ok.geo.Basemap
import com.ft8ok.geo.GreatCircle
import com.ft8ok.geo.Maidenhead
import com.ft8ok.geo.Mercator
import com.ft8ok.geo.WorldWrap
import com.ft8ok.rx.HeardStations
import com.ft8ok.rx.MapSquares
import com.ft8ok.rx.RxSession
import com.ft8ok.settings.SettingsStore
import com.ft8ok.tx.AutoTx
import com.ft8ok.ui.theme.Tokens
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fixed zoom steps, by how much longitude fills the width: three
 * squares (so the edges of a square show and its label no longer fills it),
 * one field (so a four-character square is about a tenth of the screen), three
 * fields, about 140 degrees, the world.
 */
enum class MapZoom(val spanLonDeg: Double, val label: Int) {
    LOCAL(6.0, R.string.map_zoom_local),
    SQUARES(20.0, R.string.map_zoom_squares),
    AREA(60.0, R.string.map_zoom_area),
    REGION(140.0, R.string.map_zoom_region),
    WORLD(360.0, R.string.map_zoom_world);

    companion object {
        /** The step a free (pinched) span is closest to, by ratio: for the label. */
        fun nearest(span: Double): MapZoom = entries.minBy { abs(ln(it.spanLonDeg / span)) }
    }
}

/** How far back the map looks. Null = the whole session. */
enum class MapWindow(val ms: Long?, val label: Int) {
    MIN5(5 * 60_000L, R.string.map_window_5),
    MIN15(15 * 60_000L, R.string.map_window_15),
    MIN60(60 * 60_000L, R.string.map_window_60),
    SESSION(null, R.string.map_window_session)
}

/**
 * The two outlines, as paths in basemap units (1/perDegree of a degree): longitude
 * as is, latitude already projected to its Mercator northing, once, at load.
 */
private class Outlines(
    val fine: android.graphics.Path,
    val coarse: android.graphics.Path,
    /** Lakes, filled as water over the land (the Great Lakes). */
    val lakes: android.graphics.Path,
    /** State and province lines: stroked, never filled. */
    val admin: android.graphics.Path,
    val perDegree: Int
)

private fun Basemap.toPath(closed: Boolean = true): android.graphics.Path {
    val path = android.graphics.Path()
    path.fillType = android.graphics.Path.FillType.EVEN_ODD
    fun north(lat: Short): Float = (Mercator.y(lat.toDouble() / perDegree) * perDegree).toFloat()
    for (ring in rings) {
        path.moveTo(ring[0].toFloat(), north(ring[1]))
        var i = 2
        while (i < ring.size) {
            path.lineTo(ring[i].toFloat(), north(ring[i + 1]))
            i += 2
        }
        if (closed) path.close()
    }
    return path
}

/**
 * The Map tab: grid squares heard, from what WE decoded on
 * an offline outline, with no tiles and no network (rule 1).
 *
 * Mercator ([Mercator]): shapes are true at every latitude, and Maidenhead squares,
 * being latitude and longitude rectangles, stay rectangles. Two earlier plate carree
 * versions were wrong on the phone: scaled for the view's centre, the map re-stretched
 * on every north-south drag; scaled for our own latitude, land nearer the equator came
 * out squeezed east-west.
 */
@Composable
fun MapScreen(
    session: RxSession,
    settings: SettingsStore,
    autoTx: AutoTx,
    onCall: (RxSession.DecodeRow) -> Unit
) {
    val context = LocalContext.current
    val outlines by produceState<Outlines?>(null) {
        value = withContext(Dispatchers.IO) {
            fun load(name: String) = Basemap.decode(context.assets.open("basemap/$name").use { it.readBytes() })
            val fine = load("countries-50m.bin")
            Outlines(
                fine = fine.toPath(),
                coarse = load("countries-110m.bin").toPath(),
                lakes = load("lakes-50m.bin").toPath(),
                admin = load("admin1-50m.bin").toPath(closed = false),
                perDegree = fine.perDegree
            )
        }
    }

    val myGrid = Maidenhead.square(settings.profile.normalisedGrid)
    val home = Maidenhead.boxOf(myGrid)
    // Degrees of longitude across the width: any value between the outer steps, set by
    // pinching or stepped by the buttons.
    var span by rememberSaveable { mutableDoubleStateOf(MapZoom.AREA.spanLonDeg) }
    var window by rememberSaveable { mutableStateOf(MapWindow.MIN15) }
    var centreLon by rememberSaveable { mutableDoubleStateOf(home?.centreLon ?: 0.0) }
    var centreLat by rememberSaveable { mutableDoubleStateOf(home?.centreLat ?: 20.0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    val now = session.clockTick.takeIf { it > 0 } ?: System.currentTimeMillis()
    // This band only, like the Roster.
    val stations = session.heard.heardSince(window.ms?.let { now - it } ?: Long.MIN_VALUE, session.band)
    val squares = remember(stations) { MapSquares.build(stations) }

    // The partner's square, for the line to them: from the contact, or from what
    // they have sent this session.
    val partner = autoTx.qso
    val partnerGrid = Maidenhead.square(
        partner?.partnerGrid ?: partner?.partner?.let { p -> stations.find { it.call.equals(p, true) }?.grid }
    )
    // The short great circle to them, worked out once per pair of squares.
    val partnerPath = remember(home, partnerGrid) {
        val p = Maidenhead.boxOf(partnerGrid)
        if (home == null || p == null) null
        else GreatCircle.path(home.centreLon, home.centreLat, p.centreLon, p.centreLat)
    }

    val density = LocalDensity.current
    val tapRadiusPx = with(density) { Tokens.Map.TapRadius.toPx() }
    val labelPx = with(density) { Tokens.Strip.LabelSize.toPx() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Tokens.StripColors.Background)
                .padding(horizontal = Tokens.Strip.Padding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (w in MapWindow.entries) Chip(stringResource(w.label), w == window) { window = w }
            Text(
                text = stringResource(R.string.map_counts, stations.count { Maidenhead.square(it.grid) != null }, squares.size),
                color = Tokens.StripColors.Label,
                fontSize = Tokens.Strip.LabelSize,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // The outline is drawn on the native canvas, which Compose does not
                    // clip: without this it spills over the top bar and the chips.
                    .clipToBounds()
                    .background(Tokens.StripColors.Screen)
                    .pointerInput(Unit) {
                        // One finger pans; two pan and pinch, about the point between
                        // them, so whatever is under the fingers stays under them.
                        detectTransformGestures { centroid, pan, zoomBy, _ ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val before = View(w, h, span, centreLon, centreLat)
                            // The point that was under the fingers, before this move...
                            val lon = before.lonAt(centroid.x - pan.x)
                            val north = before.northAt(centroid.y - pan.y)
                            span = (span / zoomBy).coerceIn(MapZoom.LOCAL.spanLonDeg, MapZoom.WORLD.spanLonDeg)
                            // ...goes to where they are now, at the new scale.
                            val pxPerDeg = w / span
                            // East-west the map wraps, so panning rolls over at 180.
                            centreLon = WorldWrap.normalize(lon - (centroid.x - w / 2) / pxPerDeg)
                            centreLat = Mercator.lat(north + (centroid.y - h / 2) / pxPerDeg)
                                .coerceIn(-80.0, 80.0)
                        }
                    }
                    .pointerInput(squares) {
                        detectTapGestures { at ->
                            val v = View(size.width.toFloat(), size.height.toFloat(), span, centreLon, centreLat)
                            // The nearest heard square within a thumb's reach: at the
                            // far zoom steps a square is a few pixels wide.
                            selected = squares.values
                                .flatMap { sq ->
                                    val box = Maidenhead.boxOf(sq.grid)!!
                                    // Every copy of the square on screen is a target.
                                    v.copies.map { k ->
                                        sq.grid to hypot(v.x(box.centreLon + k) - at.x, v.y(box.centreLat) - at.y)
                                    }
                                }
                                .filter { (grid, d) -> d <= tapRadiusPx || grid == v.squareAt(at) }
                                .minByOrNull { it.second }?.first
                        }
                    }
            ) {
                val v = View(size.width, size.height, span, centreLon, centreLat)
                outlines?.let {
                    drawOutline(v, if (span <= MapZoom.AREA.spanLonDeg) it.fine else it.coarse, it.perDegree)
                    // Lakes and state/province lines from Region in; at World they
                    // are clutter a few pixels wide.
                    if (span <= MapZoom.REGION.spanLonDeg) drawDetail(v, it)
                }
                if (settings.mapGrid) drawGrid(v, labelPx)
                drawHeard(v, squares, selected, now)
                drawHomeAndPartner(v, home, partnerPath)
            }

            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // The buttons go to the next step in or out from wherever a pinch left it.
                val stepIn = MapZoom.entries.lastOrNull { it.spanLonDeg < span * STEP_SLACK_IN }
                val stepOut = MapZoom.entries.firstOrNull { it.spanLonDeg > span * STEP_SLACK_OUT }
                MapButton(stringResource(R.string.map_zoom_in), enabled = stepIn != null) {
                    stepIn?.let { span = it.spanLonDeg }
                }
                Text(
                    text = stringResource(MapZoom.nearest(span).label),
                    color = Tokens.StripColors.Label,
                    fontSize = Tokens.Strip.LabelSize
                )
                MapButton(stringResource(R.string.map_zoom_out), enabled = stepOut != null) {
                    stepOut?.let { span = it.spanLonDeg }
                }
                if (home != null) {
                    MapButton(stringResource(R.string.map_home), enabled = true) {
                        centreLon = home.centreLon
                        centreLat = home.centreLat
                    }
                }
                // Grid lines and labels on or off; heard squares are drawn either way.
                MapButton(stringResource(R.string.map_grid), enabled = true, on = settings.mapGrid) {
                    settings.chooseMapGrid(!settings.mapGrid)
                }
            }
            if (outlines == null) {
                Text(
                    text = stringResource(R.string.map_loading),
                    color = Tokens.StripColors.Label,
                    fontSize = Tokens.Strip.LabelSize,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        selected?.let { grid ->
            squares[grid]?.let { sq ->
                SquarePanel(sq, myGrid, now, onClose = { selected = null }, onCall = onCall)
            }
        }
    }
}

/** The projection for one frame. */
private class View(
    val width: Float,
    val height: Float,
    spanLonDeg: Double,
    val centreLon: Double,
    val centreLat: Double
) {
    /** Pixels per degree of longitude, and per unit of Mercator northing: the same. */
    val pxPerLon: Double = width / spanLonDeg
    val centreNorth: Double = Mercator.y(centreLat)

    fun x(lon: Double): Float = (width / 2 + (lon - centreLon) * pxPerLon).toFloat()
    fun y(lat: Double): Float = (height / 2 - (Mercator.y(lat) - centreNorth) * pxPerLon).toFloat()
    fun lonAt(x: Float): Double = centreLon + (x - width / 2) / pxPerLon
    fun northAt(y: Float): Double = centreNorth - (y - height / 2) / pxPerLon
    fun latAt(y: Float): Double = Mercator.lat(northAt(y))
    fun squareAt(at: Offset): String = Maidenhead.squareAt(WorldWrap.normalize(lonAt(at.x)), latAt(at.y))

    /** The screen's edges, unwrapped: they may run past 180 either way. */
    val west: Double get() = lonAt(0f)
    val east: Double get() = lonAt(width)

    /** Shifts (0, or 360 either way) at which a copy of the world is on screen. */
    val copies: List<Double> = WorldWrap.copies(west, east)
    val north: Double get() = latAt(0f).coerceAtMost(90.0)
    val south: Double get() = latAt(height).coerceAtLeast(-90.0)
}

private val landPaint = android.graphics.Paint().apply {
    style = android.graphics.Paint.Style.FILL
    color = Tokens.Map.Land.toArgb()
    isAntiAlias = true
}

/** Stroke width 0 is a hairline: one pixel whatever the canvas scale. */
private val borderPaint = android.graphics.Paint().apply {
    style = android.graphics.Paint.Style.STROKE
    strokeWidth = 0f
    color = Tokens.Map.Border.toArgb()
    isAntiAlias = true
}

private val labelPaint = android.graphics.Paint().apply {
    color = Tokens.Map.GridLabel.toArgb()
    isAntiAlias = true
    textAlign = android.graphics.Paint.Align.CENTER
    typeface = android.graphics.Typeface.MONOSPACE
}

private val waterPaint = android.graphics.Paint().apply {
    style = android.graphics.Paint.Style.FILL
    color = Tokens.StripColors.Screen.toArgb()
    isAntiAlias = true
}

private val adminPaint = android.graphics.Paint().apply {
    style = android.graphics.Paint.Style.STROKE
    strokeWidth = 0f
    color = Tokens.Map.AdminLine.toArgb()
    isAntiAlias = true
}

/**
 * Draw in basemap units: [perDegree] per degree, latitude already Mercator northing.
 * Once per copy of the world on screen; the outlines are cut at 180, so the copies
 * join up (BasemapTest checks the assets).
 */
private fun DrawScope.inBasemapUnits(v: View, perDegree: Int, draw: (android.graphics.Canvas) -> Unit) {
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        for (k in v.copies) {
            nc.save()
            nc.translate(v.width / 2, v.height / 2)
            nc.scale((v.pxPerLon / perDegree).toFloat(), (-v.pxPerLon / perDegree).toFloat())
            nc.translate(((k - v.centreLon) * perDegree).toFloat(), (-v.centreNorth * perDegree).toFloat())
            draw(nc)
            nc.restore()
        }
    }
}

private fun DrawScope.drawOutline(v: View, path: android.graphics.Path, perDegree: Int) =
    inBasemapUnits(v, perDegree) { nc ->
        nc.drawPath(path, landPaint)
        nc.drawPath(path, borderPaint)
    }

/** State and province lines, then lakes over them (a line never crosses open water). */
private fun DrawScope.drawDetail(v: View, o: Outlines) =
    inBasemapUnits(v, o.perDegree) { nc ->
        nc.drawPath(o.admin, adminPaint)
        nc.drawPath(o.lakes, waterPaint)
        nc.drawPath(o.lakes, borderPaint)
    }

/** Field lines always; square lines and labels once there is room for them. */
private fun DrawScope.drawGrid(v: View, labelPx: Float) {
    val squares = v.pxPerLon * 2 >= SQUARE_LINES_MIN_PX
    if (squares) {
        var lon = floor(v.west / 2) * 2
        while (lon <= v.east) {
            drawLine(Tokens.Map.SquareLine, Offset(v.x(lon), 0f), Offset(v.x(lon), size.height), 1f)
            lon += 2
        }
        var lat = floor(v.south)
        while (lat <= v.north) {
            drawLine(Tokens.Map.SquareLine, Offset(0f, v.y(lat)), Offset(size.width, v.y(lat)), 1f)
            lat += 1
        }
    }
    var lon = floor((v.west + 180) / 20) * 20 - 180
    while (lon <= v.east) {
        drawLine(Tokens.Map.FieldLine, Offset(v.x(lon), 0f), Offset(v.x(lon), size.height), 1.5f)
        lon += 20
    }
    var lat = floor((v.south + 90) / 10) * 10 - 90
    while (lat <= v.north) {
        drawLine(Tokens.Map.FieldLine, Offset(0f, v.y(lat)), Offset(size.width, v.y(lat)), 1.5f)
        lat += 10
    }

    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        labelPaint.textSize = labelPx
        if (v.pxPerLon * 2 >= SQUARE_LABELS_MIN_PX) {
            // Every square in view, named: the closest step is for finding one.
            var sLon = floor(v.west / 2) * 2
            while (sLon < v.east) {
                var sLat = floor(v.south)
                while (sLat < v.north) {
                    nc.drawText(Maidenhead.squareAt(WorldWrap.normalize(sLon + 1), sLat + 0.5), v.x(sLon + 1), v.y(sLat + 0.5) + labelPx / 3, labelPaint)
                    sLat += 1
                }
                sLon += 2
            }
        } else {
            var fLon = floor((v.west + 180) / 20) * 20 - 180
            while (fLon < v.east) {
                var fLat = floor((v.south + 90) / 10) * 10 - 90
                while (fLat < v.north) {
                    nc.drawText(Maidenhead.squareAt(WorldWrap.normalize(fLon + 10), fLat + 5).take(2), v.x(fLon + 10), v.y(fLat + 5) + labelPx / 3, labelPaint)
                    fLat += 10
                }
                fLon += 20
            }
        }
    }
}

/** Heard squares: green where someone is calling CQ, blue otherwise, dimmer with age. */
private fun DrawScope.drawHeard(v: View, squares: Map<String, MapSquares.Square>, selected: String?, nowMs: Long) {
    val minPx = Tokens.Map.MinSquarePx
    for (sq in squares.values) for (k in v.copies) {
        val box = Maidenhead.boxOf(sq.grid) ?: continue
        var left = v.x(box.west + k); var right = v.x(box.east + k)
        var top = v.y(box.north); var bottom = v.y(box.south)
        // Never smaller than a few pixels, or a busy square vanishes at World.
        if (right - left < minPx) { val c = (left + right) / 2; left = c - minPx / 2; right = c + minPx / 2 }
        if (bottom - top < minPx) { val c = (top + bottom) / 2; top = c - minPx / 2; bottom = c + minPx / 2 }
        val fresh = nowMs - sq.lastHeardMs <= Tokens.Map.FreshWithinMs
        val base = if (sq.anyCalling) Tokens.StripColors.Marker else Tokens.StripColors.Occupied
        drawRect(
            base.copy(alpha = if (fresh) Tokens.Map.FreshAlpha else Tokens.Map.StaleAlpha),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top)
        )
        if (sq.grid == selected) {
            drawRect(
                Tokens.StripColors.Frequency,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 2f)
            )
        }
    }
}

/**
 * Our dot, and the line to the partner along [partnerPath] (GreatCircle.path): it
 * leaves on the bearing the panel gives, so to Japan from FN25 it goes west.
 */
private fun DrawScope.drawHomeAndPartner(v: View, home: Maidenhead.Box?, partnerPath: DoubleArray?) {
    if (home == null) return
    partnerPath?.let { p ->
        var from = p[0]; var to = p[0]
        for (i in 0 until p.size / 2) { from = minOf(from, p[2 * i]); to = maxOf(to, p[2 * i]) }
        // The path is unwrapped and may run past 180, so it has copies of its own.
        val line = Path()
        for (k in WorldWrap.shifts(v.west, v.east, from, to)) {
            line.moveTo(v.x(p[0] + k), v.y(p[1]))
            for (i in 1 until p.size / 2) line.lineTo(v.x(p[2 * i] + k), v.y(p[2 * i + 1]))
        }
        drawPath(line, Tokens.StripColors.Strong, style = Stroke(width = 3f))
    }
    for (k in v.copies) {
        val at = Offset(v.x(home.centreLon + k), v.y(home.centreLat))
        drawCircle(Tokens.StripColors.Screen, radius = 9f, center = at)
        drawCircle(Tokens.StripColors.Marker, radius = 6f, center = at)
    }
}

/** The stations in a tapped square. A panel under the map, never a pop-up (rule 9). */
@Composable
private fun SquarePanel(
    sq: MapSquares.Square,
    myGrid: String?,
    nowMs: Long,
    onClose: () -> Unit,
    onCall: (RxSession.DecodeRow) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().background(Tokens.StripColors.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Tokens.Strip.Padding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val km = Maidenhead.distanceKm(myGrid, sq.grid)
            val deg = Maidenhead.bearingDeg(myGrid, sq.grid)
            Text(
                text = if (km != null && deg != null) stringResource(R.string.map_square_far, sq.grid, sq.stations.size, km, deg)
                else stringResource(R.string.map_square, sq.grid, sq.stations.size),
                color = Tokens.StripColors.Frequency,
                fontSize = Tokens.Strip.LabelSize,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(Tokens.MinTouchTarget).clickable(onClick = onClose)
            ) {
                Text(stringResource(R.string.map_close), color = Tokens.StripColors.Label, fontSize = Tokens.Strip.FrequencySize)
            }
        }
        LazyColumn(modifier = Modifier.heightIn(max = Tokens.Map.PanelMaxHeight)) {
            items(sq.stations, key = { it.call }) { s -> StationLine(s, nowMs) { onCall(s.row) } }
        }
    }
}

@Composable
private fun StationLine(s: HeardStations.Station, nowMs: Long, onTap: () -> Unit) {
    val agoSec = ((nowMs - s.lastHeardMs) / 1000).coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.Roster.RowHeight)
            .clickable(onClick = onTap)
            .padding(horizontal = Tokens.Strip.Padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(s.call, color = Tokens.StripColors.Frequency, fontSize = Tokens.Feed.RowTextSize,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Text(if (s.calling) stringResource(R.string.roster_cq) else "", color = Tokens.StripColors.Marker,
            fontSize = Tokens.Strip.LabelSize, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
        Text(s.report?.let { "%+d".format(it) } ?: "--", color = Tokens.StripColors.Label,
            fontSize = Tokens.Feed.RowTextSize, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
        Text("%d:%02d".format(agoSec / 60, agoSec % 60), color = Tokens.StripColors.Label,
            fontSize = Tokens.Feed.RowTextSize, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(Tokens.Roster.ChipHeight)
            .background(if (on) Tokens.StripColors.Faint else Tokens.StripColors.Background, RoundedCornerShape(14.dp))
            .border(1.dp, if (on) Tokens.StripColors.Occupied else Tokens.StripColors.TxBand, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp)
    ) {
        Text(text, color = if (on) Tokens.StripColors.Frequency else Tokens.StripColors.Label, fontSize = Tokens.Strip.LabelSize)
    }
}

@Composable
private fun MapButton(text: String, enabled: Boolean, on: Boolean = false, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Tokens.MinTouchTarget)
            .background(Tokens.StripColors.Background, RoundedCornerShape(8.dp))
            .border(1.dp, if (on) Tokens.StripColors.Occupied else Tokens.StripColors.TxBand, RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Text(
            text = text,
            color = if (enabled) Tokens.StripColors.Frequency else Tokens.StripColors.TxBand,
            fontSize = Tokens.Strip.FrequencySize,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Square lines are drawn once a square is this wide on screen, labels once it is this wide. */
private const val SQUARE_LINES_MIN_PX = 12.0
private const val SQUARE_LABELS_MIN_PX = 60.0

/**
 * A button step must differ from the current span by more than this, or a span a hair
 * off a step after a pinch would make + or - do almost nothing.
 */
private const val STEP_SLACK_IN = 0.95
private const val STEP_SLACK_OUT = 1.05
