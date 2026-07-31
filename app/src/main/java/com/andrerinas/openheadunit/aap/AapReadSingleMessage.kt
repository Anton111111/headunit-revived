package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.AccessoryConnection
import com.andrerinas.openheadunit.utils.AppLog

internal class AapReadSingleMessage(connection: AccessoryConnection, ssl: AapSsl, handler: AapMessageHandler)
    : AapRead.Base(connection, ssl, handler) {

    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    // Increase to 4MB to handle large 1080p/4K/HEVC I-frames
    private val msgBuffer = ByteArray(4 * 1024 * 1024) 
    private val fragmentSizeBuffer = ByteArray(4)

    override fun doRead(connection: AccessoryConnection): Int {
        try {
            // Step 1: Read the encrypted header.
            // No timeout limit (0 = infinite) because this waits for the
            // NEXT message — the phone can be idle for minutes and that's normal.
            // TCP keepAlive will detect a truly dead connection.
            val isSocket = connection is com.andrerinas.openheadunit.connection.SocketAccessoryConnection
            val timeout = if (isSocket) 15000 else 0
            val headerSize = connection.recvBlocking(recvHeader.buf, recvHeader.buf.size, timeout, true) 
            if (headerSize != AapMessageIncoming.EncryptedHeader.SIZE) {
                if (headerSize == -1) {
                    AppLog.i("AapRead: Connection closed (EOF). Disconnecting.")
                    return -1
                } else if (headerSize == 0) {
                    if (isSocket) {
                        AppLog.w("AapRead: WiFi read timeout (15s) - connection lost.")
                        return -1
                    }
                    return 0
                } else {
                    AppLog.e("AapRead: Partial header read. Expected ${AapMessageIncoming.EncryptedHeader.SIZE}, got $headerSize. Skipping.")
                    return 0
                }
            }

            recvHeader.decode()

            // Immediate check for Magic Garbage in the header bytes.
            // This is the most reliable path for intentional disconnects from the Helper.
            if (isMagicGarbage(recvHeader.buf, 0, recvHeader.buf.size)) {
                AppLog.i("AapRead: Magic Garbage detected in header. Clean disconnect.")
                return -2
            }

            if (recvHeader.flags == 0x09) {
                // Once header arrived, data should be flowing — 10s timeout is valid here
                val readSize = connection.recvBlocking(fragmentSizeBuffer, 4, 10000, true)
                if(readSize != 4) {
                    AppLog.e("AapRead: Failed to read fragment total size. Skipping.")
                    return 0
                }
            }

            // Step 2: Read the encrypted message body
            // Header arrived so body should follow quickly — 10s timeout
            if (recvHeader.enc_len > msgBuffer.size || recvHeader.enc_len < 0) {
                AppLog.e("AapRead: Invalid message size (${recvHeader.enc_len} bytes). Skipping.")
                return 0
            }
            
            val msgSize = connection.recvBlocking(msgBuffer, recvHeader.enc_len, 10000, true)
            if (msgSize != recvHeader.enc_len) {
                if (msgSize == -1) {
                    AppLog.i("AapRead: Connection closed during body read.")
                    return -1
                }
                AppLog.e("AapRead: Failed to read full message body. Expected ${recvHeader.enc_len}, got $msgSize. Skipping.")
                return 0
            }

            // Step 3: Decrypt the message
            val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)

            if (msg == null) {
                // If decryption failed because of a Magic Garbage signal, return -2 to signal clean quit
                if (ssl is AapSslContext && ssl.isUserDisconnect) {
                    AppLog.i("AapRead: Magic Garbage detected in decryption. Triggering clean disconnect.")
                    return -2
                }
                return 0
            }

            // Step 4: Handle the decrypted message
            handler.handle(msg)
            return 0
        } catch (e: Exception) {
            AppLog.e("AapRead: Error in read loop (ignored): ${e.message}")
            return 0
        }
    }

    private fun isMagicGarbage(buffer: ByteArray, start: Int, length: Int): Boolean {
        if (length < 4) return false // Need at least some bytes to verify
        // Check if at least the first 4 bytes are 0xFF
        for (i in 0 until 4.coerceAtMost(length)) {
            if (buffer[start + i] != 0xFF.toByte()) return false
        }
        return true
    }
}
