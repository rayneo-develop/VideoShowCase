package com.example.video.show.demo.rtp

import java.nio.ByteBuffer
import kotlin.experimental.or

/**
 * RTP Parser
 * Parses RTP packets and extracts H.264 NAL units
 */
class RtpParser {

    companion object {
        private const val RTP_HEADER_SIZE = 12
        private const val NAL_TYPE_FU_A = 28
        private const val FU_A_HEADER_SIZE = 2
    }

    private val fuBuffer = mutableListOf<ByteArray>()
    private var fuTimestamp = 0L

    /**
     * Parses an RTP packet and returns H.264 NAL data (with start code 0x00 0x00 0x00 0x01).
     * For FU-A fragmented packets, waits until the complete NAL is assembled before returning.
     */
    fun parseRtpPacket(data: ByteArray): ByteArray? {
        if (data.size < RTP_HEADER_SIZE) return null
        val payload = data.copyOfRange(RTP_HEADER_SIZE, data.size)
        if (payload.isEmpty()) return null

        val timestamp = (((data[4].toInt() and 0xFF) shl 24) or
                ((data[5].toInt() and 0xFF) shl 16) or
                ((data[6].toInt() and 0xFF) shl 8) or
                (data[7].toInt() and 0xFF)).toLong()

        val nalType = payload[0].toInt() and 0x1F

        return when {
            nalType == NAL_TYPE_FU_A && payload.size >= FU_A_HEADER_SIZE -> {
                parseFuA(payload, timestamp)
            }
            else -> {
                // Single NAL unit
                buildNalWithStartCode(payload)
            }
        }
    }

    private fun parseFuA(payload: ByteArray, timestamp: Long): ByteArray? {
        val fuIndicator = payload[0]
        val fuHeader = payload[1].toInt()
        val isStart = (fuHeader and 0x80) != 0
        val isEnd = (fuHeader and 0x40) != 0
        val nalType = fuHeader and 0x1F

        val payloadData = payload.copyOfRange(FU_A_HEADER_SIZE, payload.size)

        return when {
            isStart -> {
                fuBuffer.clear()
                fuBuffer.add(payloadData)
                fuTimestamp = timestamp
                if (isEnd) {
                    val nalHeader = (fuIndicator.toInt() and 0xE0).toByte() or nalType.toByte()
                    val combined = fuBuffer.fold(byteArrayOf()) { acc, arr -> acc + arr }
                    buildNalWithStartCode(byteArrayOf(nalHeader) + combined)
                } else null
            }
            isEnd -> {
                fuBuffer.add(payloadData)
                val nalHeader = (fuIndicator.toInt() and 0xE0).toByte() or nalType.toByte()
                val combined = fuBuffer.fold(byteArrayOf()) { acc, arr -> acc + arr }
                val result = buildNalWithStartCode(byteArrayOf(nalHeader) + combined)
                fuBuffer.clear()
                result
            }
            else -> {
                fuBuffer.add(payloadData)
                null
            }
        }
    }

    private fun buildNalWithStartCode(nalData: ByteArray): ByteArray {
        val startCode = byteArrayOf(0, 0, 0, 1)
        return startCode + nalData
    }

    fun reset() {
        fuBuffer.clear()
    }
}
