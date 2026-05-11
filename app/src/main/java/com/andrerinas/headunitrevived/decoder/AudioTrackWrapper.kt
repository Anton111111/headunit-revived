package com.andrerinas.headunitrevived.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.andrerinas.headunitrevived.utils.AppLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AudioTrackWrapper(
    stream: Int,
    sampleRateInHz: Int,
    bitDepth: Int,
    channelCount: Int,
    private val isAac: Boolean = false,
    gain: Float,
    private val audioLatencyMultiplier: Int = 8,
    private val audioQueueCapacity: Int = 0
) : Thread() {

    private val audioTrack: AudioTrack
    private var decoder: MediaCodec? = null
    private var codecHandlerThread: HandlerThread? = null
    private val freeInputBuffers = LinkedBlockingQueue<Int>()

    // Limit queue capacity to provide backpressure to the network thread if audio playback is slow
    private val dataQueue = if (audioQueueCapacity > 0)
        LinkedBlockingQueue<ByteArray>(audioQueueCapacity)
    else
        LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var isRunning = true

    @Volatile
    private var stopRequested = false

    private var currentGain: Float = gain

    // Track frames written for better draining
    private var framesWritten: Long = 0
    private val bytesPerFrame: Int = channelCount * (if (bitDepth == 16) 2 else 1)

    // FIX 1: Single dedicated write thread for all AudioTrack writes.
    // Both PCM and decoded AAC are funnelled here, preventing concurrent writes
    // and keeping write order deterministic.
    private val pcmQueue = LinkedBlockingQueue<ByteArray>()
    private lateinit var writeThread: Thread

    init {
        this.name = "AudioPlaybackThread"
        audioTrack = createAudioTrack(stream, sampleRateInHz, bitDepth, channelCount, audioLatencyMultiplier)
        audioTrack.play()

        if (isAac) {
            initDecoder(sampleRateInHz, channelCount)
        }

        // Initialised here so audioTrack is guaranteed to exist before the lambda captures it
        writeThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isRunning || pcmQueue.isNotEmpty()) {
                try {
                    val chunk = pcmQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    val result = audioTrack.write(chunk, 0, chunk.size)
                    if (result > 0) {
                        framesWritten += result / bytesPerFrame
                    }
                } catch (e: InterruptedException) {
                    if (!isRunning && pcmQueue.isEmpty()) break
                } catch (e: Exception) {
                    AppLog.e("Error in audio write thread", e)
                }
            }
        }, "AudioWriteThread")

        writeThread.start()
        this.start()
    }

    private fun initDecoder(sampleRate: Int, channels: Int) {
        try {
            val mime = "audio/mp4a-latm"
            val format = MediaFormat.createAudioFormat(mime, sampleRate, channels)
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            // CSD for RAW AAC-LC (AudioSpecificConfig)
            val csd = makeAacCsd(sampleRate, channels)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))

            decoder = MediaCodec.createDecoderByType(mime)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                codecHandlerThread = HandlerThread("AacCodecThread").also { it.start() }

                val callback = object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        freeInputBuffers.offer(index)
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        try {
                            val outputBuffer = codec.getOutputBuffer(index)
                            if (outputBuffer != null && info.size > 0) {
                                val chunk = ByteArray(info.size)
                                outputBuffer.position(info.offset)
                                outputBuffer.get(chunk)
                                outputBuffer.clear()

                                // FIX 2: Route decoded AAC into the same write thread as PCM.
                                // This eliminates concurrent AudioTrack writes entirely.
                                pcmQueue.offer(chunk)
                            }
                            codec.releaseOutputBuffer(index, false)
                        } catch (e: Exception) {
                            AppLog.e("Error processing AAC output", e)
                        }
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        AppLog.e("AAC Codec Error", e)
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        AppLog.i("AAC Output Format Changed: $format")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val handler = Handler(codecHandlerThread!!.looper)
                    decoder!!.setCallback(callback, handler)
                } else {
                    decoder!!.setCallback(callback)
                }
            }

            decoder?.configure(format, null, null, 0)
            decoder?.start()
            AppLog.i("AAC Decoder started for $sampleRate Hz, $channels channels (Async)")
        } catch (e: Exception) {
            AppLog.e("Failed to init AAC decoder", e)
        }
    }

    override fun run() {
        // FIX 3: This thread only decodes / dispatches to pcmQueue.
        // Actual AudioTrack writes happen in writeThread (URGENT_AUDIO priority).
        // PCM writes are very fast (just queue enqueue), so no need for URGENT_AUDIO here.
        while (isRunning || dataQueue.isNotEmpty()) {
            try {
                // FIX 4: Reduced poll timeout from 100ms to 20ms.
                // 100ms is a visible audio gap; 20ms keeps the thread responsive
                // without burning CPU on a tight spin-loop.
                val buffer = dataQueue.poll(20, TimeUnit.MILLISECONDS) ?: continue

                if (isAac && decoder != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        queueInput(buffer)
                    } else {
                        decodeSync(buffer)
                    }
                } else {
                    // PCM: enqueue directly into the write thread
                    pcmQueue.offer(buffer)
                }
            } catch (e: InterruptedException) {
                if (!isRunning && dataQueue.isEmpty()) break
            } catch (e: Exception) {
                AppLog.e("Error in AudioTrackWrapper run loop", e)
                isRunning = false
            }
        }

        // Signal the write thread to finish draining, then clean up
        isRunning = false
        writeThread.join(3000)
        cleanup()
        AppLog.i("AudioTrackWrapper thread finished.")
    }

    @Suppress("DEPRECATION")
    private fun decodeSync(inputData: ByteArray) {
        try {
            val dec = this.decoder ?: return
            val inputIndex = dec.dequeueInputBuffer(200_000)
            if (inputIndex >= 0) {
                val inputBuffer = dec.inputBuffers[inputIndex]
                inputBuffer.clear()
                inputBuffer.put(inputData)
                dec.queueInputBuffer(inputIndex, 0, inputData.size, 0, 0)
            }

            val info = MediaCodec.BufferInfo()
            var outputIndex = dec.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                val outputBuffer = dec.outputBuffers[outputIndex]
                val chunk = ByteArray(info.size)
                outputBuffer.position(info.offset)
                outputBuffer.get(chunk)
                pcmQueue.offer(chunk)
                dec.releaseOutputBuffer(outputIndex, false)
                outputIndex = dec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            AppLog.e("Error in decodeSync", e)
        }
    }

    private fun queueInput(inputData: ByteArray) {
        try {
            // FIX 5: Reduced timeout from 100ms to 20ms.
            // A 100ms stall here directly causes audible gaps since this runs
            // on the same thread that feeds PCM data to the AudioTrack.
            val inputIndex = freeInputBuffers.poll(20, TimeUnit.MILLISECONDS)

            if (inputIndex != null && inputIndex >= 0) {
                val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    decoder?.getInputBuffer(inputIndex)
                } else {
                    @Suppress("DEPRECATION")
                    decoder?.inputBuffers?.get(inputIndex)
                }

                inputBuffer?.clear()
                inputBuffer?.put(inputData)
                decoder?.queueInputBuffer(inputIndex, 0, inputData.size, 0, 0)
            } else {
                AppLog.w("AAC Input Buffer timeout - dropping frame")
            }
        } catch (e: Exception) {
            AppLog.e("Error queuing AAC input", e)
        }
    }

    private fun makeAacCsd(sampleRate: Int, channelCount: Int): ByteArray {
        val sampleRateIndex = getFrequencyIndex(sampleRate)
        val audioObjectType = 2 // AAC-LC

        // FIX 6: Corrected AudioSpecificConfig bit layout.
        // Correct packing: [AOT:5][FreqIdx:4][ChanCfg:4][...padding:3]
        // Previous code used shl 3 for channelCount which left it in the wrong bit position.
        // The full 13-bit field must be left-aligned within the 2-byte (16-bit) buffer:
        //   bits 15-11 = AOT (5 bits)
        //   bits 10-7  = FrequencyIndex (4 bits)
        //   bits 6-3   = ChannelConfig (4 bits)
        //   bits 2-0   = 0 (padding / implicit extensionSamplingFrequencyIndex flag = 0)
        val config = ((audioObjectType and 0x1F) shl 11) or
                     ((sampleRateIndex and 0x0F) shl 7) or
                     ((channelCount and 0x0F) shl 3)

        return byteArrayOf(
            ((config shr 8) and 0xFF).toByte(),
            (config and 0xFF).toByte()
        )
    }

    private fun getFrequencyIndex(sampleRate: Int): Int {
        return when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000  -> 11
            7350  -> 12
            else  -> 4 // Default 44100
        }
    }

    private fun createAudioTrack(
        stream: Int,
        sampleRateInHz: Int,
        bitDepth: Int,
        channelCount: Int,
        multiplier: Int
    ): AudioTrack {
        val channelConfig =
            if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val dataFormat =
            if (bitDepth == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, dataFormat)
        val bufferSize = if (minBufferSize > 0) minBufferSize * multiplier else minBufferSize

        AppLog.i("Audio stream: $stream buffer size: $bufferSize (min: $minBufferSize) sampleRateInHz: $sampleRateInHz channelCount: $channelCount")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioAttributes = AudioAttributes.Builder()
                .setLegacyStreamType(stream)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRateInHz)
                .setChannelMask(channelConfig)
                .setEncoding(dataFormat)
                .build()

            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                stream,
                sampleRateInHz,
                channelConfig,
                dataFormat,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }
    }

    fun write(buffer: ByteArray, offset: Int, size: Int) {
        if (!isRunning) return

        try {
            val success = dataQueue.offer(buffer.copyOfRange(offset, offset + size), 5, TimeUnit.MILLISECONDS)
            if (!success) {
                AppLog.w("Audio queue is full, dropping audio frame to prevent stalling")
            }
        } catch (e: InterruptedException) {
            AppLog.w("Interrupted while offering audio data to queue")
        }
    }

    fun stopPlayback() {
        isRunning = false
        // FIX 7: Don't interrupt() here. The run() loop checks isRunning and drains
        // dataQueue naturally. interrupt() caused poll() to throw InterruptedException
        // immediately, cutting off the drain before the queue was empty.
        // writeThread.join() in run() ensures all PCM is flushed before cleanup().
    }

    private fun cleanup() {
        // 1. Stop the decoder to stop producing new output buffers
        try {
            decoder?.stop()
            decoder?.release()
            decoder = null
        } catch (e: Exception) {
            AppLog.e("Error releasing audio decoder", e)
        }

        // 2. Gracefully stop the AudioTrack – stop() plays remaining buffer data
        if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                audioTrack.stop()

                // Wait for the AudioTrack hardware buffer to drain.
                // Especially important on older devices (KitKat etc.).
                var lastPos = -1
                var stagnantCount = 0
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 2500) {
                    val pos = audioTrack.playbackHeadPosition
                    if (framesWritten > 0 && pos >= framesWritten) break
                    if (pos == lastPos && pos > 0) {
                        stagnantCount++
                        if (stagnantCount >= 3) break
                    } else {
                        lastPos = pos
                        stagnantCount = 0
                    }
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                AppLog.e("Error during audio track cleanup", e)
            }
        }

        // 3. Release the AudioTrack
        try {
            audioTrack.release()
        } catch (e: Exception) {
            AppLog.e("Error releasing audio track", e)
        }

        // 4. Clean up the codec handler thread
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                codecHandlerThread?.quitSafely()
            } else {
                codecHandlerThread?.quit()
            }
            codecHandlerThread = null
        } catch (e: Exception) {
            AppLog.e("Error quitting codec thread", e)
        }
    }
}
