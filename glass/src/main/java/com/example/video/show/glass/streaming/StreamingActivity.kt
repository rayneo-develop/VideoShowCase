package com.example.video.show.glass.streaming

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.video.show.glass.R
import com.example.video.show.glass.camera.CameraCapture
import com.example.video.show.glass.databinding.ActivityStreamingBinding
import com.example.video.show.glass.wifi.WifiDirectClient
import com.ffalcon.mercury.android.sdk.core.make3DEffectForSide
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.util.FixPosFocusTracker
import com.ffalcon.mercury.android.sdk.ui.util.FocusHolder
import com.ffalcon.mercury.android.sdk.ui.util.FocusInfo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Streaming page - navigated to after connecting to the relay side.
 * Whether to capture audio is selected via FDialog in [FrameRateSelectActivity],
 * passed through [EXTRA_AUDIO_ENABLED]; cannot be changed once streaming starts.
 */
class StreamingActivity : BaseMirrorActivity<ActivityStreamingBinding>() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        /** Whether to capture and send microphone AAC, selected from the frame rate page dialog, defaults to true */
        const val EXTRA_AUDIO_ENABLED = "audio_enabled"
    }

    private lateinit var cameraCapture: CameraCapture
    private lateinit var wifiClient: WifiDirectClient
    private lateinit var streamingService: VideoStreamingService

    private var focusHolder: FocusHolder? = null
    private var fixPosFocusTracker: FixPosFocusTracker? = null

    private var audioCaptureEnabled: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra(EXTRA_HOST)
        if (host.isNullOrEmpty()) {
            finish()
            return
        }
        val width = intent.getIntExtra(EXTRA_WIDTH, 1920)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1080)
        val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(1, 120)
        audioCaptureEnabled = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, true)

        cameraCapture = CameraCapture(this)
        wifiClient = WifiDirectClient(this)
        streamingService = VideoStreamingService(cameraCapture, wifiClient)

        setupFocusAndGestures(host, width, height, fps)
    }

    private fun setupFocusAndGestures(host: String, width: Int, height: Int, fps: Int) {
        updateResolutionLabel(width, height, fps)
        updateStatus(
            buildString {
                append("Connected to $host\n")
                append(
                    if (audioCaptureEnabled) {
                        "Selected: audio on. Tap Start streaming"
                    } else {
                        "Selected: video only. Tap Start streaming"
                    }
                )
            }
        )

        mBindingPair.enable3DEffect(
            mBindingPair.left.btnStartStream,
            mBindingPair.left.btnStopStream,
            enable = true,
            parallax = 3f
        )

        val holder = FocusHolder(loop = true)
        focusHolder = holder

        val startStreamInfo = FocusInfo(
            target = mBindingPair.left.btnStartStream,
            eventHandler = { action ->
                when (action) {
                    is TempleAction.Click -> {
                        doStartStreaming(host, width, height, fps)
                    }
                    else -> Unit
                }
            },
            focusChangeHandler = { hasFocus ->
                mBindingPair.updateView {
                    triggerFocus(hasFocus, btnStartStream, mBindingPair.checkIsLeft(this))
                }
            }
        )

        val stopStreamInfo = FocusInfo(
            target = mBindingPair.left.btnStopStream,
            eventHandler = { action ->
                when (action) {
                    is TempleAction.Click -> {
                        streamingService.stopStreaming()
                        updateStatus("Streaming stopped")
                        updateStartStreamEnabled(true)
                        updateStopStreamEnabled(false)
                    }
                    else -> Unit
                }
            },
            focusChangeHandler = { hasFocus ->
                mBindingPair.updateView {
                    triggerFocus(hasFocus, btnStopStream, mBindingPair.checkIsLeft(this))
                }
            }
        )

        holder.addFocusTarget(startStreamInfo, stopStreamInfo)
        holder.currentFocus(mBindingPair.left.btnStartStream)

        fixPosFocusTracker = FixPosFocusTracker(holder, continuous = false, isVertical = true).apply {
            focusObj.hasFocus = true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collectLatest { action ->
                    when (action) {
                        is TempleAction.DoubleClick -> finish()
                        else -> fixPosFocusTracker?.handleFocusTargetEvent(action)
                    }
                }
            }
        }
    }

    private fun doStartStreaming(host: String, width: Int, height: Int, fps: Int) {
        updateStatus("Starting streaming...")
        updateStartStreamEnabled(false)
        updateStopStreamEnabled(true)
        streamingService.startStreaming(host, width, height, fps, audioCaptureEnabled) { success ->
            runOnUiThread {
                if (success) {
                    updateStatus(
                        if (audioCaptureEnabled) {
                            "Streaming (audio + video)\nAudio setting is locked"
                        } else {
                            "Streaming (video only)\nAudio setting is locked"
                        }
                    )
                } else {
                    updateStatus("Streaming failed")
                    updateStartStreamEnabled(true)
                    updateStopStreamEnabled(false)
                }
            }
        }
    }

    private fun triggerFocus(hasFocus: Boolean, view: View, isLeft: Boolean) {
        make3DEffectForSide(view, isLeft, hasFocus)
        view.alpha = if (hasFocus) 1f else 0.7f
        view.setBackgroundColor(getColor(if (hasFocus) com.ffalcon.mercury.android.sdk.R.color.color_rayneo_theme_0 else R.color.black))
    }

    private fun updateResolutionLabel(w: Int, h: Int, fps: Int) {
        val encW: Int
        val encH: Int
        if (CameraCapture.COMPENSATE_GLASSES_CAMERA_ROTATION_CW90) {
            encW = h
            encH = w
        } else {
            encW = w
            encH = h
        }
        mBindingPair.updateView {
            tvResolution.text = "Encode resolution ${encW}×${encH} (capture ${w}×${h})"
            tvFrameRate.text = "Current frame rate ${fps} fps"
        }
    }

    private fun updateStatus(text: String) {
        mBindingPair.updateView {
            tvStatus.text = "Streaming\n$text"
        }
    }

    private fun updateStartStreamEnabled(enabled: Boolean) {
        mBindingPair.updateView {
            btnStartStream.isEnabled = enabled
        }
    }

    private fun updateStopStreamEnabled(enabled: Boolean) {
        mBindingPair.updateView {
            btnStopStream.isEnabled = enabled
        }
    }

    override fun onDestroy() {
        streamingService.release()
        super.onDestroy()
    }
}
