package com.aidexx.reader.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.aidexx.reader.R

enum class AlarmSound {
    HIGH,
    LOW,
    DISCONNECTED
}

object AlarmSoundPlayer {
    private const val ALARM_DURATION_MS =
        15_000L

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private var player:
        MediaPlayer? = null

    private var previousAlarmVolume:
        Int? = null

    private var audioFocusRequest:
        AudioFocusRequest? = null

    private var legacyFocusGranted =
        false

    private var currentContext:
        Context? = null

    private var currentAlarmSound =
        AlarmSound.HIGH

    private var currentVoice =
        AlarmVoice.MALE

    private var currentTone =
        1

    private var previewToneOnly =
        false

    private val audioAttributes =
        AudioAttributes.Builder()
            .setUsage(
                AudioAttributes.USAGE_ALARM
            )
            .setContentType(
                AudioAttributes
                    .CONTENT_TYPE_SONIFICATION
            )
            .build()

    @Synchronized
    fun play(
        context: Context,
        alarmSound: AlarmSound,
        alarmVoice: AlarmVoice,
        alarmTone: Int,
        overrideVolume: Boolean
    ) {
        val appContext =
            context.applicationContext

        stop(appContext)

        currentContext =
            appContext
        currentAlarmSound =
            alarmSound
        currentVoice =
            alarmVoice
        currentTone =
            alarmTone.coerceIn(
                1,
                10
            )
        previewToneOnly =
            false

        prepareAudio(
            appContext,
            overrideVolume
        )

        playToneStage()

        vibrate(
            context = appContext,
            alarmSound = alarmSound
        )

        handler
            .removeCallbacksAndMessages(
                null
            )

        handler.postDelayed(
            {
                stop(
                    appContext
                )
            },
            ALARM_DURATION_MS
        )
    }

    @Synchronized
    fun previewTone(
        context: Context,
        alarmTone: Int,
        overrideVolume: Boolean
    ) {
        val appContext =
            context.applicationContext

        stop(appContext)

        currentContext =
            appContext
        currentTone =
            alarmTone.coerceIn(
                1,
                10
            )
        previewToneOnly =
            true

        prepareAudio(
            appContext,
            overrideVolume
        )

        playResource(
            context = appContext,
            resourceId =
                toneResource(
                    currentTone
                ),
            onComplete = {
                stop(appContext)
            }
        )
    }

    @Synchronized
    fun play(
        context: Context,
        alarmSound: AlarmSound,
        overrideVolume: Boolean
    ) {
        val saved =
            AlarmPreferences.load(
                context
            )

        play(
            context = context,
            alarmSound =
                alarmSound,
            alarmVoice =
                saved.alarmVoice,
            alarmTone =
                saved.alarmTone,
            overrideVolume =
                overrideVolume
        )
    }

    @Synchronized
    fun play(
        context: Context,
        overrideVolume: Boolean
    ) {
        val saved =
            AlarmPreferences.load(
                context
            )

        play(
            context = context,
            alarmSound =
                AlarmSound.HIGH,
            alarmVoice =
                saved.alarmVoice,
            alarmTone =
                saved.alarmTone,
            overrideVolume =
                overrideVolume
        )
    }

    @Synchronized
    fun stop(
        context: Context
    ) {
        handler
            .removeCallbacksAndMessages(
                null
            )

        releasePlayer()

        cancelVibration(
            context
        )
        restoreVolume(
            context
        )
        abandonAudioFocus(
            context
        )

        currentContext =
            null
        previewToneOnly =
            false
    }

    private fun prepareAudio(
        context: Context,
        overrideVolume: Boolean
    ) {
        val audioManager =
            context.getSystemService(
                AudioManager::class.java
            )

        if (
            overrideVolume &&
            !audioManager.isVolumeFixed
        ) {
            previousAlarmVolume =
                audioManager
                    .getStreamVolume(
                        AudioManager.STREAM_ALARM
                    )

            runCatching {
                audioManager
                    .setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        audioManager
                            .getStreamMaxVolume(
                                AudioManager.STREAM_ALARM
                            ),
                        0
                    )
            }
        }

        requestAudioFocus(
            audioManager
        )
    }

    private fun playToneStage() {
        val context =
            currentContext ?: return

        playResource(
            context = context,
            resourceId =
                toneResource(
                    currentTone
                ),
            onComplete = {
                if (
                    previewToneOnly
                ) {
                    stop(context)
                } else {
                    playVoiceStage()
                }
            }
        )
    }

    private fun playVoiceStage() {
        val context =
            currentContext ?: return

        playResource(
            context = context,
            resourceId =
                voiceResource(
                    currentAlarmSound,
                    currentVoice
                ),
            onComplete = {
                if (
                    currentContext != null
                ) {
                    playToneStage()
                }
            }
        )
    }

    private fun playResource(
        context: Context,
        resourceId: Int,
        onComplete: () -> Unit
    ) {
        releasePlayer()

        val uri =
            Uri.parse(
                "android.resource://${context.packageName}/$resourceId"
            )

        player =
            MediaPlayer().apply {
                setAudioAttributes(
                    audioAttributes
                )
                setDataSource(
                    context,
                    uri
                )
                isLooping =
                    false
                setVolume(
                    1.0f,
                    1.0f
                )

                setOnPreparedListener {
                    it.start()
                }

                setOnCompletionListener {
                    releasePlayer()
                    onComplete()
                }

                setOnErrorListener {
                        _,
                        _,
                        _ ->
                    releasePlayer()
                    true
                }

                prepareAsync()
            }
    }

    private fun releasePlayer() {
        player?.let { mp ->
            runCatching {
                if (mp.isPlaying) {
                    mp.stop()
                }
            }

            runCatching {
                mp.reset()
            }

            runCatching {
                mp.release()
            }
        }

        player =
            null
    }

    private fun toneResource(
        tone: Int
    ): Int =
        when (
            tone.coerceIn(
                1,
                10
            )
        ) {
            1 -> R.raw.aidexx_tone_01
            2 -> R.raw.aidexx_tone_02
            3 -> R.raw.aidexx_tone_03
            4 -> R.raw.aidexx_tone_04
            5 -> R.raw.aidexx_tone_05
            6 -> R.raw.aidexx_tone_06
            7 -> R.raw.aidexx_tone_07
            8 -> R.raw.aidexx_tone_08
            9 -> R.raw.aidexx_tone_09
            else ->
                R.raw.aidexx_tone_10
        }

    private fun voiceResource(
        alarmSound: AlarmSound,
        alarmVoice: AlarmVoice
    ): Int =
        when (
            alarmSound to alarmVoice
        ) {
            AlarmSound.HIGH to
                AlarmVoice.MALE ->
                R.raw.aidexx_voice_high_male

            AlarmSound.HIGH to
                AlarmVoice.FEMALE ->
                R.raw.aidexx_voice_high_female

            AlarmSound.LOW to
                AlarmVoice.MALE ->
                R.raw.aidexx_voice_low_male

            AlarmSound.LOW to
                AlarmVoice.FEMALE ->
                R.raw.aidexx_voice_low_female

            AlarmSound.DISCONNECTED to
                AlarmVoice.MALE ->
                R.raw.aidexx_voice_disconnected_male

            AlarmSound.DISCONNECTED to
                AlarmVoice.FEMALE ->
                R.raw.aidexx_voice_disconnected_female

            else ->
                R.raw.aidexx_voice_high_male
        }

    private fun requestAudioFocus(
        audioManager: AudioManager
    ) {
        if (
            Build.VERSION.SDK_INT >= 26
        ) {
            val request =
                AudioFocusRequest
                    .Builder(
                        AudioManager
                            .AUDIOFOCUS_GAIN_TRANSIENT
                    )
                    .setAudioAttributes(
                        audioAttributes
                    )
                    .setAcceptsDelayedFocusGain(
                        false
                    )
                    .setOnAudioFocusChangeListener {
                    }
                    .build()

            audioFocusRequest =
                request

            runCatching {
                audioManager
                    .requestAudioFocus(
                        request
                    )
            }
        } else {
            @Suppress("DEPRECATION")
            val result =
                audioManager
                    .requestAudioFocus(
                        { },
                        AudioManager.STREAM_ALARM,
                        AudioManager
                            .AUDIOFOCUS_GAIN_TRANSIENT
                    )

            legacyFocusGranted =
                result ==
                    AudioManager
                        .AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus(
        context: Context
    ) {
        val audioManager =
            context.getSystemService(
                AudioManager::class.java
            )

        if (
            Build.VERSION.SDK_INT >= 26
        ) {
            audioFocusRequest?.let {
                    request ->
                runCatching {
                    audioManager
                        .abandonAudioFocusRequest(
                            request
                        )
                }
            }

            audioFocusRequest =
                null
        } else if (
            legacyFocusGranted
        ) {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager
                    .abandonAudioFocus(
                        null
                    )
            }

            legacyFocusGranted =
                false
        }
    }

    private fun vibrate(
        context: Context,
        alarmSound: AlarmSound
    ) {
        val vibrator =
            getVibrator(
                context
            )

        if (
            !vibrator.hasVibrator()
        ) {
            return
        }

        val pattern =
            when (
                alarmSound
            ) {
                AlarmSound.HIGH ->
                    longArrayOf(
                        0,
                        450,
                        150,
                        450,
                        150,
                        900
                    )

                AlarmSound.LOW ->
                    longArrayOf(
                        0,
                        900,
                        180,
                        500,
                        180,
                        500
                    )

                AlarmSound.DISCONNECTED ->
                    longArrayOf(
                        0,
                        250,
                        140,
                        250,
                        140,
                        250,
                        140,
                        700
                    )
            }

        val effect =
            VibrationEffect
                .createWaveform(
                    pattern,
                    0
                )

        runCatching {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                audioAttributes
            )
        }
    }

    private fun cancelVibration(
        context: Context
    ) {
        runCatching {
            getVibrator(
                context
            ).cancel()
        }
    }

    private fun getVibrator(
        context: Context
    ): Vibrator =
        if (
            Build.VERSION.SDK_INT >= 31
        ) {
            context
                .getSystemService(
                    VibratorManager::class.java
                )
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(
                Context.VIBRATOR_SERVICE
            ) as Vibrator
        }

    private fun restoreVolume(
        context: Context
    ) {
        val oldVolume =
            previousAlarmVolume
                ?: return

        val audioManager =
            context.getSystemService(
                AudioManager::class.java
            )

        if (
            !audioManager.isVolumeFixed
        ) {
            runCatching {
                audioManager
                    .setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        oldVolume,
                        0
                    )
            }
        }

        previousAlarmVolume =
            null
    }
}
