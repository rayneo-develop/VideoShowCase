package com.example.video.show.demo.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 hardware decoder.
 * Decodes H.264 data and renders it to a Surface.
 *
 * All interactions with MediaCodec are performed under the same lock to prevent
 * concurrent release and feedInput calls that would cause
 * IllegalStateException: Invalid to call at Released state.
 */
class VideoDecoder(private val surface: Surface) {

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private val codecLock = Any()
    private var mediaCodec: MediaCodec? = null
    private var isRunning = false
    private var width = 0
    private var height = 0

    fun configure(w: Int, h: Int): Boolean {
        synchronized(codecLock) {
            if (isRunning && mediaCodec != null) return true
            width = w
            height = h
            return try {
                val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
                mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                    configure(format, surface, null, 0)
                    start()
                }
                isRunning = true
                Log.d(TAG, "Decoder configured: ${width}x${height}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Decoder configure failed", e)
                mediaCodec = null
                isRunning = false
                false
            }
        }
    }

    fun feedInput(data: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean) {
        synchronized(codecLock) {
            if (!isRunning) return
            val codec = mediaCodec ?: return
            try {
                val inputBufferIndex = codec.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
                    inputBuffer.clear()
                    inputBuffer.put(data)
                    var flags = 0
                    if (isKeyFrame) flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                    codec.queueInputBuffer(inputBufferIndex, 0, data.size, presentationTimeUs, flags)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "feedInput: codec not in executing state (likely released)", e)
                isRunning = false
                try {
                    codec.stop()
                } catch (_: Exception) {}
                try {
                    codec.release()
                } catch (_: Exception) {}
                mediaCodec = null
            } catch (e: Exception) {
                Log.e(TAG, "Feed input error", e)
            }
        }
    }

    fun drainDecoder() {
        synchronized(codecLock) {
            if (!isRunning) return
            val codec = mediaCodec ?: return
            val bufferInfo = MediaCodec.BufferInfo()
            try {
                var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                while (outputBufferIndex >= 0) {
                    codec.releaseOutputBuffer(outputBufferIndex, true)
                    outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "drainDecoder: codec not in executing state", e)
            } catch (e: Exception) {
                Log.e(TAG, "Drain decoder error", e)
            }
        }
    }

    fun reconfigureIfNeeded(sps: ByteArray?, pps: ByteArray?, w: Int, h: Int): Boolean {
        synchronized(codecLock) {
            if (sps == null || pps == null) return false
            if (w == width && h == height && isRunning) return true
            releaseCodecLocked()
            width = w
            height = h
            return try {
                val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
                mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                    configure(format, surface, null, 0)
                    start()
                }
                isRunning = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "Decoder reconfigure failed", e)
                mediaCodec = null
                isRunning = false
                false
            }
        }
    }

    private fun releaseCodecLocked() {
        isRunning = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping decoder", e)
        }
        mediaCodec = null
    }

    fun stop() {
        synchronized(codecLock) {
            releaseCodecLocked()
            Log.d(TAG, "Decoder stopped")
        }
    }
}
