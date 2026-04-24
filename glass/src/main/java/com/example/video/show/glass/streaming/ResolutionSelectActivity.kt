package com.example.video.show.glass.streaming

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.video.show.glass.camera.CameraCapture
import com.example.video.show.glass.databinding.LayoutResolutionSelectBinding
import com.ffalcon.mercury.android.sdk.core.ViewPair
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.util.RecyclerViewFocusTracker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Select encoding resolution after successfully connecting to the relay
 * (refer to x3 [com.ffalcon.mercury.android.sdk.demo.ui.activity.recycle.MovedFocusPosRVActivity]).
 * Uses [RecyclerViewFocusTracker] + temple swipe/tap/double-tap.
 */
class ResolutionSelectActivity : BaseMirrorActivity<LayoutResolutionSelectBinding>() {

    companion object {
        const val EXTRA_HOST = "host"
    }

    private lateinit var favoriteTracker: RecyclerViewFocusTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val host = intent.getStringExtra(EXTRA_HOST)
        if (host.isNullOrEmpty()) {
            finish()
            return
        }

        favoriteTracker = RecyclerViewFocusTracker(
            ViewPair(mBindingPair.left.recyclerView, mBindingPair.right.recyclerView),
            ignoreDelta = 70
        )

        val cameraCapture = CameraCapture(this)
        val sizes = buildResolutionList(cameraCapture)

        initView(sizes)
        initEvent(host)
        favoriteTracker.focusObj.hasFocus = true
    }

    private fun initView(sizes: List<Pair<Int, Int>>) {
        val mPair = mBindingPair
        mPair.updateView {
            val isLeft = mPair.checkIsLeft(this)
            tvTitle.text =
                "Select resolution\nSwipe to change focus, tap to confirm, double tap to exit"
            recyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = ResolutionMovedFocusAdapter(isLeft, favoriteTracker).apply {
                    setData(sizes)
                }
                itemAnimator = null
            }
            favoriteTracker.setCurrentSelectPos(0)
        }
    }

    private fun initEvent(host: String) {
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
                                    (mBindingPair.left.recyclerView.adapter as? ResolutionMovedFocusAdapter)
                                        ?.getCurrentResolution()
                                        ?.let { (w, h) ->
                                            startActivity(
                                                Intent(
                                                    this@ResolutionSelectActivity,
                                                    FrameRateSelectActivity::class.java
                                                ).apply {
                                                    putExtra(FrameRateSelectActivity.EXTRA_HOST, host)
                                                    putExtra(FrameRateSelectActivity.EXTRA_WIDTH, w)
                                                    putExtra(FrameRateSelectActivity.EXTRA_HEIGHT, h)
                                                }
                                            )
                                            finish()
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

    private fun buildResolutionList(cam: CameraCapture): List<Pair<Int, Int>> {
        val raw = cam.getSupportedSizes()
            .filter { (w, h) -> w <= 1920 && h <= 1080 }
            .distinctBy { "${it.first}x${it.second}" }
            .sortedWith(compareBy({ -it.first * it.second }))
        if (raw.isNotEmpty()) return raw
        return listOf(Pair(1920, 1080), Pair(1280, 720), Pair(640, 480))
    }
}
