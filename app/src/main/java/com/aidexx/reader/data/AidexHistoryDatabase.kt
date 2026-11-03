package com.aidexx.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aidexx.reader.protocol.AidexReading
import com.aidexx.reader.protocol.AidexSensor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.round

class AidexHistoryDatabase(private val context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    private data class CalibratedSample(
        val serial: String,
        val receivedAtMs: Long,
        val glucoseMgDl: Double
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sensors (
                serial TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                address TEXT NOT NULL,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                last_rssi INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE readings (
                serial TEXT NOT NULL,
                sensor_minute INTEGER NOT NULL,
                received_at_ms INTEGER NOT NULL,
                glucose_mgdl INTEGER NOT NULL,
                trend_raw INTEGER NOT NULL,
                quality INTEGER NOT NULL,
                valid INTEGER NOT NULL,
                previous_mgdl INTEGER,
                previous2_mgdl INTEGER,
                crc_valid INTEGER NOT NULL,
                rssi INTEGER NOT NULL,
                PRIMARY KEY (serial, sensor_minute)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX idx_readings_serial_time
            ON readings(serial, received_at_ms)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX idx_readings_time
            ON readings(received_at_ms)
            """.trimIndent()
        )

        createCalibrationTable(
            db
        )

        createNotesTable(
            db
        )

        createHealthTables(db)
        createNoteHealthTable(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        /*
         * Migrations are additive/non-destructive so APK upgrades preserve
         * raw sensor history.
         */
        if (
            oldVersion <
            2
        ) {
            createCalibrationTable(
                db
            )
        }

        if (
            oldVersion <
            3
        ) {
            createNotesTable(
                db
            )
        }

        if (
            oldVersion <
            4
        ) {
            ensureNotesPhotoColumn(
                db
            )
        }

        if (oldVersion < 5) {
            createHealthTables(db)
        }
        if (oldVersion < 6) createNoteHealthTable(db)
        if (oldVersion < 7) ensureBodyFatColumn(db)
        if (oldVersion < 8) {
            val exists = db.rawQuery("PRAGMA table_info(glucose_notes)", null).use { c ->
                var found = false
                while(c.moveToNext()) if(c.getString(c.getColumnIndexOrThrow("name")) == "fast_action_minutes") found = true
                found
            }
            if(!exists) db.execSQL("ALTER TABLE glucose_notes ADD COLUMN fast_action_minutes INTEGER")
        }
    }

    private fun createCalibrationTable(
        db: SQLiteDatabase
    ) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS calibrations (
                serial TEXT NOT NULL,
                effective_at_ms INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL,
                sensor_minute INTEGER NOT NULL,
                raw_cgm_mgdl INTEGER NOT NULL,
                blood_mgdl INTEGER NOT NULL,
                offset_mgdl INTEGER NOT NULL,
                PRIMARY KEY (serial, effective_at_ms)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_calibrations_serial_time
            ON calibrations(serial, effective_at_ms)
            """.trimIndent()
        )
    }

    private fun ensureNotesPhotoColumn(
        db: SQLiteDatabase
    ) {
        val hasPhotoColumn =
            db.rawQuery(
                "PRAGMA table_info(glucose_notes)",
                null
            ).use { cursor ->
                val nameIndex =
                    cursor.getColumnIndex(
                        "name"
                    )
                var found =
                    false
                while (
                    cursor.moveToNext()
                ) {
                    if (
                        nameIndex >= 0 &&
                        cursor.getString(
                            nameIndex
                        ) ==
                        "photo_paths"
                    ) {
                        found =
                            true
                        break
                    }
                }
                found
            }

        if (
            !hasPhotoColumn
        ) {
            db.execSQL(
                "ALTER TABLE glucose_notes ADD COLUMN photo_paths TEXT NOT NULL DEFAULT ''"
            )
        }
    }

    private fun createNotesTable(
        db: SQLiteDatabase
    ) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS glucose_notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                serial TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                fast_insulin_units REAL NOT NULL DEFAULT 0,
                slow_insulin_units REAL NOT NULL DEFAULT 0,
                carbs_grams REAL NOT NULL DEFAULT 0,
                note_text TEXT NOT NULL DEFAULT '',
                photo_paths TEXT NOT NULL DEFAULT '',
                fast_action_minutes INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_glucose_notes_serial_time
            ON glucose_notes(serial, timestamp_ms)
            """.trimIndent()
        )
    }


    private fun createHealthTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS blood_pressure (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_ms INTEGER NOT NULL,
                systolic INTEGER NOT NULL,
                diastolic INTEGER NOT NULL,
                pulse INTEGER,
                note_text TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blood_pressure_time ON blood_pressure(timestamp_ms)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS weight_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_ms INTEGER NOT NULL,
                weight_kg REAL NOT NULL,
                note_text TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_weight_entries_time ON weight_entries(timestamp_ms)")
    }

    fun saveBloodPressure(entry: BloodPressureEntry): Long {
        val values = ContentValues().apply {
            put("timestamp_ms", entry.timestampMs); put("systolic", entry.systolic); put("diastolic", entry.diastolic)
            if (entry.pulse == null) putNull("pulse") else put("pulse", entry.pulse)
            put("note_text", entry.note.trim())
        }
        return if (entry.id > 0) { writableDatabase.update("blood_pressure", values, "id = ?", arrayOf(entry.id.toString())); entry.id }
        else writableDatabase.insert("blood_pressure", null, values)
    }

    fun deleteBloodPressure(id: Long) { writableDatabase.delete("blood_pressure", "id = ?", arrayOf(id.toString())) }

    fun loadBloodPressure(): List<BloodPressureEntry> {
        val out = ArrayList<BloodPressureEntry>()
        readableDatabase.query("blood_pressure", arrayOf("id","timestamp_ms","systolic","diastolic","pulse","note_text"), null,null,null,null,"timestamp_ms ASC").use { c ->
            while (c.moveToNext()) out += BloodPressureEntry(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), if(c.isNull(4)) null else c.getInt(4), c.getString(5).orEmpty())
        }; return (out + noteBloodPressure()).sortedBy { it.timestampMs }
    }

    fun saveWeight(entry: WeightEntry): Long {
        val values = ContentValues().apply { put("timestamp_ms",entry.timestampMs); put("weight_kg",entry.weightKg); put("note_text",entry.note.trim()) }
        return if(entry.id > 0) { writableDatabase.update("weight_entries",values,"id = ?",arrayOf(entry.id.toString())); entry.id } else writableDatabase.insert("weight_entries",null,values)
    }
    fun deleteWeight(id: Long) { writableDatabase.delete("weight_entries","id = ?",arrayOf(id.toString())) }
    fun loadWeights(): List<WeightEntry> {
        val out=ArrayList<WeightEntry>(); readableDatabase.query("weight_entries",arrayOf("id","timestamp_ms","weight_kg","note_text"),null,null,null,null,"timestamp_ms ASC").use { c ->
            while(c.moveToNext()) out += WeightEntry(c.getLong(0),c.getLong(1),c.getDouble(2),c.getString(3).orEmpty())
        }; return (out + noteWeights()).sortedBy { it.timestampMs }
    }

    private fun createNoteHealthTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS note_health (
            note_id INTEGER PRIMARY KEY NOT NULL,
            systolic INTEGER, diastolic INTEGER, pulse INTEGER,
            weight_kg REAL, height_cm REAL,
            body_fat_percent REAL CHECK(body_fat_percent IS NULL OR body_fat_percent BETWEEN 0 AND 100)
        )""".trimIndent())
    }

    private fun ensureBodyFatColumn(db: SQLiteDatabase) {
        val exists = db.rawQuery("PRAGMA table_info(note_health)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "body_fat_percent") found = true
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE note_health ADD COLUMN body_fat_percent REAL CHECK(body_fat_percent IS NULL OR body_fat_percent BETWEEN 0 AND 100)")
    }

    fun loadLatestBodyFat(atMs: Long): Pair<Double, Long>? {
        readableDatabase.rawQuery("""SELECT h.body_fat_percent,n.timestamp_ms
            FROM note_health h JOIN glucose_notes n ON n.id=h.note_id
            WHERE h.body_fat_percent IS NOT NULL AND n.timestamp_ms <= ?
            ORDER BY n.timestamp_ms DESC,n.id DESC LIMIT 1""", arrayOf(atMs.toString())).use { c ->
            return if(c.moveToFirst()) c.getDouble(0) to c.getLong(1) else null
        }
    }

    private fun saveNoteHealth(id: Long, note: GlucoseNote) {
        require(note.bodyFatPercent == null || (note.bodyFatPercent.isFinite() && note.bodyFatPercent in 0.0..100.0))
        val values = ContentValues().apply {
            put("note_id", id)
            put("systolic", note.systolic)
            put("diastolic", note.diastolic)
            put("pulse", note.pulse)
            put("weight_kg", note.weightKg)
            put("height_cm", note.heightCm)
            put("body_fat_percent", note.bodyFatPercent)
        }
        check(writableDatabase.insertWithOnConflict("note_health", null, values, SQLiteDatabase.CONFLICT_REPLACE) > 0)
    }

    private fun withNoteHealth(note: GlucoseNote): GlucoseNote {
        readableDatabase.rawQuery("SELECT systolic,diastolic,pulse,weight_kg,height_cm,body_fat_percent FROM note_health WHERE note_id = ?", arrayOf(note.id.toString())).use { c ->
            if (!c.moveToFirst()) return note
            return note.copy(
                systolic = if(c.isNull(0)) null else c.getInt(0),
                diastolic = if(c.isNull(1)) null else c.getInt(1),
                pulse = if(c.isNull(2)) null else c.getInt(2),
                weightKg = if(c.isNull(3)) null else c.getDouble(3),
                heightCm = if(c.isNull(4)) null else c.getDouble(4),
                bodyFatPercent = if(c.isNull(5)) null else c.getDouble(5)
            )
        }
    }

    private fun noteBloodPressure(): List<BloodPressureEntry> {
        val out = ArrayList<BloodPressureEntry>()
        readableDatabase.rawQuery("""SELECT n.id,n.timestamp_ms,h.systolic,h.diastolic,h.pulse,n.note_text
            FROM glucose_notes n JOIN note_health h ON h.note_id=n.id
            WHERE h.systolic IS NOT NULL AND h.diastolic IS NOT NULL""", null).use { c ->
            while(c.moveToNext()) out += BloodPressureEntry(-c.getLong(0),c.getLong(1),c.getInt(2),c.getInt(3),if(c.isNull(4)) null else c.getInt(4),c.getString(5))
        }
        return out
    }

    private fun noteWeights(): List<WeightEntry> {
        val out = ArrayList<WeightEntry>()
        readableDatabase.rawQuery("""SELECT n.id,n.timestamp_ms,h.weight_kg,n.note_text
            FROM glucose_notes n JOIN note_health h ON h.note_id=n.id WHERE h.weight_kg IS NOT NULL""", null).use { c ->
            while(c.moveToNext()) out += WeightEntry(-c.getLong(0),c.getLong(1),c.getDouble(2),c.getString(3))
        }
        return out
    }

    fun loadHeightCm(atMs: Long): Double? {
        readableDatabase.rawQuery("""SELECT h.height_cm FROM note_health h JOIN glucose_notes n ON n.id=h.note_id
            WHERE h.height_cm > 0 AND n.timestamp_ms <= ? ORDER BY n.timestamp_ms DESC LIMIT 1""", arrayOf(atMs.toString())).use { c ->
            return if(c.moveToFirst()) c.getDouble(0) else null
        }
    }


    fun saveNote(note: GlucoseNote): Long {
        val serial = note.serial.uppercase()
        val db = writableDatabase
        val values = ContentValues().apply {
            put("serial", serial)
            put("timestamp_ms", note.timestampMs)
            put("fast_insulin_units", note.fastInsulinUnits)
            put("slow_insulin_units", note.slowInsulinUnits)
            put("carbs_grams", note.carbsGrams)
            put("note_text", note.noteText.trim())
            put("photo_paths", note.photoPaths.filter { it.isNotBlank() }.distinct().joinToString("\n"))
        }
        val id: Long
        db.beginTransaction()
        try {
            // Existing doses retain their stored duration, even after settings change.
            var previousDose = false
            var previousMinutes: Int? = null
            if (note.id > 0L) db.rawQuery("SELECT fast_insulin_units,fast_action_minutes FROM glucose_notes WHERE id=? AND serial=?", arrayOf(note.id.toString(), serial)).use { c ->
                if(c.moveToFirst()) {
                    previousDose = c.getDouble(0) > 0.0
                    previousMinutes = if(c.isNull(1)) null else c.getInt(1)
                }
            }
            val minutes = if (!note.hasFastInsulin) null else if(previousDose) previousMinutes
                else com.aidexx.reader.InsulinSettings.minutes(context)
            if(minutes == null) values.putNull("fast_action_minutes") else values.put("fast_action_minutes", minutes)
            id = if (note.id > 0L) {
                check(db.update("glucose_notes", values, "id = ? AND serial = ?", arrayOf(note.id.toString(),serial)) == 1)
                note.id
            } else db.insertOrThrow("glucose_notes", null, values)
            saveNoteHealth(id, note)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        HistoryDatabaseEvents.notifyChanged(serial = serial, reason = "note_changed")
        return id
    }


    fun loadAllNotes(): List<GlucoseNote> {
        readableDatabase.query(
            "glucose_notes",
            arrayOf(
                "id",
                "serial",
                "timestamp_ms",
                "fast_insulin_units",
                "slow_insulin_units",
                "carbs_grams",
                "note_text",
                "photo_paths",
                "fast_action_minutes"
            ),
            null,
            null,
            null,
            null,
            "timestamp_ms ASC"
        ).use { cursor ->
            val notes = ArrayList<GlucoseNote>()

            while (cursor.moveToNext()) {
                notes += GlucoseNote(
                    id = cursor.getLong(0),
                    serial = cursor.getString(1),
                    timestampMs = cursor.getLong(2),
                    fastInsulinActionMinutes = if (cursor.isNull(8)) null else cursor.getInt(8),
                    fastInsulinUnits = cursor.getDouble(3),
                    slowInsulinUnits = cursor.getDouble(4),
                    carbsGrams = cursor.getDouble(5),
                    noteText = cursor.getString(6),
                    photoPaths =
                        cursor.getString(7)
                            .orEmpty()
                            .split("\n")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                )
            }

            return notes.map { withNoteHealth(it) }
        }
    }

    fun deleteNote(
        serial: String,
        noteId: Long
    ) {
        val normalized =
            serial.uppercase()

        writableDatabase.delete(
            "glucose_notes",
            "id = ? AND serial = ?",
            arrayOf(
                noteId.toString(),
                normalized
            )
        )

        writableDatabase.execSQL("DELETE FROM note_health WHERE note_id NOT IN (SELECT id FROM glucose_notes)")
        HistoryDatabaseEvents.notifyChanged(
            serial = normalized,
            reason = "note_changed"
        )
    }

    fun loadNotes(
        serial: String
    ): List<GlucoseNote> {
        val normalized =
            serial.uppercase()

        readableDatabase.query(
            "glucose_notes",
            arrayOf(
                "id",
                "serial",
                "timestamp_ms",
                "fast_insulin_units",
                "slow_insulin_units",
                "carbs_grams",
                "note_text",
                "photo_paths",
                "fast_action_minutes"
            ),
            "serial = ?",
            arrayOf(
                normalized
            ),
            null,
            null,
            "timestamp_ms ASC"
        ).use {
                cursor ->
            val notes =
                ArrayList<GlucoseNote>()

            while (
                cursor.moveToNext()
            ) {
                notes +=
                    GlucoseNote(
                        id =
                            cursor.getLong(
                                0
                            ),
                        serial =
                            cursor.getString(
                                1
                            ),
                        timestampMs =
                            cursor.getLong(
                                2
                            ),
                        fastInsulinActionMinutes = if (cursor.isNull(8)) null else cursor.getInt(8),
                        fastInsulinUnits =
                            cursor.getDouble(
                                3
                            ),
                        slowInsulinUnits =
                            cursor.getDouble(
                                4
                            ),
                        carbsGrams =
                            cursor.getDouble(
                                5
                            ),
                        noteText =
                            cursor.getString(
                                6
                            ),
                        photoPaths =
                            cursor.getString(
                                7
                            )
                                .orEmpty()
                                .split("\n")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                    )
            }

            return notes.map { withNoteHealth(it) }
        }
    }

    fun saveCalibration(
        event: CalibrationEvent
    ) {
        val values =
            ContentValues().apply {
                put(
                    "serial",
                    event.serial
                        .uppercase()
                )
                put(
                    "effective_at_ms",
                    event.effectiveAtMs
                )
                put(
                    "created_at_ms",
                    event.createdAtMs
                )
                put(
                    "sensor_minute",
                    event.sensorMinute
                )
                put(
                    "raw_cgm_mgdl",
                    event.rawCgmMgDl
                )
                put(
                    "blood_mgdl",
                    event.bloodMgDl
                )
                put(
                    "offset_mgdl",
                    event.offsetMgDl
                )
            }

        writableDatabase
            .insertWithOnConflict(
                "calibrations",
                null,
                values,
                SQLiteDatabase
                    .CONFLICT_REPLACE
            )

        HistoryDatabaseEvents
            .notifyChanged(
                serial =
                    event.serial,
                reason =
                    "calibration_changed"
            )
    }

    fun loadCalibrations(
        serial: String
    ): List<CalibrationEvent> {
        val normalized =
            serial.uppercase()

        readableDatabase.query(
            "calibrations",
            arrayOf(
                "serial",
                "effective_at_ms",
                "created_at_ms",
                "sensor_minute",
                "raw_cgm_mgdl",
                "blood_mgdl",
                "offset_mgdl"
            ),
            "serial = ?",
            arrayOf(
                normalized
            ),
            null,
            null,
            "effective_at_ms ASC"
        ).use {
                cursor ->
            val events =
                ArrayList<CalibrationEvent>()

            while (
                cursor.moveToNext()
            ) {
                events +=
                    CalibrationEvent(
                        serial =
                            cursor.getString(
                                0
                            ),
                        effectiveAtMs =
                            cursor.getLong(
                                1
                            ),
                        createdAtMs =
                            cursor.getLong(
                                2
                            ),
                        sensorMinute =
                            cursor.getInt(
                                3
                            ),
                        rawCgmMgDl =
                            cursor.getInt(
                                4
                            ),
                        bloodMgDl =
                            cursor.getInt(
                                5
                            ),
                        offsetMgDl =
                            cursor.getInt(
                                6
                            )
                    )
            }

            return events
        }
    }

    fun loadLatestCalibration(
        serial: String
    ): CalibrationEvent? =
        loadCalibrations(
            serial
        )
            .lastOrNull()

    fun saveSensor(
        sensor: AidexSensor
    ) {
        val now =
            System.currentTimeMillis()

        val serial =
            sensor.serial.uppercase()

        val previousSensorMinute =
            loadLatestReading(
                serial
            )
                ?.sensorMinute

        writableDatabase
            .beginTransaction()

        try {
            val existingFirstSeen =
                queryFirstSeen(
                    serial
                ) ?: now

            val sensorValues =
                ContentValues().apply {
                    put(
                        "serial",
                        serial
                    )
                    put(
                        "name",
                        sensor.name
                    )
                    put(
                        "address",
                        sensor.address
                    )
                    put(
                        "first_seen_ms",
                        existingFirstSeen
                    )
                    put(
                        "last_seen_ms",
                        sensor.reading
                            ?.receivedAtMs
                            ?: now
                    )
                    put(
                        "last_rssi",
                        sensor.rssi
                    )
                }

            writableDatabase
                .insertWithOnConflict(
                    "sensors",
                    null,
                    sensorValues,
                    SQLiteDatabase
                        .CONFLICT_REPLACE
                )

            sensor.reading
                ?.takeIf {
                    it.crcValid
                }
                ?.let {
                        reading ->
                    /*
                     * Only CRC-verified packets may enter glucose history.
                     * A CRC-failed advertisement can still refresh sensor
                     * discovery metadata, but its minute/glucose fields are
                     * deliberately never persisted as clinical data.
                     */
                    insertReading(
                        reading =
                            reading.copy(
                                serial =
                                    serial
                            ),
                        conflictAlgorithm =
                            SQLiteDatabase
                                .CONFLICT_REPLACE
                    )

                    /*
                     * AiDEX advertisements contain the two preceding glucose
                     * values. Insert those minutes if they are missing. They
                     * immediately fill short Bluetooth gaps without waiting
                     * for a GATT history connection.
                     */
                    insertAdvertisementBackfill(
                        reading.copy(
                            serial =
                                serial
                        )
                    )
                }

            writableDatabase
                .setTransactionSuccessful()
        } finally {
            writableDatabase
                .endTransaction()
        }

        val liveReading =
            sensor.reading

        if (
            liveReading != null &&
            liveReading.sensorMinute !=
                previousSensorMinute
        ) {
            HistoryDatabaseEvents
                .notifyChanged(
                    serial =
                        serial,
                    reason =
                        "live_sensor_packet"
                )
        }
    }

    /**
     * Saves authenticated standard CGM history records returned by RACP.
     * RACP records replace advertisement-derived placeholders for the same
     * sensor minute.
     */
    fun saveBackfillReadings(
        readings: List<AidexReading>
    ): Int {
        if (
            readings.isEmpty()
        ) {
            return 0
        }

        var saved = 0

        writableDatabase
            .beginTransaction()

        try {
            readings.forEach {
                    reading ->
                val result =
                    insertReading(
                        reading =
                            reading.copy(
                                serial =
                                    reading
                                        .serial
                                        .uppercase()
                            ),
                        conflictAlgorithm =
                            SQLiteDatabase
                                .CONFLICT_REPLACE
                    )

                if (
                    result != -1L
                ) {
                    saved += 1
                }
            }

            writableDatabase
                .setTransactionSuccessful()
        } finally {
            writableDatabase
                .endTransaction()
        }

        readings
            .firstOrNull()
            ?.serial
            ?.let {
                    serial ->
                HistoryDatabaseEvents
                    .notifyChanged(
                        serial =
                            serial,
                        reason =
                            "encrypted_history_backfill"
                    )
            }

        return saved
    }

    private fun insertAdvertisementBackfill(
        current: AidexReading
    ) {
        if (
            !current.crcValid ||
            !current.valid
        ) {
            return
        }

        val previous =
            current.previousMgDl

        if (
            previous != null &&
            current.sensorMinute >= 1
        ) {
            val previous2 =
                current.previous2MgDl

            val previousTrend =
                if (
                    previous2 != null
                ) {
                    (
                        previous -
                            previous2
                    ) * 10
                } else {
                    0
                }

            insertReading(
                AidexReading(
                    serial =
                        current.serial,
                    sensorMinute =
                        current.sensorMinute -
                            1,
                    glucoseMgDl =
                        previous,
                    trendRaw =
                        previousTrend,
                    quality =
                        current.quality,
                    valid =
                        true,
                    previousMgDl =
                        previous2,
                    previous2MgDl =
                        null,
                    crcValid =
                        true,
                    rssi =
                        current.rssi,
                    receivedAtMs =
                        current.receivedAtMs -
                            60_000L
                ),
                SQLiteDatabase
                    .CONFLICT_IGNORE
            )
        }

        val previous2 =
            current.previous2MgDl

        if (
            previous2 != null &&
            current.sensorMinute >= 2
        ) {
            insertReading(
                AidexReading(
                    serial =
                        current.serial,
                    sensorMinute =
                        current.sensorMinute -
                            2,
                    glucoseMgDl =
                        previous2,
                    trendRaw =
                        0,
                    quality =
                        current.quality,
                    valid =
                        true,
                    previousMgDl =
                        null,
                    previous2MgDl =
                        null,
                    crcValid =
                        true,
                    rssi =
                        current.rssi,
                    receivedAtMs =
                        current.receivedAtMs -
                            120_000L
                ),
                SQLiteDatabase
                    .CONFLICT_IGNORE
            )
        }
    }

    private fun insertReading(
        reading: AidexReading,
        conflictAlgorithm: Int
    ): Long {
        val values =
            ContentValues().apply {
                put(
                    "serial",
                    reading.serial
                        .uppercase()
                )
                put(
                    "sensor_minute",
                    reading.sensorMinute
                )
                put(
                    "received_at_ms",
                    reading.receivedAtMs
                )
                put(
                    "glucose_mgdl",
                    reading.glucoseMgDl
                )
                put(
                    "trend_raw",
                    reading.trendRaw
                )
                put(
                    "quality",
                    reading.quality
                )
                put(
                    "valid",
                    if (
                        reading.valid
                    ) {
                        1
                    } else {
                        0
                    }
                )

                if (
                    reading.previousMgDl ==
                    null
                ) {
                    putNull(
                        "previous_mgdl"
                    )
                } else {
                    put(
                        "previous_mgdl",
                        reading.previousMgDl
                    )
                }

                if (
                    reading.previous2MgDl ==
                    null
                ) {
                    putNull(
                        "previous2_mgdl"
                    )
                } else {
                    put(
                        "previous2_mgdl",
                        reading.previous2MgDl
                    )
                }

                put(
                    "crc_valid",
                    if (
                        reading.crcValid
                    ) {
                        1
                    } else {
                        0
                    }
                )

                put(
                    "rssi",
                    reading.rssi
                )
            }

        return writableDatabase
            .insertWithOnConflict(
                "readings",
                null,
                values,
                conflictAlgorithm
            )
    }

    /**
     * Finds recoverable holes in the recent CGM sensor-minute timeline.
     *
     * Warm-up minutes 0-59 are intentionally ignored. The newest two sensor
     * minutes are also ignored so normal BLE advertisements and their
     * previous-1/previous-2 values have a chance to arrive before encrypted
     * F002 recovery is started.
     *
     * Only continuous holes of at least [minimumGapMinutes] are returned.
     * Small one/two-minute gaps are normally repaired by the advertisement
     * previous-value path.
     */
    fun findRecoverableGaps(
        serial: String,
        currentSensorMinute: Int,
        windowMinutes: Int =
            72 * 60,
        graceMinutes: Int =
            2,
        minimumGapMinutes: Int =
            3
    ): HistoryGapSummary {
        val normalized =
            serial.uppercase()

        val current =
            currentSensorMinute
                .coerceAtLeast(
                    0
                )

        val windowStart =
            maxOf(
                FIRST_VALID_SENSOR_MINUTE,
                current -
                    windowMinutes
                        .coerceAtLeast(
                            1
                        ) +
                    1
            )

        val windowEnd =
            current -
                graceMinutes
                    .coerceAtLeast(
                        0
                    )

        if (
            windowEnd <
            windowStart
        ) {
            return HistoryGapSummary(
                serial =
                    normalized,
                currentSensorMinute =
                    current,
                windowStartMinute =
                    windowStart,
                windowEndMinute =
                    windowEnd,
                missingMinutes =
                    0,
                gaps =
                    emptyList()
            )
        }

        val present =
            ArrayList<Int>()

        readableDatabase.query(
            "readings",
            arrayOf(
                "sensor_minute"
            ),
            "serial = ? AND sensor_minute BETWEEN ? AND ? AND valid = 1 AND crc_valid = 1",
            arrayOf(
                normalized,
                windowStart.toString(),
                windowEnd.toString()
            ),
            null,
            null,
            "sensor_minute ASC"
        ).use {
                cursor ->
            while (
                cursor.moveToNext()
            ) {
                present +=
                    cursor.getInt(
                        0
                    )
            }
        }

        return HistoryGapCalculator
            .calculate(
                serial =
                    normalized,
                currentSensorMinute =
                    current,
                presentMinutes =
                    present,
                windowMinutes =
                    windowMinutes,
                graceMinutes =
                    graceMinutes,
                minimumGapMinutes =
                    minimumGapMinutes,
                firstValidSensorMinute =
                    FIRST_VALID_SENSOR_MINUTE
            )
    }

    /**
     * Aggregates Time in Range directly from the persistent SQLite history.
     * This intentionally does not depend on the 14-day in-memory graph cache,
     * so 30/90/120/180-day reports remain available.
     *
     * Only accepted readings (valid=1, crc_valid=1) are included.
     */
    /**
     * Builds a 24-hour Daily Patterns profile directly from persistent
     * accepted SQLite history.
     *
     * Readings are grouped into 30-minute local-time buckets. Each bucket
     * returns the 10th, 25th, 50th, 75th and 90th percentiles so the UI can
     * render a median curve with inner and outer variability bands.
     */
    /**
     * Builds the Low Glucose Events, High Glucose Events and Average Glucose
     * reports from accepted persistent history.
     *
     * Event counts represent excursions, not every minute spent outside the
     * target. A low/high event begins when an accepted reading crosses into
     * that configured range after being outside it. A data gap over 15 minutes
     * resets event continuity.
     */
    /**
     * Calculates Sensor Usage from accepted persistent sensor-minute history.
     *
     * "Total Readings" is used rather than "Total Scans" because AiDEX X
     * Reader receives CGM data automatically.
     *
     * Capture percentage is the number of accepted unique sensor-minute
     * readings divided by the expected minute span between the first and last
     * accepted reading inside the selected report window.
     */
    fun calculateSensorUsageReport(
        serial: String,
        days: Int,
        nowMs: Long =
            System.currentTimeMillis()
    ): SensorUsageReport {
        val normalized =
            serial.uppercase()

        val reportDays =
            days.coerceIn(
                1,
                MAX_REPORT_DAYS
            )

        val zone =
            ZoneId.systemDefault()

        val today =
            Instant
                .ofEpochMilli(
                    nowMs
                )
                .atZone(
                    zone
                )
                .toLocalDate()

        val firstDate =
            today.minusDays(
                reportDays
                    .toLong() -
                    1L
            )

        val windowStartMs =
            firstDate
                .atStartOfDay(
                    zone
                )
                .toInstant()
                .toEpochMilli()

        val daysWithData =
            linkedSetOf<LocalDate>()

        var total =
            0

        var firstSensorMinute:
            Int? =
            null

        var lastSensorMinute:
            Int? =
            null

        readableDatabase.query(
            "readings",
            arrayOf(
                "received_at_ms",
                "sensor_minute"
            ),
            "serial = ? AND received_at_ms >= ? AND received_at_ms <= ? AND valid = 1 AND crc_valid = 1 AND glucose_mgdl > 0",
            arrayOf(
                normalized,
                windowStartMs.toString(),
                nowMs.toString()
            ),
            null,
            null,
            "sensor_minute ASC"
        ).use {
                cursor ->
            while (
                cursor.moveToNext()
            ) {
                val receivedAtMs =
                    cursor.getLong(
                        0
                    )

                val sensorMinute =
                    cursor.getInt(
                        1
                    )

                daysWithData +=
                    Instant
                        .ofEpochMilli(
                            receivedAtMs
                        )
                        .atZone(
                            zone
                        )
                        .toLocalDate()

                if (
                    firstSensorMinute ==
                    null
                ) {
                    firstSensorMinute =
                        sensorMinute
                }

                lastSensorMinute =
                    sensorMinute

                total +=
                    1
            }
        }

        val firstMinute =
            firstSensorMinute

        val lastMinute =
            lastSensorMinute

        val expected =
            SensorUsageCalculator
                .expectedMinutes(
                    firstSensorMinute =
                        firstMinute,
                    lastSensorMinute =
                        lastMinute
                )

        val captured =
            SensorUsageCalculator
                .capturedPercent(
                    acceptedReadings =
                        total,
                    expectedMinutes =
                        expected
                )

        return SensorUsageReport(
            serial =
                normalized,
            days =
                reportDays,
            windowStartMs =
                windowStartMs,
            windowEndMs =
                nowMs,
            availableDays =
                daysWithData.size,
            totalReadings =
                total,
            readingsPerDay =
                total.toDouble() /
                    reportDays
                        .toDouble(),
            expectedSensorMinutes =
                expected,
            capturedPercent =
                captured,
            firstSensorMinute =
                firstMinute,
            lastSensorMinute =
                lastMinute
        )
    }

    fun calculateGlucosePeriodSummaryReport(
        serial: String,
        days: Int,
        lowMmol: Double,
        highMmol: Double,
        nowMs: Long =
            System.currentTimeMillis()
    ): GlucosePeriodSummaryReport {
        val normalized =
            serial.uppercase()

        val reportDays =
            days.coerceIn(
                1,
                MAX_REPORT_DAYS
            )

        val zone =
            ZoneId.systemDefault()

        val today =
            Instant
                .ofEpochMilli(
                    nowMs
                )
                .atZone(
                    zone
                )
                .toLocalDate()

        val firstDate =
            today.minusDays(
                reportDays
                    .toLong() -
                    1L
            )

        val windowStartMs =
            firstDate
                .atStartOfDay(
                    zone
                )
                .toInstant()
                .toEpochMilli()

        val calibrationEvents =
            loadCalibrations(
                normalized
            )

        val samples =
            ArrayList<GlucoseSummarySample>()

        readableDatabase.query(
            "readings",
            arrayOf(
                "received_at_ms",
                "glucose_mgdl"
            ),
            "serial = ? AND received_at_ms >= ? AND received_at_ms <= ? AND valid = 1 AND crc_valid = 1 AND glucose_mgdl > 0",
            arrayOf(
                normalized,
                windowStartMs.toString(),
                nowMs.toString()
            ),
            null,
            null,
            "received_at_ms ASC"
        ).use {
                cursor ->
            while (
                cursor.moveToNext()
            ) {
                val receivedAtMs =
                    cursor.getLong(
                        0
                    )

                val rawMgDl =
                    cursor.getInt(
                        1
                    )

                samples +=
                    GlucoseSummarySample(
                        receivedAtMs =
                            receivedAtMs,
                        glucoseMgDl =
                            CalibrationTimeline
                                .applyMgDl(
                                    rawMgDl =
                                        rawMgDl
                                            .toDouble(),
                                    receivedAtMs =
                                        receivedAtMs,
                                    events =
                                        calibrationEvents
                                )
                                .let {
                                    kotlin.math.round(
                                        it
                                    )
                                        .toInt()
                                }
                    )
            }
        }

        return GlucosePeriodSummaryCalculator
            .calculate(
                serial =
                    normalized,
                days =
                    reportDays,
                samples =
                    samples,
                lowMmol =
                    lowMmol,
                highMmol =
                    highMmol,
                windowStartMs =
                    windowStartMs,
                windowEndMs =
                    nowMs,
                zone =
                    zone
            )
    }

    fun calculateDailyPatternReport(
        serial: String,
        days: Int,
        nowMs: Long =
            System.currentTimeMillis()
    ): DailyPatternReport {
        val normalized =
            serial.uppercase()

        val reportDays =
            days.coerceIn(
                1,
                MAX_REPORT_DAYS
            )

        val zone =
            ZoneId.systemDefault()

        val today =
            Instant
                .ofEpochMilli(
                    nowMs
                )
                .atZone(
                    zone
                )
                .toLocalDate()

        val firstDate =
            today.minusDays(
                reportDays
                    .toLong() -
                    1L
            )

        val windowStartMs =
            firstDate
                .atStartOfDay(
                    zone
                )
                .toInstant()
                .toEpochMilli()

        val calibrationEvents =
            loadCalibrations(
                normalized
            )

        val buckets =
            Array(
                DAILY_PATTERN_BUCKET_COUNT
            ) {
                ArrayList<Double>()
            }

        val daysWithData =
            linkedSetOf<LocalDate>()

        var total =
            0

        readableDatabase.query(
            "readings",
            arrayOf(
                "received_at_ms",
                "glucose_mgdl"
            ),
            "serial = ? AND received_at_ms >= ? AND received_at_ms <= ? AND valid = 1 AND crc_valid = 1 AND glucose_mgdl > 0",
            arrayOf(
                normalized,
                windowStartMs.toString(),
                nowMs.toString()
            ),
            null,
            null,
            "received_at_ms ASC"
        ).use {
                cursor ->
            while (
                cursor.moveToNext()
            ) {
                val receivedAtMs =
                    cursor.getLong(
                        0
                    )

                val rawMgDl =
                    cursor.getInt(
                        1
                    )

                val glucoseMgDl =
                    CalibrationTimeline
                        .applyMgDl(
                            rawMgDl =
                                rawMgDl
                                    .toDouble(),
                            receivedAtMs =
                                receivedAtMs,
                            events =
                                calibrationEvents
                        )

                val local =
                    Instant
                        .ofEpochMilli(
                            receivedAtMs
                        )
                        .atZone(
                            zone
                        )

                daysWithData +=
                    local.toLocalDate()

                val minuteOfDay =
                    local.hour *
                        60 +
                        local.minute

                val bucket =
                    (
                        minuteOfDay /
                            DAILY_PATTERN_BUCKET_MINUTES
                    )
                        .coerceIn(
                            0,
                            DAILY_PATTERN_BUCKET_COUNT -
                                1
                        )

                buckets[
                    bucket
                ] +=
                    glucoseMgDl
                        .toDouble()

                total +=
                    1
            }
        }

        val points =
            buckets
                .mapIndexedNotNull {
                        index,
                        values ->
                    if (
                        values.isEmpty()
                    ) {
                        return@mapIndexedNotNull null
                    }

                    val sorted =
                        values.sorted()

                    DailyPatternPoint(
                        minuteOfDay =
                            index *
                                DAILY_PATTERN_BUCKET_MINUTES +
                                DAILY_PATTERN_BUCKET_MINUTES /
                                2,
                        p10MgDl =
                            percentile(
                                sorted,
                                0.10
                            ),
                        p25MgDl =
                            percentile(
                                sorted,
                                0.25
                            ),
                        medianMgDl =
                            percentile(
                                sorted,
                                0.50
                            ),
                        p75MgDl =
                            percentile(
                                sorted,
                                0.75
                            ),
                        p90MgDl =
                            percentile(
                                sorted,
                                0.90
                            ),
                        sampleCount =
                            sorted.size
                    )
                }

        return DailyPatternReport(
            serial =
                normalized,
            days =
                reportDays,
            windowStartMs =
                windowStartMs,
            windowEndMs =
                nowMs,
            availableDays =
                daysWithData.size,
            totalReadings =
                total,
            bucketMinutes =
                DAILY_PATTERN_BUCKET_MINUTES,
            points =
                points
        )
    }

    private fun percentile(
        sorted: List<Double>,
        fraction: Double
    ): Double {
        if (
            sorted.isEmpty()
        ) {
            return 0.0
        }

        if (
            sorted.size ==
            1
        ) {
            return sorted[0]
        }

        val position =
            (
                sorted.size -
                    1
            ) *
                fraction
                    .coerceIn(
                        0.0,
                        1.0
                    )

        val lowerIndex =
            position
                .toInt()
                .coerceIn(
                    0,
                    sorted.lastIndex
                )

        val upperIndex =
            (
                lowerIndex +
                    1
            )
                .coerceAtMost(
                    sorted.lastIndex
                )

        if (
            lowerIndex ==
            upperIndex
        ) {
            return sorted[
                lowerIndex
            ]
        }

        val weight =
            position -
                lowerIndex

        return sorted[
            lowerIndex
        ] *
            (
                1.0 -
                    weight
            ) +
            sorted[
                upperIndex
            ] *
            weight
    }

    fun calculateTimeInRangeReport(
        serial: String,
        days: Int,
        lowMmol: Double,
        highMmol: Double,
        nowMs: Long =
            System.currentTimeMillis()
    ): TimeInRangeReport {
        val normalized =
            serial.uppercase()

        val reportDays =
            days.coerceIn(
                1,
                MAX_REPORT_DAYS
            )

        val zone =
            ZoneId.systemDefault()

        val today =
            Instant
                .ofEpochMilli(
                    nowMs
                )
                .atZone(
                    zone
                )
                .toLocalDate()

        val firstDate =
            today.minusDays(
                reportDays
                    .toLong() -
                    1L
            )

        val windowStartMs =
            firstDate
                .atStartOfDay(
                    zone
                )
                .toInstant()
                .toEpochMilli()

        val windowEndMs =
            nowMs

        val calibrationEvents =
            loadCalibrations(
                normalized
            )

        val daysWithData =
            linkedSetOf<LocalDate>()

        var low =
            0

        var target =
            0

        var high =
            0

        var veryHigh =
            0

        readableDatabase.query(
            "readings",
            arrayOf(
                "received_at_ms",
                "glucose_mgdl"
            ),
            "serial = ? AND received_at_ms >= ? AND received_at_ms <= ? AND valid = 1 AND crc_valid = 1 AND glucose_mgdl > 0",
            arrayOf(
                normalized,
                windowStartMs.toString(),
                windowEndMs.toString()
            ),
            null,
            null,
            "received_at_ms ASC"
        ).use {
                cursor ->
            while (
                cursor.moveToNext()
            ) {
                val receivedAtMs =
                    cursor.getLong(
                        0
                    )

                val rawMgDl =
                    cursor.getInt(
                        1
                    )

                val glucoseMgDl =
                    CalibrationTimeline
                        .applyMgDl(
                            rawMgDl =
                                rawMgDl
                                    .toDouble(),
                            receivedAtMs =
                                receivedAtMs,
                            events =
                                calibrationEvents
                        )

                daysWithData +=
                    Instant
                        .ofEpochMilli(
                            receivedAtMs
                        )
                        .atZone(
                            zone
                        )
                        .toLocalDate()

                val mmol =
                    glucoseMgDl /
                        18.0

                val displayedMmol =
                    round(
                        mmol *
                            10.0
                    ) /
                        10.0

                when {
                    mmol <=
                        lowMmol ->
                        low +=
                            1

                    mmol <=
                        highMmol ->
                        target +=
                            1

                    displayedMmol <
                        VERY_HIGH_MMOL ->
                        high +=
                            1

                    else ->
                        veryHigh +=
                            1
                }
            }
        }

        val total =
            low +
                target +
                high +
                veryHigh

        return TimeInRangeReport(
            serial =
                normalized,
            days =
                reportDays,
            windowStartMs =
                windowStartMs,
            windowEndMs =
                windowEndMs,
            availableDays =
                daysWithData.size,
            totalReadings =
                total,
            lowReadings =
                low,
            targetReadings =
                target,
            highReadings =
                high,
            veryHighReadings =
                veryHigh
        )
    }

    fun loadRecentReadings(
        serial: String,
        days: Int
    ): List<AidexReading> {
        val cutoff = System.currentTimeMillis() - days.coerceAtLeast(1) * DAY_MS

        readableDatabase.query(
            "readings",
            READING_COLUMNS,
            "serial = ? AND received_at_ms >= ?",
            arrayOf(serial.uppercase(), cutoff.toString()),
            null,
            null,
            "received_at_ms ASC"
        ).use { cursor ->
            val result = ArrayList<AidexReading>()
            while (cursor.moveToNext()) {
                result += cursorToReading(cursor)
            }
            return result
        }
    }

    fun loadRecentReadingsAllSensors(
        days: Int
    ): List<AidexReading> {
        val cutoff =
            System.currentTimeMillis() -
                days.coerceAtLeast(1) *
                    DAY_MS

        readableDatabase.query(
            "readings",
            READING_COLUMNS,
            "received_at_ms >= ?",
            arrayOf(
                cutoff.toString()
            ),
            null,
            null,
            "received_at_ms ASC"
        ).use { cursor ->
            val result =
                ArrayList<AidexReading>()

            while (
                cursor.moveToNext()
            ) {
                result +=
                    cursorToReading(
                        cursor
                    )
            }

            return result
        }
    }

    fun loadRecentCalibratedReadingsAllSensors(
        days: Int
    ): List<AidexReading> {
        val calibrationCache =
            mutableMapOf<
                String,
                List<CalibrationEvent>
            >()

        return loadRecentReadingsAllSensors(
            days
        ).map { reading ->
            val serial =
                reading.serial
                    .uppercase()

            val events =
                calibrationCache
                    .getOrPut(
                        serial
                    ) {
                        loadCalibrations(
                            serial
                        )
                    }

            CalibrationTimeline
                .applyReading(
                    reading =
                        reading,
                    events =
                        events
                )
        }
    }

    private fun reportWindowStartMs(
        days: Int,
        nowMs: Long
    ): Pair<Int, Long> {
        val reportDays =
            days.coerceIn(
                1,
                MAX_REPORT_DAYS
            )

        val zone =
            ZoneId.systemDefault()

        val today =
            Instant
                .ofEpochMilli(
                    nowMs
                )
                .atZone(
                    zone
                )
                .toLocalDate()

        val firstDate =
            today.minusDays(
                reportDays
                    .toLong() -
                    1L
            )

        val windowStartMs =
            firstDate
                .atStartOfDay(
                    zone
                )
                .toInstant()
                .toEpochMilli()

        return reportDays to
            windowStartMs
    }

    private fun loadCalibratedSamplesAllSensors(
        windowStartMs: Long,
        windowEndMs: Long
    ): List<CalibratedSample> {
        data class RawSample(
            val serial: String,
            val receivedAtMs: Long,
            val glucoseMgDl: Int
        )

        val raw =
            ArrayList<RawSample>()

        readableDatabase.query(
            "readings",
            arrayOf(
                "serial",
                "received_at_ms",
                "glucose_mgdl"
            ),
            "received_at_ms >= ? AND received_at_ms <= ? AND valid = 1 AND crc_valid = 1 AND glucose_mgdl > 0",
            arrayOf(
                windowStartMs.toString(),
                windowEndMs.toString()
            ),
            null,
            null,
            "received_at_ms ASC"
        ).use { cursor ->
            while (
                cursor.moveToNext()
            ) {
                raw +=
                    RawSample(
                        serial =
                            cursor
                                .getString(
                                    0
                                )
                                .uppercase(),
                        receivedAtMs =
                            cursor.getLong(
                                1
                            ),
                        glucoseMgDl =
                            cursor.getInt(
                                2
                            )
                    )
            }
        }

        val calibrationCache =
            mutableMapOf<
                String,
                List<CalibrationEvent>
            >()

        return raw.map {
                sample ->
            val events =
                calibrationCache
                    .getOrPut(
                        sample.serial
                    ) {
                        loadCalibrations(
                            sample.serial
                        )
                    }

            CalibratedSample(
                serial =
                    sample.serial,
                receivedAtMs =
                    sample.receivedAtMs,
                glucoseMgDl =
                    CalibrationTimeline
                        .applyMgDl(
                            rawMgDl =
                                sample
                                    .glucoseMgDl
                                    .toDouble(),
                            receivedAtMs =
                                sample
                                    .receivedAtMs,
                            events =
                                events
                        )
            )
        }
    }

    fun calculateGlucosePeriodSummaryReportAllSensors(
        days: Int,
        lowMmol: Double,
        highMmol: Double,
        nowMs: Long =
            System.currentTimeMillis()
    ): GlucosePeriodSummaryReport {
        val (
            reportDays,
            windowStartMs
        ) =
            reportWindowStartMs(
                days,
                nowMs
            )

        val samples =
            loadCalibratedSamplesAllSensors(
                windowStartMs,
                nowMs
            ).map {
                    sample ->
                GlucoseSummarySample(
                    receivedAtMs =
                        sample.receivedAtMs,
                    glucoseMgDl =
                        kotlin.math
                            .round(
                                sample.glucoseMgDl
                            )
                            .toInt()
                )
            }

        return GlucosePeriodSummaryCalculator
            .calculate(
                serial =
                    ALL_SENSORS_SERIAL,
                days =
                    reportDays,
                samples =
                    samples,
                lowMmol =
                    lowMmol,
                highMmol =
                    highMmol,
                windowStartMs =
                    windowStartMs,
                windowEndMs =
                    nowMs,
                zone =
                    ZoneId.systemDefault()
            )
    }

    fun calculateDailyPatternReportAllSensors(
        days: Int,
        nowMs: Long =
            System.currentTimeMillis()
    ): DailyPatternReport {
        val (
            reportDays,
            windowStartMs
        ) =
            reportWindowStartMs(
                days,
                nowMs
            )

        val zone =
            ZoneId.systemDefault()

        val buckets =
            Array(
                DAILY_PATTERN_BUCKET_COUNT
            ) {
                ArrayList<Double>()
            }

        val daysWithData =
            linkedSetOf<LocalDate>()

        val samples =
            loadCalibratedSamplesAllSensors(
                windowStartMs,
                nowMs
            )

        samples.forEach {
                sample ->
            val local =
                Instant
                    .ofEpochMilli(
                        sample.receivedAtMs
                    )
                    .atZone(
                        zone
                    )

            daysWithData +=
                local.toLocalDate()

            val minuteOfDay =
                local.hour *
                    60 +
                    local.minute

            val bucket =
                (
                    minuteOfDay /
                        DAILY_PATTERN_BUCKET_MINUTES
                )
                    .coerceIn(
                        0,
                        DAILY_PATTERN_BUCKET_COUNT -
                            1
                    )

            buckets[
                bucket
            ] +=
                sample.glucoseMgDl
        }

        val points =
            buckets
                .mapIndexedNotNull {
                        index,
                        values ->
                    if (
                        values.isEmpty()
                    ) {
                        return@mapIndexedNotNull null
                    }

                    val sorted =
                        values.sorted()

                    DailyPatternPoint(
                        minuteOfDay =
                            index *
                                DAILY_PATTERN_BUCKET_MINUTES +
                                DAILY_PATTERN_BUCKET_MINUTES /
                                2,
                        p10MgDl =
                            percentile(
                                sorted,
                                0.10
                            ),
                        p25MgDl =
                            percentile(
                                sorted,
                                0.25
                            ),
                        medianMgDl =
                            percentile(
                                sorted,
                                0.50
                            ),
                        p75MgDl =
                            percentile(
                                sorted,
                                0.75
                            ),
                        p90MgDl =
                            percentile(
                                sorted,
                                0.90
                            ),
                        sampleCount =
                            sorted.size
                    )
                }

        return DailyPatternReport(
            serial =
                ALL_SENSORS_SERIAL,
            days =
                reportDays,
            windowStartMs =
                windowStartMs,
            windowEndMs =
                nowMs,
            availableDays =
                daysWithData.size,
            totalReadings =
                samples.size,
            bucketMinutes =
                DAILY_PATTERN_BUCKET_MINUTES,
            points =
                points
        )
    }

    fun calculateTimeInRangeReportAllSensors(
        days: Int,
        lowMmol: Double,
        highMmol: Double,
        nowMs: Long =
            System.currentTimeMillis()
    ): TimeInRangeReport {
        val (
            reportDays,
            windowStartMs
        ) =
            reportWindowStartMs(
                days,
                nowMs
            )

        val zone =
            ZoneId.systemDefault()

        val daysWithData =
            linkedSetOf<LocalDate>()

        var low = 0
        var target = 0
        var high = 0
        var veryHigh = 0

        loadCalibratedSamplesAllSensors(
            windowStartMs,
            nowMs
        ).forEach {
                sample ->
            daysWithData +=
                Instant
                    .ofEpochMilli(
                        sample.receivedAtMs
                    )
                    .atZone(
                        zone
                    )
                    .toLocalDate()

            val mmol =
                sample.glucoseMgDl /
                    18.0

            val displayedMmol =
                round(
                    mmol *
                        10.0
                ) /
                    10.0

            when {
                mmol <=
                    lowMmol ->
                    low += 1

                mmol <=
                    highMmol ->
                    target += 1

                displayedMmol <
                    VERY_HIGH_MMOL ->
                    high += 1

                else ->
                    veryHigh += 1
            }
        }

        val total =
            low +
                target +
                high +
                veryHigh

        return TimeInRangeReport(
            serial =
                ALL_SENSORS_SERIAL,
            days =
                reportDays,
            windowStartMs =
                windowStartMs,
            windowEndMs =
                nowMs,
            availableDays =
                daysWithData.size,
            totalReadings =
                total,
            lowReadings =
                low,
            targetReadings =
                target,
            highReadings =
                high,
            veryHighReadings =
                veryHigh
        )
    }

    fun calculateSensorUsageReportAllSensors(
        days: Int,
        nowMs: Long =
            System.currentTimeMillis()
    ): SensorUsageReport {
        val (
            reportDays,
            windowStartMs
        ) =
            reportWindowStartMs(
                days,
                nowMs
            )

        val zone =
            ZoneId.systemDefault()

        val daysWithData =
            linkedSetOf<LocalDate>()

        val ranges =
            linkedMapOf<
                String,
                Pair<Int, Int>
            >()

        var total = 0

        readableDatabase.query(
            "readings",
            arrayOf(
                "serial",
                "received_at_ms",
                "sensor_minute"
            ),
            "received_at_ms >= ? AND received_at_ms <= ? AND valid = 1 AND crc_valid = 1 AND glucose_mgdl > 0",
            arrayOf(
                windowStartMs.toString(),
                nowMs.toString()
            ),
            null,
            null,
            "received_at_ms ASC"
        ).use { cursor ->
            while (
                cursor.moveToNext()
            ) {
                val serial =
                    cursor
                        .getString(
                            0
                        )
                        .uppercase()

                val receivedAtMs =
                    cursor.getLong(
                        1
                    )

                val sensorMinute =
                    cursor.getInt(
                        2
                    )

                daysWithData +=
                    Instant
                        .ofEpochMilli(
                            receivedAtMs
                        )
                        .atZone(
                            zone
                        )
                        .toLocalDate()

                val previous =
                    ranges[
                        serial
                    ]

                ranges[
                    serial
                ] =
                    if (
                        previous ==
                        null
                    ) {
                        sensorMinute to
                            sensorMinute
                    } else {
                        minOf(
                            previous.first,
                            sensorMinute
                        ) to
                            maxOf(
                                previous.second,
                                sensorMinute
                            )
                    }

                total += 1
            }
        }

        val expected =
            ranges.values
                .sumOf {
                        range ->
                    SensorUsageCalculator
                        .expectedMinutes(
                            firstSensorMinute =
                                range.first,
                            lastSensorMinute =
                                range.second
                        )
                }

        val captured =
            SensorUsageCalculator
                .capturedPercent(
                    acceptedReadings =
                        total,
                    expectedMinutes =
                        expected
                )

        return SensorUsageReport(
            serial =
                ALL_SENSORS_SERIAL,
            days =
                reportDays,
            windowStartMs =
                windowStartMs,
            windowEndMs =
                nowMs,
            availableDays =
                daysWithData.size,
            totalReadings =
                total,
            readingsPerDay =
                total.toDouble() /
                    reportDays
                        .toDouble(),
            expectedSensorMinutes =
                expected,
            capturedPercent =
                captured,
            firstSensorMinute =
                null,
            lastSensorMinute =
                null
        )
    }

    fun loadSavedSensors(): List<AidexSensor> {
        readableDatabase.query(
            "sensors",
            arrayOf(
                "serial",
                "name",
                "address",
                "last_rssi"
            ),
            null,
            null,
            null,
            null,
            "last_seen_ms DESC"
        ).use { cursor ->
            val sensors = ArrayList<AidexSensor>()
            while (cursor.moveToNext()) {
                val serial = cursor.getString(0)
                val name = cursor.getString(1)
                val address = cursor.getString(2)
                val rssi = cursor.getInt(3)

                sensors += AidexSensor(
                    name = name,
                    serial = serial,
                    address = address,
                    rssi = rssi,
                    reading = loadLatestReading(serial)
                )
            }
            return sensors
        }
    }

    fun loadLatestReading(serial: String): AidexReading? {
        readableDatabase.query(
            "readings",
            READING_COLUMNS,
            "serial = ? AND crc_valid = 1",
            arrayOf(serial.uppercase()),
            null,
            null,
            "received_at_ms DESC",
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursorToReading(cursor)
            } else {
                null
            }
        }
    }


    fun countReadings(serial: String): Long {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM readings WHERE serial = ?",
            arrayOf(serial.uppercase())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    fun loadSensorFirstSeenMs(
        serial: String
    ): Long? =
        queryFirstSeen(
            serial
                .trim()
                .uppercase()
        )

    private fun queryFirstSeen(serial: String): Long? {
        readableDatabase.query(
            "sensors",
            arrayOf("first_seen_ms"),
            "serial = ?",
            arrayOf(serial),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun cursorToReading(cursor: android.database.Cursor): AidexReading {
        val serial = cursor.getString(cursor.getColumnIndexOrThrow("serial"))
        val sensorMinute = cursor.getInt(cursor.getColumnIndexOrThrow("sensor_minute"))
        val receivedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("received_at_ms"))
        val glucoseMgDl = cursor.getInt(cursor.getColumnIndexOrThrow("glucose_mgdl"))
        val trendRaw = cursor.getInt(cursor.getColumnIndexOrThrow("trend_raw"))
        val quality = cursor.getInt(cursor.getColumnIndexOrThrow("quality"))
        val valid = cursor.getInt(cursor.getColumnIndexOrThrow("valid")) != 0

        val previousIndex = cursor.getColumnIndexOrThrow("previous_mgdl")
        val previous2Index = cursor.getColumnIndexOrThrow("previous2_mgdl")

        val previous = if (cursor.isNull(previousIndex)) null else cursor.getInt(previousIndex)
        val previous2 = if (cursor.isNull(previous2Index)) null else cursor.getInt(previous2Index)

        val crcValid = cursor.getInt(cursor.getColumnIndexOrThrow("crc_valid")) != 0
        val rssi = cursor.getInt(cursor.getColumnIndexOrThrow("rssi"))

        return AidexReading(
            serial = serial,
            sensorMinute = sensorMinute,
            glucoseMgDl = glucoseMgDl,
            trendRaw = trendRaw,
            quality = quality,
            valid = valid,
            previousMgDl = previous,
            previous2MgDl = previous2,
            crcValid = crcValid,
            rssi = rssi,
            receivedAtMs = receivedAtMs
        )
    }

    companion object {
        const val MAX_REPORT_DAYS =
            365

        const val ALL_SENSORS_SERIAL =
            "ALL"

        private const val FIRST_VALID_SENSOR_MINUTE =
            60

        private const val DAILY_PATTERN_BUCKET_MINUTES =
            30

        private const val DAILY_PATTERN_BUCKET_COUNT =
            24 * 60 /
                DAILY_PATTERN_BUCKET_MINUTES

        private const val VERY_HIGH_MMOL =
            13.4

        private const val DATABASE_NAME =
            "aidexx_history.db"
        private const val DATABASE_VERSION =
            8
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        private val READING_COLUMNS = arrayOf(
            "serial",
            "sensor_minute",
            "received_at_ms",
            "glucose_mgdl",
            "trend_raw",
            "quality",
            "valid",
            "previous_mgdl",
            "previous2_mgdl",
            "crc_valid",
            "rssi"
        )
    }
}
