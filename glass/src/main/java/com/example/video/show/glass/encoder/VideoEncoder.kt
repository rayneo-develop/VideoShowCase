package com.example.video.show.glass.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * H.264 hardware encoder.
 * Uses buffer input and accepts NV12 format YUV data.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 30,
    private val bitrate: Int = 2_000_000
) {

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL = 1
    }

    private var mediaCodec: MediaCodec? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        isRunning = true
        Log.d(TAG, "Encoder started: ${width}x${height}")
    }

    fun stop() {
        isRunning = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
        mediaCodec = null
        Log.d(TAG, "Encoder stopped")
    }

    fun encode(nv12Data: ByteArray, presentationTimeUs: Long) {
        val codec = mediaCodec ?: return
        try {
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(nv12Data)
                codec.queueInputBuffer(inputBufferIndex, 0, nv12Data.size, presentationTimeUs, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encode error", e)
        }
    }

    /** @return whether at least one encoded frame was output (used by the sender to decide when to flush the TCP buffer) */
    fun drainEncoder(callback: (ByteBuffer, MediaCodec.BufferInfo) -> Unit): Boolean {
        val codec = mediaCodec ?: return false
        val bufferInfo = MediaCodec.BufferInfo()
        var produced = false
        try {
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: break
                outputBuffer.position(bufferInfo.offset)
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                callback(outputBuffer, bufferInfo)
                produced = true
                codec.releaseOutputBuffer(outputBufferIndex, false)
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Drain encoder error", e)
        }
        return produced
    }

    fun requestKeyFrame() {
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            mediaCodec?.setParameters(params)
        } catch (e: Exception) {
            Log.e(TAG, "Request key frame failed", e)
        }
    }
}
