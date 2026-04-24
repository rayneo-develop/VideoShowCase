package com.example.video.show.glass.streaming

/**
 * Stream header + frame format agreed upon with the relay-side app.
 *
 * Header: [magic 4] + [width 4] + [height 4] + [fps 4] + [audio sample rate 4] + [channels 4] (all big-endian int)
 * Each subsequent frame: **1 byte type** + **4 byte big-endian length** + **payload**
 * - [VIDEO_RTP]: payload is a complete RTP packet (compared to the original "4-byte length + RTP", this adds one byte for the type)
 * - [AAC_ADTS]: payload is an AAC ADTS frame (glasses-side microphone)
 */
object StreamProtocol {
    const val HEADER_MAGIC = "VSCH"
    /** Magic 4 + width 4 + height 4 + fps 4 + sample rate 4 + channels 4 */
    const val HEADER_SIZE = 24

    const val PACKET_TYPE_VIDEO_RTP: Byte = 0x01
    const val PACKET_TYPE_AAC_ADTS: Byte = 0x02

    /** Each frame: type 1 + length 4 + payload */
    const val FRAME_HEADER_SIZE = 5

    const val DEFAULT_AUDIO_SAMPLE_RATE = 44100
    const val DEFAULT_AUDIO_CHANNELS = 1
}
