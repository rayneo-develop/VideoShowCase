package com.example.video.show.demo.streaming

import android.media.MediaCodec
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * RTMP cloud streaming module - based on RootEncoder (rtmppush project approach).
 * Pushes H.264 Annex B data to an RTMP server.
 */
class RtmpPushModule : ConnectChecker {

    companion object {
        private const val TAG = "RtmpPushModule"
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8
        private const val NAL_TYPE_IDR = 5
        // Default push URL - can be replaced with Alibaba Cloud, Tencent Cloud, Nginx-RTMP, etc.
        const val MOCK_RTMP_URL = "rtmp://server-ip/live/stream-name"
    }

    private var rtmpUrl: String = MOCK_RTMP_URL
    private var videoWidth: Int = 1920
    private var videoHeight: Int = 1080
    private var videoFps: Int = 30
    private var pushActive = false
    private val rtmpClient = RtmpClient(this)
    private val startTimeUs = AtomicLong(0)
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null
    private var videoInfoSet = false
    private var firstKeyframeSent = false
    private var videoFrameCount = 0

    private var audioSampleRate: Int = StreamProtocol.DEFAULT_AUDIO_SAMPLE_RATE
    private var audioChannels: Int = StreamProtocol.DEFAULT_AUDIO_CHANNELS
    /** True when the stream header declares an audio track; when false, RTMP sends video only */
    private var rtmpAudioEnabled: Boolean = true
    private val audioStartUs = AtomicLong(0)
    private var audioFrameCount = 0

    private var onConnectionResult: ((Boolean, String?) -> Unit)? = null

    fun setPushUrl(url: String) {
        rtmpUrl = url.trim()
        Log.d(TAG, "Push URL set: $rtmpUrl")
    }

    fun getPushUrl(): String = rtmpUrl

    /** Should match the glasses-side encoding resolution; set by StreamReceiver from the stream header before startPush */
    fun setVideoResolution(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
            Log.d(TAG, "Video resolution: ${videoWidth}x${videoHeight}")
        }
    }

    fun getVideoWidth(): Int = videoWidth
    fun getVideoHeight(): Int = videoHeight

    fun setVideoFps(fps: Int) {
        if (fps > 0) {
            videoFps = fps.coerceIn(1, 120)
            Log.d(TAG, "Video fps: $videoFps")
        }
    }

    fun getVideoFps(): Int = videoFps

    /** Must match the glasses-side stream header; set by StreamReceiver before startPush; sampleRate ≤ 0 means no audio */
    fun setAudioStreamParams(sampleRate: Int, channels: Int) {
        if (sampleRate <= 0) {
            rtmpAudioEnabled = false
            Log.d(TAG, "Audio stream disabled (header)")
        } else {
            rtmpAudioEnabled = true
            audioSampleRate = sampleRate.coerceIn(8000, 192_000)
            audioChannels = channels.coerceIn(1, 2)
            Log.d(TAG, "Audio stream: ${audioSampleRate}Hz ch=$audioChannels")
        }
    }

    fun isPushing(): Boolean = pushActive

    /**
     * Start pushing - connect to the RTMP server.
     */
    fun startPush(onResult: ((Boolean, String?) -> Unit)? = null) {
        if (pushActive) return
        pushActive = true
        startTimeUs.set(0)
        videoInfoSet = false
        firstKeyframeSent = false
        videoFrameCount = 0
        audioFrameCount = 0
        audioStartUs.set(0)
        // Keep already-cached spsData/ppsData (received when the camera connected)
        onConnectionResult = onResult
        rtmpClient.setOnlyVideo(!rtmpAudioEnabled)
        if (rtmpAudioEnabled) {
            rtmpClient.setAudioInfo(audioSampleRate, isStereo = audioChannels >= 2)
        }
        rtmpClient.setVideoResolution(videoWidth, videoHeight)
        rtmpClient.setFps(videoFps)
        rtmpClient.connect(rtmpUrl.trim())
    }

    /**
     * Feed H.264 data into the push stream.
     * Note: SPS/PPS are cached immediately upon receipt (even when not pushing),
     * so they are already available when the user starts pushing.
     * @param data Annex B format (0x00 0x00 0x00 0x01 + NAL)
     */
    fun feedH264Data(data: ByteArray, timestampUs: Long, isKeyFrame: Boolean) {
        if (data.size <= 4) return

        // Support both 4-byte (0,0,0,1) and 3-byte (0,0,1) start codes
        val nalStart = when {
            data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
            data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte() -> 3
            else -> return
        }
        val nalType = data[nalStart].toInt() and 0x1F

        // Always cache SPS/PPS so they may already be available when the user starts pushing (sent at camera startup)
        when (nalType) {
            NAL_TYPE_SPS -> {
                spsData = data.copyOfRange(nalStart, data.size)
                Log.d(TAG, "Cached SPS, size=${spsData!!.size}")
            }
            NAL_TYPE_PPS -> {
                ppsData = data.copyOfRange(nalStart, data.size)
                Log.d(TAG, "Cached PPS, size=${ppsData!!.size}")
            }
        }

        if (!pushActive || !rtmpClient.isStreaming) return

        if (!videoInfoSet && spsData != null && ppsData != null) {
            try {
                rtmpClient.setVideoInfo(
                    ByteBuffer.wrap(spsData!!),
                    ByteBuffer.wrap(ppsData!!),
                    null
                )
                videoInfoSet = true
                Log.d(TAG, "Video info (SPS/PPS) set, ready to send frames")
            } catch (e: Exception) {
                Log.e(TAG, "setVideoInfo failed", e)
            }
        }

        // SPS/PPS are only used for setVideoInfo; they are not sent as video frames
        if (nalType == NAL_TYPE_SPS || nalType == NAL_TYPE_PPS) return
        if (!videoInfoSet) return

        // The first frame must be a keyframe (IDR); otherwise the player cannot decode
        if (!firstKeyframeSent && nalType != NAL_TYPE_IDR) return

        try {
            if (startTimeUs.get() == 0L) {
                startTimeUs.set(timestampUs)
            }
            if (isKeyFrame) firstKeyframeSent = true
            val ptsUs = timestampUs - startTimeUs.get()
            val buffer = ByteBuffer.wrap(data)
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = data.size
                presentationTimeUs = ptsUs
                flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            }
            rtmpClient.sendVideo(buffer, info)
            videoFrameCount++
            if (videoFrameCount <= 5 || (videoFrameCount % 100 == 0)) {
                Log.d(TAG, "Sent video frame #$videoFrameCount, keyFrame=$isKeyFrame, size=${data.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feedH264Data error", e)
        }
    }

    /**
     * AAC ADTS frames from the glasses side; strips the ADTS header and feeds AAC RAW
     * into RTMP (consistent with RootEncoder's AacPacket).
     */
    fun feedAacAdts(adtsOrRaw: ByteArray) {
        if (!rtmpAudioEnabled) return
        if (adtsOrRaw.isEmpty()) return
        if (!pushActive || !rtmpClient.isStreaming) return
        val raw = stripAdtsIfPresent(adtsOrRaw)
        if (raw.isEmpty()) return
        try {
            if (audioStartUs.get() == 0L) {
                audioStartUs.set(System.nanoTime() / 1000)
            }
            val ptsUs = System.nanoTime() / 1000 - audioStartUs.get()
            val buffer = ByteBuffer.wrap(raw)
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = raw.size
                presentationTimeUs = ptsUs
                flags = 0
            }
            rtmpClient.sendAudio(buffer, info)
            audioFrameCount++
            if (audioFrameCount <= 5 || (audioFrameCount % 200 == 0)) {
                Log.d(TAG, "Sent audio frame #$audioFrameCount size=${raw.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feedAacAdts error", e)
        }
    }

    private fun stripAdtsIfPresent(data: ByteArray): ByteArray {
        if (data.size < 7) return data
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return data
        val protectionAbsent = (b1 and 0x01) != 0
        val headerLen = if (protectionAbsent) 7 else 9
        return if (data.size > headerLen) data.copyOfRange(headerLen, data.size) else data
    }

    fun stopPush() {
        pushActive = false
        rtmpClient.disconnect()
        Log.d(TAG, "RTMP push stopped")
    }

    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "RTMP connection started: $url")
    }

    override fun onConnectionSuccess() {
        Log.d(TAG, "RTMP push connected")
        onConnectionResult?.invoke(true, null)
        onConnectionResult = null
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTMP open failed: $reason")
        pushActive = false
        onConnectionResult?.invoke(false, reason)
        onConnectionResult = null
    }

    override fun onDisconnect() {
        Log.d(TAG, "RTMP disconnected")
        pushActive = false
    }

    override fun onAuthError() {
        Log.e(TAG, "RTMP auth error")
        pushActive = false
        onConnectionResult?.invoke(false, "Authentication failed")
        onConnectionResult = null
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "RTMP auth success")
    }
}
