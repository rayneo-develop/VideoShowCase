package com.example.video.show.glass.encoder

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Microphone PCM → AAC-LC (ADTS) hardware encoding for TCP framed transmission.
 */
class AudioEncoder(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val onAacAdtsFrame: (ByteArray, ptsUs: Long) -> Unit
) {

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        val channelConfig = if (channelCount == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return false
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord ctor", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.e(TAG, "AudioRecord not initialized")
            return false
        }
        audioRecord = record

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            // 64kbps is sufficient for voice; reduces encoding load and bandwidth, slightly lowering CPU/bus contention with video
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
        }
        val enc = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec AAC", e)
            record.release()
            return false
        }
        codec = enc

        record.startRecording()
        running = true
        val pcmBuffer = ByteArray(minBuf)
        thread = Thread({
            val c = codec ?: return@Thread
            val rec = audioRecord ?: return@Thread
            while (running) {
                val read = rec.read(pcmBuffer, 0, pcmBuffer.size)
                if (read > 0) {
                    val inIndex = c.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = c.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        inBuf.put(pcmBuffer, 0, read)
                        val ptsUs = System.nanoTime() / 1000
                        c.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                    }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read: $read")
                }
                drainEncoder(c)
            }
        }, "AudioEncoder").apply { start() }
        return true
    }

    private fun drainEncoder(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* continue draining */ }
                outIndex >= 0 -> {
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val outBuf = c.getOutputBuffer(outIndex)!!
                        val data = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.get(data)
                        onAacAdtsFrame(data, info.presentationTimeUs)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                }
                else -> break
            }
        }
    }

    fun stop() {
        running = false
        // Stop recording first to prevent the thread from blocking on AudioRecord.read(), which would cause join timeout
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            thread?.join(2000)
        } catch (_: InterruptedException) {
        }
        thread = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        Log.d(TAG, "AudioEncoder stopped")
    }

    companion object {
        private const val TAG = "AudioEncoder"
    }
}
