package org.opentrafficmap.citstogo.drive.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.opentrafficmap.citstogo.drive.model.DenmCategory
import org.opentrafficmap.citstogo.drive.profile.AlertPriority
import org.opentrafficmap.citstogo.drive.safety.ActionId
import org.opentrafficmap.citstogo.drive.safety.EventRelevance
import org.opentrafficmap.citstogo.drive.safety.RelativeDirection
import org.opentrafficmap.citstogo.drive.safety.RelevanceTrafficDirection
import org.opentrafficmap.citstogo.drive.safety.SafetyEvent
import org.opentrafficmap.citstogo.drive.safety.SafetyEventStatus

/**
 * Durable DENM event history.
 *
 * Room would be overkill for one append-only table, so this uses the platform SQLite helper that is
 * already available. The schema is versioned; a future migration can add columns without dropping
 * the user's history. Only event metadata is stored, never raw C-ITS payloads.
 */
class EventHistoryDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE events (
                station_id INTEGER NOT NULL,
                sequence_number INTEGER NOT NULL,
                version INTEGER NOT NULL,
                category TEXT NOT NULL,
                cause_code INTEGER,
                sub_cause_code INTEGER,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                priority TEXT NOT NULL,
                status TEXT NOT NULL,
                latitude_e7 INTEGER,
                longitude_e7 INTEGER,
                detection_at INTEGER NOT NULL,
                reference_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                validity_seconds INTEGER NOT NULL,
                information_quality INTEGER,
                relevance_distance INTEGER,
                traffic_direction INTEGER,
                event_speed_kph REAL,
                event_heading_deg REAL,
                station_type INTEGER NOT NULL,
                distance_m REAL,
                relative_direction TEXT NOT NULL,
                relevance TEXT NOT NULL,
                confidence REAL NOT NULL,
                PRIMARY KEY (station_id, sequence_number, version)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX events_received ON events(received_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the initial schema. Later versions add columns with ALTER TABLE so history
        // survives an application update.
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE events ADD COLUMN is_ahead INTEGER") }
        }
    }

    fun insert(event: SafetyEvent) {
        val values = ContentValues().apply {
            put("station_id", event.actionId.originatingStationId)
            put("sequence_number", event.actionId.sequenceNumber)
            put("version", event.version)
            put("category", event.category.name)
            put("cause_code", event.causeCode)
            put("sub_cause_code", event.subCauseCode)
            put("title", event.title)
            put("description", event.description)
            put("priority", event.priority.name)
            put("status", event.status.name)
            put("latitude_e7", event.latitudeE7)
            put("longitude_e7", event.longitudeE7)
            put("detection_at", event.detectionAtMs)
            put("reference_at", event.referenceAtMs)
            put("received_at", event.receivedAtMs)
            put("expires_at", event.expiresAtMs)
            put("validity_seconds", event.validitySeconds)
            put("information_quality", event.informationQuality)
            put("relevance_distance", event.relevanceDistance)
            put("traffic_direction", event.trafficDirection?.code)
            put("event_speed_kph", event.eventSpeedKph)
            put("event_heading_deg", event.eventHeadingDeg)
            put("station_type", event.stationType)
            put("distance_m", event.distanceM)
            put("relative_direction", event.relativeDirection.name)
            put("relevance", event.relevance.name)
            put("confidence", event.confidence)
        }
        runCatching {
            writableDatabase.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            prune()
        }
    }

    fun recent(limit: Int = 200): List<SafetyEvent> = try {
        readableDatabase.query(
            "events",
            null,
            null,
            null,
            null,
            null,
            "received_at DESC",
            limit.coerceIn(1, 2_000).toString(),
        ).use { cursor ->
            val events = mutableListOf<SafetyEvent>()
            while (cursor.moveToNext()) {
                fun text(column: String): String? = cursor.getColumnIndex(column).let {
                    if (it < 0 || cursor.isNull(it)) null else cursor.getString(it)
                }
                fun long(column: String): Long = cursor.getColumnIndex(column).let {
                    if (it < 0 || cursor.isNull(it)) 0L else cursor.getLong(it)
                }
                fun int(column: String): Int? = cursor.getColumnIndex(column).let {
                    if (it < 0 || cursor.isNull(it)) null else cursor.getInt(it)
                }
                fun double(column: String): Double? = cursor.getColumnIndex(column).let {
                    if (it < 0 || cursor.isNull(it)) null else cursor.getDouble(it)
                }
                events += SafetyEvent(
                    actionId = ActionId(long("station_id"), int("sequence_number") ?: 0),
                    category = DenmCategory.entries.firstOrNull { it.name == text("category") }
                        ?: DenmCategory.Other,
                    causeCode = int("cause_code"),
                    subCauseCode = int("sub_cause_code"),
                    title = text("title").orEmpty(),
                    description = text("description").orEmpty(),
                    priority = AlertPriority.entries.firstOrNull { it.name == text("priority") }
                        ?: AlertPriority.Informational,
                    status = SafetyEventStatus.entries.firstOrNull { it.name == text("status") }
                        ?: SafetyEventStatus.Expired,
                    latitudeE7 = int("latitude_e7"),
                    longitudeE7 = int("longitude_e7"),
                    detectionAtMs = long("detection_at"),
                    referenceAtMs = long("reference_at"),
                    receivedAtMs = long("received_at"),
                    expiresAtMs = long("expires_at"),
                    validitySeconds = int("validity_seconds") ?: 0,
                    informationQuality = int("information_quality"),
                    relevanceDistance = int("relevance_distance"),
                    trafficDirection = RelevanceTrafficDirection.fromCode(int("traffic_direction")),
                    eventSpeedKph = double("event_speed_kph"),
                    eventHeadingDeg = double("event_heading_deg"),
                    stationType = int("station_type") ?: 0,
                    distanceM = double("distance_m"),
                    relativeDirection = RelativeDirection.entries.firstOrNull {
                        it.name == text("relative_direction")
                    } ?: RelativeDirection.Unknown,
                    relevance = EventRelevance.entries.firstOrNull { it.name == text("relevance") }
                        ?: EventRelevance.Medium,
                    confidence = double("confidence") ?: 0.5,
                    version = int("version") ?: 1,
                )
            }
            events
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun prune(maxRows: Int = MAX_ROWS) {
        runCatching {
            writableDatabase.execSQL(
                "DELETE FROM events WHERE rowid NOT IN (SELECT rowid FROM events ORDER BY received_at DESC LIMIT ?)",
                arrayOf(maxRows),
            )
        }
    }

    fun clear() {
        runCatching { writableDatabase.delete("events", null, null) }
    }

    companion object {
        private const val NAME = "cits_drive_events.db"
        private const val VERSION = 2
        private const val MAX_ROWS = 1_000
    }
}
