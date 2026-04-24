package com.example.video.show.demo.streaming

import android.util.Log
import android.view.Surface
import com.example.video.show.demo.decoder.VideoDecoder
import com.example.video.show.demo.rtp.RtpParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.Socket

/**
 * Stream receiver: parses the stream header, then dispatches video RTP and AAC (ADTS) frames
 * by type for decoding, playback, and forwarding to RTMP.
 */
class StreamReceiver(private val surface: Surface) {

    companion object {
        private const val TAG = "StreamReceiver"
        private const val BUFFER_SIZE = 256 * 1024
        private const val MAX_PACKET_BYTES = 2 * 1024 * 1024
    }

    private val rtpParser = RtpParser()
    private val decoder = VideoDecoder(surface)
    private val rtmpModule = RtmpPushModule()
    private var receiveJob: Job? = null
    private var clientSocket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * @param onStarted On success, returns width, height, fps, audio sample rate, and channel count;
     *                   on failure, all parameters are 0.
     */
    fun start(socket: Socket, onStarted: (Boolean, Int, Int, Int, Int, Int) -> Unit) {
        scope.launch {
            try {
                clientSocket = socket
                val inputStream = socket.getInputStream()
                val headerBuf = ByteArray(StreamProtocol.HEADER_SIZE)
                if (!readFully(inputStream, headerBuf)) {
                    Log.e(TAG, "Failed to read stream header")
                    clientSocket = null
                    withContext(Dispatchers.Main) { onStarted(false, 0, 0, 0, 0, 0) }
                    return@launch
                }
                val meta = parseStreamHeader(headerBuf) ?: run {
                    Log.e(TAG, "Invalid stream header")
                    clientSocket = null
                    withContext(Dispatchers.Main) { onStarted(false, 0, 0, 0, 0, 0) }
                    return@launch
                }
                val w = meta.width
                val h = meta.height
                val fps = meta.fps
                val audioSr = meta.audioSampleRate
                val audioCh = meta.audioChannels
                rtmpModule.setVideoResolution(w, h)
                rtmpModule.setVideoFps(fps)
                rtmpModule.setAudioStreamParams(audioSr, audioCh)
                if (!decoder.configure(w, h)) {
                    clientSocket = null
                    withContext(Dispatchers.Main) { onStarted(false, 0, 0, 0, 0, 0) }
                    return@launch
                }
                receiveJob = launch {
                    receiveLoop(inputStream)
                }
                withContext(Dispatchers.Main) { onStarted(true, w, h, fps, audioSr, audioCh) }
            } catch (e: Exception) {
                Log.e(TAG, "Start receiver failed", e)
                clientSocket = null
                withContext(Dispatchers.Main) { onStarted(false, 0, 0, 0, 0, 0) }
            }
        }
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = inputStream.read(buffer, offset, buffer.size - offset)
            if (n <= 0) return false
            offset += n
        }
        return true
    }

    private fun parseStreamHeader(buf: ByteArray): StreamHeaderMeta? {
        if (buf.size < StreamProtocol.HEADER_SIZE) return null
        val magic = StreamProtocol.HEADER_MAGIC.toByteArray(Charsets.US_ASCII)
        for (i in 0..3) {
            if (buf[i] != magic[i]) return null
        }
        val w = readIntBe(buf, 4)
        val h = readIntBe(buf, 8)
        val fps = readIntBe(buf, 12)
        val audioSr = readIntBe(buf, 16)
        val audioCh = readIntBe(buf, 20)
        if (w <= 0 || h <= 0 || w > 7680 || h > 4320) return null
        val fpsClamped = fps.coerceIn(1, 120)
        // Audio fields of 0 in the stream header mean the glasses side did not enable audio;
        // the relay side treats it as video-only.
        val sr = if (audioSr > 0) audioSr.coerceIn(8000, 192_000) else 0
        val ch = if (audioSr > 0) audioCh.coerceIn(1, 2) else 0
        return StreamHeaderMeta(w, h, fpsClamped, sr, ch)
    }

    private data class StreamHeaderMeta(
        val width: Int,
        val height: Int,
        val fps: Int,
        val audioSampleRate: Int,
        val audioChannels: Int
    )

    private fun readIntBe(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    private fun receiveLoop(inputStream: InputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        var offset = 0
        try {
            while (true) {
                val read = inputStream.read(buffer, offset, buffer.size - offset)
                if (read <= 0) break
                offset += read
                var consumed = 0
                while (consumed + StreamProtocol.FRAME_HEADER_SIZE <= offset) {
                    val packetType = buffer[consumed]
                    val packetLen = readIntBe(buffer, consumed + 1)
                    if (packetLen <= 0 || packetLen > MAX_PACKET_BYTES) {
                        consumed++
                        continue
                    }
                    if (consumed + StreamProtocol.FRAME_HEADER_SIZE + packetLen > offset) break
                    val payloadStart = consumed + StreamProtocol.FRAME_HEADER_SIZE
                    val packet = buffer.copyOfRange(payloadStart, payloadStart + packetLen)
                    consumed += StreamProtocol.FRAME_HEADER_SIZE + packetLen
                    when (packetType) {
                        StreamProtocol.PACKET_TYPE_VIDEO_RTP -> processRtpPacket(packet)
                        StreamProtocol.PACKET_TYPE_AAC_ADTS -> rtmpModule.feedAacAdts(packet)
                        else -> Log.w(TAG, "Unknown packet type: $packetType")
                    }
                }
                if (consumed > 0) {
                    System.arraycopy(buffer, consumed, buffer, 0, offset - consumed)
                    offset -= consumed
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Receive loop error", e)
        }
    }

    private fun processRtpPacket(packet: ByteArray) {
        val nalData = rtpParser.parseRtpPacket(packet) ?: return
        if (nalData.size <= 4) return
        val nalType = nalData[4].toInt() and 0x1F
        val isKeyFrame = nalType == 5
        val ptsUs = System.nanoTime() / 1000
        decoder.feedInput(nalData, ptsUs, isKeyFrame)
        decoder.drainDecoder()
        rtmpModule.feedH264Data(nalData, ptsUs, isKeyFrame)
    }

    fun stop() {
        try {
            clientSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Close socket", e)
        }
        clientSocket = null
        receiveJob?.cancel()
        runBlocking(Dispatchers.IO) {
            receiveJob?.join()
        }
        receiveJob = null
        decoder.stop()
        rtmpModule.stopPush()
        rtpParser.reset()
        Log.d(TAG, "StreamReceiver stopped")
    }

    fun getRtmpModule(): RtmpPushModule = rtmpModule

    fun release() {
        stop()
        scope.cancel()
    }
}
