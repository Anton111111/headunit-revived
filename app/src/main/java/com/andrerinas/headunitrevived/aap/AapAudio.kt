package com.andrerinas.headunitrevived.aap

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.andrerinas.headunitrevived.aap.protocol.AudioConfigs
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.decoder.AudioDecoder
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

internal class AapAudio(
        private val audioDecoder: AudioDecoder,
        private val audioManager: AudioManager,
        settings: Settings) {

    private val staticAudioFocus = settings.staticAudioFocus
    private val separateAudioStreams = settings.separateAudioStreams
    private val mediaVolumeOffset = settings.mediaVolumeOffset
    private val assistantVolumeOffset = settings.assistantVolumeOffset
    private val navigationVolumeOffset = settings.navigationVolumeOffset
    private val audioLatencyMultiplier = settings.audioLatencyMultiplier
    private val useAacAudio = settings.useAacAudio
    private val audioQueueCapacity = settings.audioQueueCapacity

    private var audioFocusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    @Volatile
    private var isDucked = false
    private val handler = Handler(Looper.getMainLooper())
    private val unduckRunnable = Runnable {
        unduckMedia()
    }

    private fun duckMedia() {
        if (!isDucked) {
            val mediaTrack = audioDecoder.getTrack(Channel.ID_AUD)
            if (mediaTrack != null) {
                val duckedVolume = getMediaGain() * DUCK_VOLUME_FACTOR
                AppLog.i("Static Audio Focus: Ducking media volume to $duckedVolume")
                mediaTrack.setVolume(duckedVolume)
                isDucked = true
            }
        }
    }

    private fun unduckMedia() {
        if (isDucked) {
            val mediaTrack = audioDecoder.getTrack(Channel.ID_AUD)
            if (mediaTrack != null) {
                val originalVolume = getMediaGain()
                AppLog.i("Static Audio Focus: Restoring media volume to $originalVolume")
                mediaTrack.setVolume(originalVolume)
            }
            isDucked = false
        }
    }

    private fun getMediaGain(): Float {
        return (1.0f + (mediaVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
    }

    fun requestFocusChange(stream: Int, focusRequest: Int, callback: AudioManager.OnAudioFocusChangeListener): Int {
        AppLog.i("Audio Focus Request: stream=$stream, type=$focusRequest")
        
        var result = AudioManager.AUDIOFOCUS_REQUEST_FAILED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26+
            if (focusRequest == Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE) {
                AppLog.i("Releasing audio focus")
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
                result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                val usage = when (stream) {
                    AudioManager.STREAM_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                    AudioManager.STREAM_VOICE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
                    else -> AudioAttributes.USAGE_MEDIA
                }

                val audioAttributes = AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(if (usage == AudioAttributes.USAGE_MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                audioFocusRequest = AudioFocusRequest.Builder(focusRequest)
                        .setAudioAttributes(audioAttributes)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(callback)
                        .build()
                
                result = audioManager.requestAudioFocus(audioFocusRequest!!)
                AppLog.i("Audio focus request result: ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED ($result)"}")
            }
        } else { // API < 26
            @Suppress("DEPRECATION")
            result = when (focusRequest) {
                Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE -> {
                    audioManager.abandonAudioFocus(callback)
                    legacyFocusListener?.let { audioManager.abandonAudioFocus(it) }
                    legacyFocusListener = null
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_VALUE -> {
                    legacyFocusListener = callback
                    audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN)
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_VALUE -> {
                    legacyFocusListener = callback
                    audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK_VALUE -> {
                    legacyFocusListener = callback
                    audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                }
                else -> AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }
            AppLog.i("Audio focus request result (legacy): ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED"}")
        }
        return result
    }

    fun releaseAllFocus() {
        AppLog.i("AapAudio: Releasing all audio focus.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyFocusListener?.let { audioManager.abandonAudioFocus(it) }
            legacyFocusListener = null
        }
    }

    /**
     * Processes a message as an audio stream packet.
     * Returns true if the packet was identified and processed as audio data, false otherwise.
     */
    fun process(message: AapMessage): Boolean {
        // Media stream packets have msgType 0 or 1.
        // Control packets on audio channels (Setup, Start, Stop) have types > 32767.
        if (message.type == 0 || message.type == 1) {
            if (message.size >= 10) {
                decode(message.channel, 10, message.data, message.size - 10)
            }
            return true
        }
        return false
    }

    private fun decode(channel: Int, start: Int, buf: ByteArray, len: Int) {
        var length = len
        if (length > AUDIO_BUFS_SIZE) {
            AppLog.e("Error audio len: %d  aud_buf_BUFS_SIZE: %d", length, AUDIO_BUFS_SIZE)
            length = AUDIO_BUFS_SIZE
        }

        if (audioDecoder.getTrack(channel) == null) {
            val config = AudioConfigs.get(channel)
            val stream = AudioConfigs.stream(channel, separateAudioStreams)

            val offset = when (channel) {
                Channel.ID_AUD -> mediaVolumeOffset
                Channel.ID_AU1 -> assistantVolumeOffset
                Channel.ID_AU2 -> navigationVolumeOffset
                else -> 0
            }
            val gain = (1.0f + (offset / 100.0f)).coerceIn(0.0f, 2.0f)

            // Voice and Navigation benefit from lower latency. Cap the multiplier for those channels.
            val effectiveMultiplier = if (channel == Channel.ID_AUD) {
                audioLatencyMultiplier
            } else {
                audioLatencyMultiplier.coerceAtMost(4)
            }

            AppLog.i("AudioDecoder.start: channel=$channel, stream=$stream, gain=$gain, sampleRate=${config.sampleRate}, numberOfBits=${config.numberOfBits}, numberOfChannels=${config.numberOfChannels}, isAac=$useAacAudio, latencyMultiplier=$effectiveMultiplier, queueCapacity=$audioQueueCapacity")
            audioDecoder.start(channel, stream, config.sampleRate, config.numberOfBits, config.numberOfChannels, useAacAudio, gain, effectiveMultiplier, audioQueueCapacity)
        }

        audioDecoder.decode(channel, buf, start, length)

        if (channel == Channel.ID_AU2 && staticAudioFocus) {
            duckMedia()
            handler.removeCallbacks(unduckRunnable)
            handler.postDelayed(unduckRunnable, UNDUCK_DELAY_MS)
        }
    }

    fun stopAudio(channel: Int) {
        AppLog.i("Audio Stop: " + Channel.name(channel))
        audioDecoder.stop(channel)
        if (channel == Channel.ID_AU2 && staticAudioFocus) {
            handler.removeCallbacks(unduckRunnable)
            unduckMedia()
        }
    }

    companion object {
        private const val AUDIO_BUFS_SIZE = 65536 * 4  // Up to 256 Kbytes
        private const val DUCK_VOLUME_FACTOR = 0.4f
        private const val UNDUCK_DELAY_MS = 1500L
    }
}
