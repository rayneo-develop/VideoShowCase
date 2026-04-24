package com.example.video.show.glass.streaming

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.video.show.glass.R
import com.example.video.show.glass.databinding.DialogAudioStreamChoiceBinding
import com.example.video.show.glass.databinding.LayoutFrameRateSelectBinding
import com.ffalcon.mercury.android.sdk.core.ViewPair
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.dialog.FDialog
import com.ffalcon.mercury.android.sdk.ui.dialog.FocusTracker
import com.ffalcon.mercury.android.sdk.ui.dialog.TrackInfo
import com.ffalcon.mercury.android.sdk.ui.util.RecyclerViewFocusTracker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Select encoding frame rate (interaction same as [ResolutionSelectActivity]), defaults to 30fps.
 * After confirming the frame rate with a tap, an **FDialog** pops up to choose whether to capture audio,
 * then navigates to [StreamingActivity] (passed via [StreamingActivity.EXTRA_AUDIO_ENABLED]).
 */
class FrameRateSelectActivity : BaseMirrorActivity<LayoutFrameRateSelectBinding>() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"

        /** Available frame rates in descending order (default first item is 30fps) */
        val FRAME_RATE_OPTIONS = listOf(30, 25, 20, 15, 10, 5, 1)
    }

    private lateinit var favoriteTracker: RecyclerViewFocusTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val host = intent.getStringExtra(EXTRA_HOST)
        val width = intent.getIntExtra(EXTRA_WIDTH, 0)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
        if (host.isNullOrEmpty() || width <= 0 || height <= 0) {
            finish()
            return
        }

        favoriteTracker = RecyclerViewFocusTracker(
            ViewPair(mBindingPair.left.recyclerView, mBindingPair.right.recyclerView),
            ignoreDelta = 70
        )

        initView()
        initEvent(host, width, height)
        favoriteTracker.focusObj.hasFocus = true
    }

    private fun initView() {
        val rates = FRAME_RATE_OPTIONS
        val defaultIndex = rates.indexOf(30).takeIf { it >= 0 } ?: rates.lastIndex

        val mPair = mBindingPair
        mPair.updateView {
            val isLeft = mPair.checkIsLeft(this)
            tvTitle.text = "Select frame rate\nSwipe to change focus, tap to confirm, double tap to exit"
            recyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = FrameRateMovedFocusAdapter(isLeft, favoriteTracker).apply {
                    setData(rates)
                }
                itemAnimator = null
            }
            favoriteTracker.setCurrentSelectPos(defaultIndex)
        }
    }

    private fun initEvent(host: String, width: Int, height: Int) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collectLatest { action ->
                    if (!favoriteTracker.focusObj.hasFocus || !coroutineContext.isActive || action.consumed) {
                        return@collectLatest
                    }
                    favoriteTracker.handleActionEvent(action) { act ->
                        when (act) {
                            is TempleAction.DoubleClick -> finish()
                            is TempleAction.Click -> {
                                if (!act.consumed) {
                                    (mBindingPair.left.recyclerView.adapter as? FrameRateMovedFocusAdapter)
                                        ?.getCurrentFps()
                                        ?.let { fps ->
                                            showAudioStreamChoiceDialog(host, width, height, fps)
                                        }
                                }
                            }

                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    /**
     * After selecting the frame rate, ask whether to capture the microphone, then navigate to the streaming page.
     */
    private fun showAudioStreamChoiceDialog(host: String, width: Int, height: Int, fps: Int) {
        FDialog.Builder<DialogAudioStreamChoiceBinding>(this)
            .setCancelable(false)
            .setCanceledOnTouchOutside(false)
            .setContentView(DialogAudioStreamChoiceBinding::class.java, { pair, _ ->
                pair.updateView {
                    tvTitle.text = getString(R.string.dialog_audio_title)
                    tvSubtitle.text = getString(R.string.dialog_audio_subtitle)
                    btnEnableAudio.text = getString(R.string.dialog_audio_enable)
                    btnVideoOnly.text = getString(R.string.dialog_video_only)
                }
            })
            .apply {
                val tracker = FocusTracker(true).apply {
                    addFocusTarget(
                        TrackInfo(
                            mPair.left.btnEnableAudio,
                            { action, dialog ->
                                when (action) {
                                    is TempleAction.Click -> {
                                        dialog.dismiss()
                                        navigateToStreaming(
                                            host,
                                            width,
                                            height,
                                            fps,
                                            audioEnabled = true
                                        )
                                    }

                                    else -> Unit
                                }
                            },
                            { hasFocus ->
                                mPair.updateView {
                                    btnEnableAudio.alpha = if (hasFocus) 1f else 0.7f
                                    btnEnableAudio.setBackgroundColor(
                                        getColor(
                                            if (hasFocus) {
                                                com.ffalcon.mercury.android.sdk.R.color.color_rayneo_theme_0
                                            } else {
                                                R.color.black
                                            }
                                        )
                                    )
                                }
                            }
                        ),
                        TrackInfo(
                            mPair.left.btnVideoOnly,
                            { action, dialog ->
                                when (action) {
                                    is TempleAction.Click -> {
                                        dialog.dismiss()
                                        navigateToStreaming(
                                            host,
                                            width,
                                            height,
                                            fps,
                                            audioEnabled = false
                                        )
                                    }

                                    else -> Unit
                                }
                            },
                            { hasFocus ->
                                mPair.updateView {
                                    btnVideoOnly.alpha = if (hasFocus) 1f else 0.7f
                                    btnVideoOnly.setBackgroundColor(
                                        getColor(
                                            if (hasFocus) {
                                                com.ffalcon.mercury.android.sdk.R.color.color_rayneo_theme_0
                                            } else {
                                                R.color.black
                                            }
                                        )
                                    )
                                }
                            }
                        )
                    )
                }
                tracker.currentFocus(mPair.left.btnEnableAudio)
                setFocusTracker(tracker)
            }
            .setEventHandler { action, dialog ->
                if (action is TempleAction.DoubleClick) {
                    dialog.dismiss()
                }
            }
            .build()
            .show()
    }

    private fun navigateToStreaming(
        host: String,
        width: Int,
        height: Int,
        fps: Int,
        audioEnabled: Boolean
    ) {
        startActivity(
            Intent(this, StreamingActivity::class.java).apply {
                putExtra(StreamingActivity.EXTRA_HOST, host)
                putExtra(StreamingActivity.EXTRA_WIDTH, width)
                putExtra(StreamingActivity.EXTRA_HEIGHT, height)
                putExtra(StreamingActivity.EXTRA_FPS, fps)
                putExtra(StreamingActivity.EXTRA_AUDIO_ENABLED, audioEnabled)
            }
        )
        finish()
    }
}
