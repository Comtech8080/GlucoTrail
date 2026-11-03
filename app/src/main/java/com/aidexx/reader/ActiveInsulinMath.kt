package com.aidexx.reader

import com.aidexx.reader.data.GlucoseNote

val insulinActionOptions = (120..360 step 30).toList()

data class ActiveInsulinDose(val note: GlucoseNote, val finishMs: Long) {
    fun remainingMs(now: Long) = (finishMs - now).coerceAtLeast(0L)
}

fun activeInsulinDoses(notes: List<GlucoseNote>, now: Long): List<ActiveInsulinDose> =
    notes.mapNotNull { note ->
        val minutes = note.fastInsulinActionMinutes
        if (!note.hasFastInsulin || minutes !in insulinActionOptions || note.timestampMs > now) null
        else {
            val finish = note.timestampMs + minutes!!.toLong() * 60_000L
            if (finish > now) ActiveInsulinDose(note, finish) else null
        }
    }.sortedBy { it.finishMs }

fun insulinRemainingLabel(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0L) + 59_999L) / 60_000L
    return "${minutes / 60}h ${minutes % 60}m remaining"
}

fun insulinHoursLabel(minutes: Int): String =
    if (minutes % 60 == 0) "${minutes / 60} hours" else "${minutes / 60}.5 hours"
