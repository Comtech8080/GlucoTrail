package com.aidexx.reader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HistoryAutoRecoverySnapshot(
    val enabled: Boolean =
        true,
    val active: Boolean =
        false,
    val message: String =
        "Automatic 72h gap recovery ready",
    val missingMinutes: Int =
        0,
    val gapCount: Int =
        0,
    val largestGapMinutes: Int =
        0,
    val currentSensorMinute: Int =
        0,
    val windowStartMinute: Int =
        0,
    val lastCheckMs: Long =
        0L,
    val lastStartedMs: Long =
        0L,
    val lastCompletedMs: Long =
        0L,
    val lastError: String? =
        null
)

object HistoryAutoRecoveryStatus {
    private const val PREFS =
        "aidexx_reader"

    private const val KEY_MESSAGE =
        "history_auto_message"

    private const val KEY_ACTIVE =
        "history_auto_active"

    private const val KEY_MISSING =
        "history_auto_missing_minutes"

    private const val KEY_GAPS =
        "history_auto_gap_count"

    private const val KEY_LARGEST =
        "history_auto_largest_gap"

    private const val KEY_CURRENT =
        "history_auto_current_minute"

    private const val KEY_WINDOW_START =
        "history_auto_window_start"

    private const val KEY_LAST_CHECK =
        "history_auto_last_check_ms"

    private const val KEY_LAST_STARTED =
        "history_auto_last_started_ms"

    private const val KEY_LAST_COMPLETED =
        "history_auto_last_completed_ms"

    private const val KEY_LAST_ERROR =
        "history_auto_last_error"

    private val _state =
        MutableStateFlow(
            HistoryAutoRecoverySnapshot()
        )

    val state:
        StateFlow<HistoryAutoRecoverySnapshot> =
        _state.asStateFlow()

    fun initialize(
        context: Context
    ) {
        _state.value =
            read(
                context
            )
    }

    fun requested(
        context: Context,
        message: String =
            "Preparing recent history sync…"
    ) {
        update(
            context,
            _state.value.copy(
                active =
                    true,
                message =
                    message,
                lastCheckMs =
                    System.currentTimeMillis(),
                lastError =
                    null
            )
        )
    }

    fun progress(
        context: Context,
        message: String
    ) {
        update(
            context,
            _state.value.copy(
                active =
                    true,
                message =
                    message,
                lastCheckMs =
                    System.currentTimeMillis(),
                lastError =
                    null
            )
        )
    }

    fun retryScheduled(
        context: Context,
        message: String
    ) {
        update(
            context,
            _state.value.copy(
                active =
                    false,
                message =
                    message,
                lastCheckMs =
                    System.currentTimeMillis(),
                lastError =
                    message
            )
        )
    }

    fun checked(
        context: Context,
        summary: HistoryGapSummary,
        message: String
    ) {
        update(
            context,
            _state.value.copy(
                active =
                    false,
                message =
                    message,
                missingMinutes =
                    summary.missingMinutes,
                gapCount =
                    summary.gaps.size,
                largestGapMinutes =
                    summary.largestGapMinutes,
                currentSensorMinute =
                    summary.currentSensorMinute,
                windowStartMinute =
                    summary.windowStartMinute,
                lastCheckMs =
                    System.currentTimeMillis(),
                lastError =
                    null
            )
        )
    }

    fun started(
        context: Context,
        summary: HistoryGapSummary
    ) {
        update(
            context,
            _state.value.copy(
                active =
                    true,
                message =
                    "Recovering ${summary.missingMinutes} missing minutes across ${summary.gaps.size} gap(s) with encrypted F002 history.",
                missingMinutes =
                    summary.missingMinutes,
                gapCount =
                    summary.gaps.size,
                largestGapMinutes =
                    summary.largestGapMinutes,
                currentSensorMinute =
                    summary.currentSensorMinute,
                windowStartMinute =
                    summary.windowStartMinute,
                lastCheckMs =
                    System.currentTimeMillis(),
                lastStartedMs =
                    System.currentTimeMillis(),
                lastError =
                    null
            )
        )
    }

    fun completed(
        context: Context,
        summary: HistoryGapSummary,
        recoveredReadings: Int
    ) {
        update(
            context,
            _state.value.copy(
                active =
                    false,
                message =
                    if (
                        summary.gaps.isNotEmpty()
                    ) {
                        "F002 recovery saved $recoveredReadings readings. ${summary.missingMinutes} missing minutes remain."
                    } else {
                        "History is caught up. No recoverable gaps remain in the last 72h."
                    },
                missingMinutes =
                    summary.missingMinutes,
                gapCount =
                    summary.gaps.size,
                largestGapMinutes =
                    summary.largestGapMinutes,
                currentSensorMinute =
                    summary.currentSensorMinute,
                windowStartMinute =
                    summary.windowStartMinute,
                lastCheckMs =
                    System.currentTimeMillis(),
                lastCompletedMs =
                    System.currentTimeMillis(),
                lastError =
                    null
            )
        )
    }

    fun failed(
        context: Context,
        message: String
    ) {
        update(
            context,
            _state.value.copy(
                active =
                    false,
                message =
                    message,
                lastCheckMs =
                    System.currentTimeMillis(),
                lastError =
                    message
            )
        )
    }

    private fun update(
        context: Context,
        snapshot:
            HistoryAutoRecoverySnapshot
    ) {
        _state.value =
            snapshot

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putString(
                KEY_MESSAGE,
                snapshot.message
            )
            .putBoolean(
                KEY_ACTIVE,
                snapshot.active
            )
            .putInt(
                KEY_MISSING,
                snapshot.missingMinutes
            )
            .putInt(
                KEY_GAPS,
                snapshot.gapCount
            )
            .putInt(
                KEY_LARGEST,
                snapshot.largestGapMinutes
            )
            .putInt(
                KEY_CURRENT,
                snapshot.currentSensorMinute
            )
            .putInt(
                KEY_WINDOW_START,
                snapshot.windowStartMinute
            )
            .putLong(
                KEY_LAST_CHECK,
                snapshot.lastCheckMs
            )
            .putLong(
                KEY_LAST_STARTED,
                snapshot.lastStartedMs
            )
            .putLong(
                KEY_LAST_COMPLETED,
                snapshot.lastCompletedMs
            )
            .apply {
                if (
                    snapshot.lastError ==
                    null
                ) {
                    remove(
                        KEY_LAST_ERROR
                    )
                } else {
                    putString(
                        KEY_LAST_ERROR,
                        snapshot.lastError
                    )
                }
            }
            .apply()
    }

    private fun read(
        context: Context
    ): HistoryAutoRecoverySnapshot {
        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        return HistoryAutoRecoverySnapshot(
            active =
                false,
            message =
                prefs.getString(
                    KEY_MESSAGE,
                    "Automatic 72h gap recovery ready"
                )
                    ?: "Automatic 72h gap recovery ready",
            missingMinutes =
                prefs.getInt(
                    KEY_MISSING,
                    0
                ),
            gapCount =
                prefs.getInt(
                    KEY_GAPS,
                    0
                ),
            largestGapMinutes =
                prefs.getInt(
                    KEY_LARGEST,
                    0
                ),
            currentSensorMinute =
                prefs.getInt(
                    KEY_CURRENT,
                    0
                ),
            windowStartMinute =
                prefs.getInt(
                    KEY_WINDOW_START,
                    0
                ),
            lastCheckMs =
                prefs.getLong(
                    KEY_LAST_CHECK,
                    0L
                ),
            lastStartedMs =
                prefs.getLong(
                    KEY_LAST_STARTED,
                    0L
                ),
            lastCompletedMs =
                prefs.getLong(
                    KEY_LAST_COMPLETED,
                    0L
                ),
            lastError =
                prefs.getString(
                    KEY_LAST_ERROR,
                    null
                )
        )
    }
}
