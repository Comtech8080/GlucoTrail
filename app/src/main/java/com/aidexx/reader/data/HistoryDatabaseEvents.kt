package com.aidexx.reader.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HistoryDatabaseEvent(
    val serial: String,
    val reason: String,
    val changedAtMs: Long =
        System.currentTimeMillis()
)

object HistoryDatabaseEvents {
    private val _event =
        MutableStateFlow<HistoryDatabaseEvent?>(
            null
        )

    val event:
        StateFlow<HistoryDatabaseEvent?> =
        _event.asStateFlow()

    fun notifyChanged(
        serial: String,
        reason: String
    ) {
        _event.value =
            HistoryDatabaseEvent(
                serial =
                    serial.uppercase(),
                reason =
                    reason
            )
    }
}
