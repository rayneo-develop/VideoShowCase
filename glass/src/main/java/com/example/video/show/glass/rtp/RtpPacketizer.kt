package com.example.video.show.glass.rtp

import com.example.video.show.glass.streaming.StreamProtocol
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * H.264 RTP packetizer (RFC 3984).
 * Encapsulates H.264 NAL units into RTP packets and sends them via an OutputStream.
 */
class RtpPacketizer(
    private val outputStream: OutputStream,
    /** If pushing has stopped, skip writing to avoid repeated exceptions after the remote side disconnects */
    private val canSend: () -> Boolean = { true },
    private val onWriteFailed: (Exception) -> Unit = {}
) {

    companion object {
        private const val RTP_HEADER_SIZE = 12
        private const val MAX_PAYLOAD_SIZE = 1400 - RTP_HEADER_SIZE
        private const val RTP_VERSION = 2
        private const val RTP_PAYLOAD_TYPE_H264 = 96
        private const val RTP_CLOCK_RATE = 90000L

        // NAL unit types
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8
        private const val NAL_TYPE_FU_A = 28
        private const val NAL_TYPE_IDR = 5
    }

    private var sequenceNumber = 0
    private var ssrc = (Math.random() * 0xFFFFFFFFL).toLong().and(0xFFFFFFFFL).toInt()

    /**
     * Encapsulate and send an H.264 NAL unit.
     * @param data NAL data (without start code)
     * @param offset offset
     * @param length length
     * @param timestampUs timestamp in microseconds
     * @param isKeyFrame whether this is a keyframe
     */
    fun sendNalUnit(data: ByteArray, offset: Int, length: Int, timestampUs: Long, isKeyFrame: Boolean) {
        val timestamp = ((timestampUs * RTP_CLOCK_RATE / 1_000_000) and 0xFFFFFFFFL).toInt()
        val nalType = data[offset].toInt() and 0x1F

        if (length <= MAX_PAYLOAD_SIZE - 1) {
            // Single NAL unit
            sendSingleNalUnit(data, offset, length, timestamp)
        } else {
            // FU-A fragmentation
            sendFuA(data, offset, length, timestamp)
        }
    }

    private fun sendSingleNalUnit(data: ByteArray, offset: Int, length: Int, timestamp: Int) {
        val packet = ByteBuffer.allocate(RTP_HEADER_SIZE + length)
        writeRtpHeader(packet, timestamp, length)
        packet.put(data, offset, length)
        sendPacket(packet.array(), packet.position())
    }

    private fun sendFuA(data: ByteArray, offset: Int, length: Int, timestamp: Int) {
        val nalHeader = data[offset]
        val fuIndicator = ((nalHeader.toInt() and 0xE0) or 28).toByte()
        var remaining = length - 1
        var pos = offset + 1
        var isFirst = true

        while (remaining > 0) {
            val chunkSize = minOf(remaining, MAX_PAYLOAD_SIZE - 2)
            val packet = ByteBuffer.allocate(RTP_HEADER_SIZE + 2 + chunkSize)
            writeRtpHeader(packet, timestamp, 2 + chunkSize)

            packet.put(fuIndicator)
            val fuHeader = when {
                isFirst -> ((data[offset].toInt() and 0x1F) or 0x80).toByte()
                remaining - chunkSize <= 0 -> ((data[offset].toInt() and 0x1F) or 0x40).toByte()
                else -> (data[offset].toInt() and 0x1F).toByte()
            }
            packet.put(fuHeader)
            packet.put(data, pos, chunkSize)

            sendPacket(packet.array(), packet.position())
            pos += chunkSize
            remaining -= chunkSize
            isFirst = false
        }
    }

    private fun writeRtpHeader(packet: ByteBuffer, timestamp: Int, payloadSize: Int) {
        packet.order(ByteOrder.BIG_ENDIAN)
        packet.put((RTP_VERSION shl 6).toByte())           // V=2, P=0, X=0, CC=0
        packet.put((RTP_PAYLOAD_TYPE_H264 and 0x7F).toByte())  // M=0, PT=96
        packet.putShort((sequenceNumber and 0xFFFF).toShort()) // 16-bit sequence
        packet.putInt(timestamp)
        packet.putInt(ssrc)
        sequenceNumber++
    }

    private fun sendPacket(data: ByteArray, length: Int) {
        if (!canSend()) return
        try {
            synchronized(outputStream) {
                if (!canSend()) return
                // TCP: 1-byte type + 4-byte big-endian length + RTP packet
                outputStream.write(StreamProtocol.PACKET_TYPE_VIDEO_RTP.toInt())
                val lenBytes = byteArrayOf(
                    (length shr 24).toByte(),
                    (length shr 16).toByte(),
                    (length shr 8).toByte(),
                    length.toByte()
                )
                outputStream.write(lenBytes)
                outputStream.write(data, 0, length)
                // Do not flush every time: reduces syscall/lock-hold time when sharing TCP with audio, mitigating frame drops
            }
        } catch (e: IOException) {
            onWriteFailed(e)
        }
    }

    fun reset() {
        sequenceNumber = 0
    }
}
