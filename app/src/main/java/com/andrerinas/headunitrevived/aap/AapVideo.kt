package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import java.nio.ByteBuffer

internal class AapVideo(private val videoDecoder: VideoDecoder, private val settings: Settings, private val onFrameCorrupted: () -> Unit) {

    private val messageBuffer = ByteBuffer.allocate(
        if (settings.videoCodec == VideoDecoder.CodecType.H265.mimeType) {
            Messages.DEF_BUFFER_LENGTH * 64 // ~8MB for H.265 support
        } else {
            Messages.DEF_BUFFER_LENGTH * 16 // ~2MB for H.264 legacy support
        }
    )
    private var legacyAssembledBuffer: ByteArray? = null
    private var isFrameCorrupt = false
    private var lastKeyframeRequestMs = 0L
    private var isAssemblingFrame = false
    private var waitingForKeyframe = false

    private fun markCorruptAndRequestRecovery() {
        isFrameCorrupt = true
        waitingForKeyframe = true // Lock out P-Frames until an I-Frame arrives
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastKeyframeRequestMs > 1000) {
            lastKeyframeRequestMs = now
            AppLog.w("AapVideo: Frame corrupted, requesting keyframe to recover stream")
            onFrameCorrupted()
        }
    }

    private fun checkKeyframe(message: AapMessage): Boolean {
        if (!waitingForKeyframe)
            return false

        val flags = message.flags.toInt()

        // We need to check if this new frame is a Keyframe (SPS/PPS or IDR)
        // Flag 11 (Single) or Flag 9 (First Fragment) indicate the start of a frame
        // Drop middle/end fragments if it's not
        if (flags != 11 && flags != 9)
            return true

        val buf = message.data
        val len = message.size

        // Try offset 10 first, fallback to offset 2
        var scOffset = 10
        var scLen = findStartCode(buf, scOffset)
        if (scLen <= 0) {
            scOffset = 2
            scLen = findStartCode(buf, scOffset)
        }

        // No start code = Not a keyframe. Drop it.
        if (scLen <= 0 || scOffset + scLen >= len)
            return true

        val nalType = if (settings.videoCodec == VideoDecoder.CodecType.H265.mimeType) {
            (buf[scOffset + scLen].toInt() and 0x7E) shr 1 // H.265 NAL
        } else {
            buf[scOffset + scLen].toInt() and 0x1F // H.264 NAL
        }

        // Check if it's an I-Frame or VPS/SPS/PPS (types that can start a clean stream)
        val isKeyframe = if (settings.videoCodec == VideoDecoder.CodecType.H265.mimeType) {
            nalType in 16..21 || nalType in 32..34
        } else {
            nalType == 5 || nalType == 7 || nalType == 8
        }

        if (isKeyframe) {
            AppLog.i("AapVideo: Keyframe received, resuming stream.")
            waitingForKeyframe = false
            isFrameCorrupt = false
        } else {
            return true // Drop this P-Frame, we are still waiting for a Keyframe!
        }

        return false
    }

    private fun checkFragmentState(message: AapMessage) {
        when (val flags = message.flags.toInt()) {
            11, 9 -> {
                // 11 (Single) and 9 (First) should always start a clean slate.
                if (isAssemblingFrame) {
                    AppLog.w("AapVideo: Previous frame was truncated! Resetting assembly state.")
                }
                isAssemblingFrame = (flags == 9) // Only 9 means we are assembling
            }
            8, 10 -> {
                // 8 (Middle) and 10 (Last) MUST belong to an active assembly.
                if (!isAssemblingFrame) {
                    AppLog.e("AapVideo: Orphaned fragment (Flag $flags) detected! Frame data lost.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                    // Still need to pass it to checkKeyframe in case it's magically valid (rare),
                    // but usually we just drop it.
                }
                if (flags == 10) {
                    isAssemblingFrame = false // Assembly finished
                }
            }
        }
    }

    private fun findStartCode(buf: ByteArray, offset: Int): Int {
        if (offset + 3 > buf.size) return -1
        if (buf[offset].toInt() == 0 && buf[offset + 1].toInt() == 0) {
            if (buf[offset + 2].toInt() == 1) return 3 // 3-byte start code
            if (offset + 4 <= buf.size && buf[offset + 2].toInt() == 0 && buf[offset + 3].toInt() == 1) return 4 // 4-byte start code
        }
        return -1
    }

    fun process(message: AapMessage): Boolean {
        // Fix smearing happening after some while
        checkFragmentState(message)
        if (checkKeyframe(message))
            return true

        val flags = message.flags.toInt()
        val buf = message.data
        val len = message.size

        when (flags) {
            11 -> {
                // Single fragment frame - corruption only affects this frame
                isFrameCorrupt = false
                messageBuffer.clear()

                // Timestamp Indication (Offset 10)
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    videoDecoder.decode(buf, 10, len - 10, settings.forceSoftwareDecoding, settings.videoCodec)
                    return true
                }

                // Media Indication or Config (Offset 2)
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    videoDecoder.decode(buf, 2, len - 2, settings.forceSoftwareDecoding, settings.videoCodec)
                    return true
                }
                AppLog.w("AapVideo: Dropped Flag 11 packet. len=$len")
            }
            9 -> {
                // First fragment - reset corruption state for the new frame
                isFrameCorrupt = false
                messageBuffer.clear()

                // Timestamp Indication (Offset 10)
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    messageBuffer.put(message.data, 10, message.size - 10)
                    return true
                }
                // Media Indication (Offset 2)
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    messageBuffer.put(message.data, 2, message.size - 2)
                    return true
                }
            }
            8 -> {
                if (isFrameCorrupt) return true // Skip fragments of an already corrupt frame

                // Middle fragment - append to buffer with overflow detection
                if (messageBuffer.remaining() >= message.size) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AppLog.e("AapVideo: Fragment overflow (Flag 8)! Size ${message.size} exceeds remaining ${messageBuffer.remaining()}. Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                }
                return true
            }
            10 -> {
                if (isFrameCorrupt) return true // Skip fragments of an already corrupt frame

                // Last fragment - append, assemble, and decode
                if (messageBuffer.remaining() >= message.size) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AppLog.e("AapVideo: Final fragment overflow (Flag 10)! Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                    return true
                }

                messageBuffer.flip()
                val assembledSize = messageBuffer.limit()

                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                    if (legacyAssembledBuffer == null || legacyAssembledBuffer!!.size < assembledSize) {
                        legacyAssembledBuffer = ByteArray(assembledSize + 1024)
                    }
                    messageBuffer.get(legacyAssembledBuffer!!, 0, assembledSize)
                    videoDecoder.decode(legacyAssembledBuffer!!, 0, assembledSize, settings.forceSoftwareDecoding, settings.videoCodec)
                } else {
                    videoDecoder.decode(messageBuffer.array(), 0, assembledSize, settings.forceSoftwareDecoding, settings.videoCodec)
                }

                messageBuffer.clear()
                return true
            }
        }

        return false
    }

    fun release() {
        // Kept for AapTransport lifecycle compatibility. Decoding is synchronous here.
    }
}
