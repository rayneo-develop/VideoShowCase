package com.example.video.show.demo.streaming

/**
 * Stream header + frame format agreed upon with the glasses side (see StreamProtocol comments in the glass module).
 */
object StreamProtocol {
    const val HEADER_MAGIC = "VSCH"
    const val HEADER_SIZE = 24

    const val PACKET_TYPE_VIDEO_RTP: Byte = 0x01
    const val PACKET_TYPE_AAC_ADTS: Byte = 0x02

    const val FRAME_HEADER_SIZE = 5

    const val DEFAULT_AUDIO_SAMPLE_RATE = 44100
    const val DEFAULT_AUDIO_CHANNELS = 1
}
