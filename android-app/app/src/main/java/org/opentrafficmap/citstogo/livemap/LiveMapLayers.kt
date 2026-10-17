package org.opentrafficmap.citstogo.livemap

import com.google.gson.JsonObject
import org.maplibre.android.location.LocationComponentConstants
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.concat
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** A marker as the feature under a tap identifies it. */
sealed interface MarkerRef {
    data class Station(val stationId: Long) : MarkerRef
    data class Denm(val id: String) : MarkerRef
    data class RoadSideUnit(val macAddress: String) : MarkerRef

    /** The pin of a station that the map was asked to show. */
    data class Focus(val macAddress: String) : MarkerRef
}

/** The GeoJSON features of the map layers. Plain GeoJSON, so that JVM tests cover it. */
object LiveMapFeatures {
    const val STATION_ID = "stationId"
    const val DENM_ID = "denmId"
    const val MAC_ADDRESS = "macAddress"
    const val FOCUS_MAC_ADDRESS = "focusMacAddress"
    const val ICON = "icon"
    const val LABEL = "label"
    const val ALERT = "alert"
    const val HEADING = "heading"
    const val TRAILER = "trailer"
    const val STALE = "stale"

    // Ids are text: station ids are unsigned 32-bit, and a tap query returns numbers as doubles.
    fun stations(stations: List<StationMarker>): FeatureCollection = FeatureCollection.fromFeatures(
        stations.map { station ->
            val properties = JsonObject().apply {
                addProperty(STATION_ID, station.stationId.toString())
                addProperty(ICON, station.icon)
                station.directionDeg?.let { addProperty(HEADING, it) }
                addProperty(TRAILER, station.trailer)
                addProperty(STALE, station.stale)
            }
            Feature.fromGeometry(
                Point.fromLngLat(station.longitude, station.latitude),
                properties,
                station.stationId.toString(),
            )
        },
    )

    fun denms(denms: List<DenmMarker>): FeatureCollection = FeatureCollection.fromFeatures(
        denms.map { denm ->
            val properties = JsonObject().apply {
                addProperty(DENM_ID, denm.id)
                addProperty(ICON, denm.iconGroup.icon)
                addProperty(LABEL, denm.causeLabel)
            }
            Feature.fromGeometry(Point.fromLngLat(denm.longitude, denm.latitude), properties, denm.id)
        },
    )

    /** The event zones as lines, red for an alert like their marker. */
    fun denmZones(denms: List<DenmMarker>): FeatureCollection = FeatureCollection.fromFeatures(
        denms.filter { it.eventZone.isNotEmpty() }.map { denm ->
            val properties = JsonObject().apply {
                addProperty(DENM_ID, denm.id)
                addProperty(ALERT, denm.iconGroup.alert)
            }
            Feature.fromGeometry(line(denm.eventZone), properties)
        },
    )

    /** Every trace as a line of its own. */
    fun denmTraces(denms: List<DenmMarker>): FeatureCollection = FeatureCollection.fromFeatures(
        denms.flatMap { denm ->
            denm.traces.map { trace ->
                val properties = JsonObject().apply { addProperty(DENM_ID, denm.id) }
                Feature.fromGeometry(line(trace), properties)
            }
        },
    )

    private fun line(positions: List<GeoPosition>): LineString =
        LineString.fromLngLats(positions.map { Point.fromLngLat(it.longitude, it.latitude) })

    fun roadSideUnits(units: List<RoadSideUnitMarker>): FeatureCollection = FeatureCollection.fromFeatures(
        units.map { unit ->
            val properties = JsonObject().apply {
                addProperty(MAC_ADDRESS, unit.macAddress)
                addProperty(ICON, unit.icon)
            }
            Feature.fromGeometry(Point.fromLngLat(unit.longitude, unit.latitude), properties, unit.macAddress)
        },
    )

    fun focus(marker: FocusMarker?): FeatureCollection = FeatureCollection.fromFeatures(
        listOfNotNull(marker).map { focus ->
            val properties = JsonObject().apply { addProperty(FOCUS_MAC_ADDRESS, focus.macAddress) }
            Feature.fromGeometry(Point.fromLngLat(focus.longitude, focus.latitude), properties, focus.macAddress)
        },
    )

    fun markerOf(feature: Feature): MarkerRef? = when {
        feature.hasNonNullValueForProperty(FOCUS_MAC_ADDRESS) ->
            MarkerRef.Focus(feature.getStringProperty(FOCUS_MAC_ADDRESS))
        feature.hasNonNullValueForProperty(DENM_ID) -> MarkerRef.Denm(feature.getStringProperty(DENM_ID))
        feature.hasNonNullValueForProperty(STATION_ID) ->
            feature.getStringProperty(STATION_ID).toLongOrNull()?.let { MarkerRef.Station(it) }
        feature.hasNonNullValueForProperty(MAC_ADDRESS) -> MarkerRef.RoadSideUnit(feature.getStringProperty(MAC_ADDRESS))
        else -> null
    }
}

/**
 * The sources and layers of the live map: road side units from history, the traces and event zones of DENMs above
 * them, stations above those, DENM markers with their cause above the stations, and on top the pin of a station the
 * map was asked to show. The trailer of the own vehicle lies just below MapLibre's own position.
 */
object LiveMapLayers {
    const val STATIONS = "stations"
    const val STATION_POINTERS = "station-pointers"
    const val DENMS = "denms"
    const val DENM_ZONES = "denm-zones"
    const val DENM_TRACES = "denm-traces"
    const val ROAD_SIDE_UNITS = "roadSideUnits"
    const val FOCUS = "focus"
    const val OWN_TRAILER = "own-trailer"
    private const val STALE_OPACITY = 0.6f
    private const val HISTORY_OPACITY = 0.75f
    private const val DENM_LINE_OPACITY = 0.95f
    // The font of the cause under a DENM marker, served by the glyphs of both OpenFreeMap styles.
    private val LABEL_FONT = arrayOf("Noto Sans Bold")
    private const val LABEL_HALO = 0xD9111827.toInt()
    private const val OWN_POSITION_LAYER_PREFIX = "mapbox-location-"

    /** Adds the sources and layers once per style; the icons must be added as well, see [MapIcons]. */
    fun addTo(style: Style) {
        if (style.getSource(STATIONS) != null) return
        val stale = eq(get(LiveMapFeatures.STALE), true)
        style.addSource(GeoJsonSource(ROAD_SIDE_UNITS))
        style.addSource(GeoJsonSource(DENM_TRACES))
        style.addSource(GeoJsonSource(DENM_ZONES))
        style.addSource(GeoJsonSource(STATIONS))
        style.addSource(GeoJsonSource(DENMS))
        style.addSource(GeoJsonSource(FOCUS))
        fun add(layer: Layer) = addBelowOwnPosition(style, layer)
        add(
            SymbolLayer(ROAD_SIDE_UNITS, ROAD_SIDE_UNITS).withProperties(
                iconImage(get(LiveMapFeatures.ICON)),
                iconOpacity(HISTORY_OPACITY),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        add(
            LineLayer(DENM_TRACES, DENM_TRACES).withProperties(
                lineColor(MapIcons.DENM_ALERT),
                lineWidth(4f),
                lineDasharray(arrayOf(2f, 1.4f)),
                lineOpacity(DENM_LINE_OPACITY),
            ),
        )
        add(
            LineLayer(DENM_ZONES, DENM_ZONES).withProperties(
                lineColor(
                    switchCase(
                        eq(get(LiveMapFeatures.ALERT), true),
                        color(MapIcons.DENM_ALERT),
                        color(MapIcons.DENM_WARNING),
                    ),
                ),
                lineWidth(5f),
                lineOpacity(DENM_LINE_OPACITY),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        val staleSuffix = switchCase(stale, literal(MapIcons.STALE_SUFFIX), literal(""))
        val staleOpacity = iconOpacity(switchCase(stale, literal(STALE_OPACITY), literal(1f)))
        val trailerSuffix =
            switchCase(eq(get(LiveMapFeatures.TRAILER), true), literal(MapIcons.TRAILER_SUFFIX), literal(""))
        // The pointer and a trailer opposite it turn with the heading below the circle, whose pictogram stays upright.
        add(
            SymbolLayer(STATION_POINTERS, STATIONS)
                .withProperties(
                    iconImage(
                        concat(
                            get(LiveMapFeatures.ICON),
                            literal(MapIcons.POINTER_SUFFIX),
                            trailerSuffix,
                            staleSuffix,
                        ),
                    ),
                    staleOpacity,
                    iconRotate(get(LiveMapFeatures.HEADING)),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    // Upright on a tilted map, like the circle; it would hide under it when lying flat.
                    iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                )
                .withFilter(has(LiveMapFeatures.HEADING)),
        )
        add(
            SymbolLayer(STATIONS, STATIONS).withProperties(
                iconImage(concat(get(LiveMapFeatures.ICON), staleSuffix)),
                staleOpacity,
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        // The marker always shows, its cause below it only where there is room. Light text on a dark halo reads on
        // both map styles.
        add(
            SymbolLayer(DENMS, DENMS).withProperties(
                iconImage(get(LiveMapFeatures.ICON)),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                textField(get(LiveMapFeatures.LABEL)),
                textFont(LABEL_FONT),
                textSize(12f),
                textColor(MapIcons.DENM_OUTLINE),
                textHaloColor(LABEL_HALO),
                textHaloWidth(1.8f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 1.35f)),
                textOptional(true),
            ),
        )
        add(
            SymbolLayer(FOCUS, FOCUS).withProperties(
                iconImage(FocusMarker.ICON),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
    }

    fun update(style: Style, liveMap: LiveMap, roadSideUnits: List<RoadSideUnitMarker>, focus: FocusMarker?) {
        // A style that a newer one replaced must not be touched.
        if (!style.isFullyLoaded) return
        style.getSourceAs<GeoJsonSource>(ROAD_SIDE_UNITS)?.setGeoJson(LiveMapFeatures.roadSideUnits(roadSideUnits))
        style.getSourceAs<GeoJsonSource>(DENM_TRACES)?.setGeoJson(LiveMapFeatures.denmTraces(liveMap.denms))
        style.getSourceAs<GeoJsonSource>(DENM_ZONES)?.setGeoJson(LiveMapFeatures.denmZones(liveMap.denms))
        style.getSourceAs<GeoJsonSource>(STATIONS)?.setGeoJson(LiveMapFeatures.stations(liveMap.stations))
        style.getSourceAs<GeoJsonSource>(DENMS)?.setGeoJson(LiveMapFeatures.denms(liveMap.denms))
        style.getSourceAs<GeoJsonSource>(FOCUS)?.setGeoJson(LiveMapFeatures.focus(focus))
    }

    /**
     * Shows the trailer of the own vehicle turned to [headingDeg], or hides it for `null`. It is a layer on the source
     * of MapLibre's own position, so that it moves with the animated position instead of jumping ahead of it, and
     * [scale] sizes it like the own vehicle icon. Does nothing before the location component is activated.
     */
    fun updateOwnTrailer(style: Style, headingDeg: Float?, scale: Expression) {
        if (!style.isFullyLoaded) return
        val layer = style.getLayerAs<SymbolLayer>(OWN_TRAILER) ?: addOwnTrailer(style, scale) ?: return
        if (headingDeg == null) {
            layer.setProperties(visibility(Property.NONE))
        } else {
            layer.setProperties(iconRotate(headingDeg), visibility(Property.VISIBLE))
        }
    }

    private fun addOwnTrailer(style: Style, scale: Expression): SymbolLayer? {
        if (style.getSource(LocationComponentConstants.LOCATION_SOURCE) == null) return null
        val layer = SymbolLayer(OWN_TRAILER, LocationComponentConstants.LOCATION_SOURCE).withProperties(
            iconImage(MapIcons.OWN_TRAILER),
            iconSize(scale),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            visibility(Property.NONE),
        )
        addBelowOwnPosition(style, layer)
        return layer
    }

    /** After a style change MapLibre has already put the own position on the new style; it stays on top. */
    private fun addBelowOwnPosition(style: Style, layer: Layer) {
        val ownPosition = style.layers.firstOrNull { it.id.startsWith(OWN_POSITION_LAYER_PREFIX) }?.id
        if (ownPosition != null) style.addLayerBelow(layer, ownPosition) else style.addLayer(layer)
    }
}
