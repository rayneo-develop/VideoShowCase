package com.example.video.show.glass.streaming

import android.util.Log
import com.example.video.show.glass.camera.CameraCapture
import com.example.video.show.glass.encoder.AudioEncoder
import com.example.video.show.glass.encoder.VideoEncoder
import com.example.video.show.glass.rtp.RtpPacketizer
import com.example.video.show.glass.wifi.WifiDirectClient
import kotlinx.coroutines.*
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Video streaming service - integrates Camera, encoding, RTP, and Wi-Fi Direct transmission.
 * Core logic for Device A (camera side).
 */
class VideoStreamingService(private val cameraCapture: CameraCapture, private val wifiClient: WifiDirectClient) {

    companion object {
        private const val TAG = "VideoStreamingService"
        /** Flush audio packets every N frames to reduce per-packet flush overhead while avoiding excessive buffering delay */
        private const val AUDIO_FLUSH_EVERY_FRAMES = 8
    }

    private var encoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var packetizer: RtpPacketizer? = null
    private var socket: Socket? = null
    /** Coalesce small packets and reduce flush frequency, lowering stuttering (dropped-frame feel) when contending for the lock with the audio thread */
    private var streamOut: BufferedOutputStream? = null
    private var sendJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Only write to the Socket when true; set to false after the remote side disconnects or an active stop, preventing uncaught Broken pipe crashes */
    private val sendAllowed = AtomicBoolean(false)
    private val audioFlushCounter = AtomicInteger(0)

    /** Whether the stream header declared audio (non-zero); once started as video-only, audio cannot be enabled mid-stream at the protocol level (requires re-push) */
    private var streamHeaderAnnouncedAudio: Boolean = false

    /**
     * @param audioEnabled whether to capture and send microphone AAC; defaults to true. When false, audio fields in the stream header are 0 and the relay treats it as video-only.
     * @param width encoding width
     * @param height encoding height (capped at 1080p by the selection page)
     * @param fps frame rate (passed from the frame-rate selection page)
     */
    fun startStreaming(
        host: String,
        width: Int,
        height: Int,
        fps: Int,
        audioEnabled: Boolean = true,
        onStarted: (Boolean) -> Unit
    ) {
        scope.launch {
            try {
                socket = wifiClient.connectSocket(host)
                if (socket == null) {
                    withContext(Dispatchers.Main) { onStarted(false) }
                    return@launch
                }
                val sock = socket!!
                try {
                    sock.sendBufferSize = sock.sendBufferSize.coerceAtLeast(256 * 1024)
                } catch (_: Exception) {
                }
                val rawOut = sock.getOutputStream()
                streamOut = BufferedOutputStream(rawOut, 64 * 1024)
                val outputStream = streamOut!!
                val encWidth: Int
                val encHeight: Int
                if (CameraCapture.COMPENSATE_GLASSES_CAMERA_ROTATION_CW90) {
                    encWidth = height
                    encHeight = width
                } else {
                    encWidth = width
                    encHeight = height
                }
                streamHeaderAnnouncedAudio = audioEnabled
                writeStreamHeader(
                    outputStream,
                    encWidth,
                    encHeight,
                    fps,
                    if (audioEnabled) StreamProtocol.DEFAULT_AUDIO_SAMPLE_RATE else 0,
                    if (audioEnabled) StreamProtocol.DEFAULT_AUDIO_CHANNELS else 0
                )
                sendAllowed.set(true)
                audioFlushCounter.set(0)
                packetizer = RtpPacketizer(
                    outputStream,
                    canSend = { sendAllowed.get() },
                    onWriteFailed = { scheduleStopAfterIoError(it) }
                )
                encoder = VideoEncoder(encWidth, encHeight, fps).apply { start() }
                if (audioEnabled) {
                    val ae = AudioEncoder(
                        StreamProtocol.DEFAULT_AUDIO_SAMPLE_RATE,
                        StreamProtocol.DEFAULT_AUDIO_CHANNELS
                    ) { adts, _ ->
                        writeAacAdtsPacket(outputStream, adts)
                    }
                    if (ae.start()) {
                        audioEncoder = ae
                    } else {
                        Log.w(TAG, "Audio encoder failed, video-only")
                    }
                }

                cameraCapture.onFrameAvailable = { nv12Data, ptsUs ->
                    encoder?.encode(nv12Data, ptsUs)
                }
                val sizePair = Pair(width, height)
                cameraCapture.start(sizePair, fps) { success ->
                    if (success) {
                        startEncoderDrainLoop()
                        scope.launch(Dispatchers.Main) { onStarted(true) }
                    } else {
                        scope.launch(Dispatchers.Main) { onStarted(false) }
                    }
                }
            } catch (e: Exception) {
                sendAllowed.set(false)
                Log.e(TAG, "Start streaming failed", e)
                withContext(Dispatchers.Main) { onStarted(false) }
            }
        }
    }

    private fun writeStreamHeader(
        out: OutputStream,
        width: Int,
        height: Int,
        fps: Int,
        audioSampleRate: Int,
        audioChannels: Int
    ) {
        out.write(StreamProtocol.HEADER_MAGIC.toByteArray(Charsets.US_ASCII))
        writeIntBe(out, width)
        writeIntBe(out, height)
        writeIntBe(out, fps.coerceIn(1, 120))
        writeIntBe(out, audioSampleRate.coerceAtLeast(8000))
        writeIntBe(out, audioChannels.coerceIn(1, 2))
        out.flush()
        if (audioSampleRate > 0) {
            Log.d(TAG, "Stream header sent: ${width}x${height} @${fps}fps, audio ${audioSampleRate}Hz ch=$audioChannels")
        } else {
            Log.d(TAG, "Stream header sent: ${width}x${height} @${fps}fps, audio off (video-only)")
        }
    }

    private fun writeAacAdtsPacket(out: OutputStream, adts: ByteArray) {
        if (!sendAllowed.get()) return
        try {
            synchronized(out) {
                if (!sendAllowed.get()) return
                out.write(StreamProtocol.PACKET_TYPE_AAC_ADTS.toInt())
                writeIntBe(out, adts.size)
                out.write(adts)
                if (audioFlushCounter.incrementAndGet() >= AUDIO_FLUSH_EVERY_FRAMES) {
                    audioFlushCounter.set(0)
                    out.flush()
                }
            }
        } catch (e: IOException) {
            // Remote side closed the connection, etc.; do NOT call audioEncoder.stop() from the audio thread (would deadlock on join)
            scheduleStopAfterIoError(e)
        }
    }

    /**
     * When a Socket write fails, perform teardown in another coroutine thread (stop the audio thread, close the Socket, etc.).
     */
    private fun scheduleStopAfterIoError(cause: Exception) {
        if (!sendAllowed.getAndSet(false)) return
        Log.w(TAG, "Socket write failed (peer closed or network error), stopping stream", cause)
        scope.launch(Dispatchers.IO) {
            try {
                stopStreaming()
            } catch (e: Exception) {
                Log.e(TAG, "Error while stopping after IO failure", e)
            }
        }
    }

    private fun writeIntBe(out: OutputStream, v: Int) {
        out.write(v shr 24 and 0xff)
        out.write(v shr 16 and 0xff)
        out.write(v shr 8 and 0xff)
        out.write(v and 0xff)
    }

    private fun startEncoderDrainLoop() {
        sendJob = scope.launch {
            val enc = encoder ?: return@launch
            val pkt = packetizer ?: return@launch
            while (isActive) {
                val hadVideo = enc.drainEncoder { buffer, info ->
                    val data = ByteArray(info.size)
                    buffer.get(data)
                    val isKeyFrame = (info.flags and android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    parseAndSendNalUnits(data, info.presentationTimeUs, isKeyFrame, pkt)
                }
                if (hadVideo) {
                    try {
                        streamOut?.flush()
                    } catch (e: IOException) {
                        scheduleStopAfterIoError(e)
                    }
                }
                delay(5)
            }
        }
    }

    private fun parseAndSendNalUnits(data: ByteArray, ptsUs: Long, isKeyFrame: Boolean, pkt: RtpPacketizer) {
        var i = 0
        while (i < data.size) {
            if (i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                i += 4
            } else if (i + 3 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                i += 3
            } else {
                i++
                continue
            }
            if (i >= data.size) break
            val nalStart = i
            while (i < data.size) {
                if (i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) break
                if (i + 3 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) break
                i++
            }
            val nalLen = i - nalStart
            if (nalLen > 0) {
                pkt.sendNalUnit(data, nalStart, nalLen, ptsUs, isKeyFrame)
            }
        }
    }

    fun isStreaming(): Boolean = encoder != null

    fun stopStreaming() {
        sendAllowed.set(false)
        cameraCapture.onFrameAvailable = null
        sendJob?.cancel()
        sendJob = null
        cameraCapture.stop()
        audioEncoder?.stop()
        audioEncoder = null
        encoder?.stop()
        encoder = null
        packetizer = null
        try {
            streamOut?.flush()
        } catch (_: Exception) {
        }
        streamOut = null
        try {
            socket?.close()
        } catch (e: Exception) { }
        socket = null
        streamHeaderAnnouncedAudio = false
        Log.d(TAG, "Streaming stopped")
    }

    fun release() {
        stopStreaming()
        scope.cancel()
    }
}
