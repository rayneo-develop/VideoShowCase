package com.example.video.show.glass

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.content.Intent
import android.widget.TextView
import com.example.video.show.glass.databinding.ActivityMainBinding
import com.example.video.show.glass.streaming.ResolutionSelectActivity
import com.example.video.show.glass.wifi.WifiDirectClient
import com.ffalcon.mercury.android.sdk.core.make3DEffectForSide
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.util.FixPosFocusTracker
import com.ffalcon.mercury.android.sdk.ui.util.FocusHolder
import com.ffalcon.mercury.android.sdk.ui.util.FocusInfo
import com.ffalcon.mercury.android.sdk.ui.util.FocusViewHandle
import com.ffalcon.mercury.android.sdk.ui.util.addFocusView
import com.ffalcon.mercury.android.sdk.ui.toast.FToast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Device A - Camera side.
 * Uses MercurySDK temple touch: swipe to change focus, single tap to trigger, double tap to exit.
 */
class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
    }

    private lateinit var wifiClient: WifiDirectClient

    private var groupOwnerAddress: String? = null
    private var focusHolder: FocusHolder? = null
    private var fixPosFocusTracker: FixPosFocusTracker? = null
    private val peerFocusHandles = mutableListOf<FocusViewHandle<*>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            initComponents()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initComponents()
        } else {
            FToast.show("Camera, microphone, and network permissions are required for streaming")
        }
    }

    private fun hasPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun initComponents() {
        wifiClient = WifiDirectClient(this)
        wifiClient.initialize()
        setupWifiCallbacks()
        setupFocusAndGestures()
    }

    private fun setupWifiCallbacks() {
        wifiClient.onPeersAvailable = { peers ->
            runOnUiThread { updatePeerList(peers) }
        }
        wifiClient.onConnected = { goAddress ->
            runOnUiThread {
                if (goAddress.isBlank()) {
                    FToast.show("Relay IP not available. Please reconnect")
                    updateStatus("Connected but relay IP not received")
                    return@runOnUiThread
                }
                groupOwnerAddress = goAddress
                updateStatus("Connected to: $goAddress")
                startActivity(Intent(this@MainActivity, ResolutionSelectActivity::class.java).apply {
                    putExtra(ResolutionSelectActivity.EXTRA_HOST, goAddress)
                })
            }
        }
        wifiClient.onConnectionFailed = { msg ->
            runOnUiThread { updateStatus("Connection failed: $msg") }
        }
        wifiClient.onDisconnected = {
            runOnUiThread {
                groupOwnerAddress = null
                updateStatus("Disconnected")
            }
        }
    }

    private fun setupFocusAndGestures() {
        updateStatus("Swipe to change focus, tap to trigger, double tap to exit")
        rebuildFocusHolder(emptyList())

        // enable3DEffect only takes the left-side View; passing the right side would cause a NullPointerException
        mBindingPair.enable3DEffect(
            mBindingPair.left.btnDiscover,
            enable = true,
            parallax = 3f
        )

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

    private fun rebuildFocusHolder(peers: List<WifiP2pDevice>) {
        peerFocusHandles.forEach { it.clearFocusView() }
        peerFocusHandles.clear()
        mBindingPair.left.peerListContainer.removeAllViews()
        mBindingPair.right.peerListContainer.removeAllViews()

        val holder = FocusHolder(loop = true)
        focusHolder = holder

        val discoverInfo = FocusInfo(
            target = mBindingPair.left.btnDiscover,
            eventHandler = { action ->
                when (action) {
                    is TempleAction.Click -> {
                        updateStatus("Discovering devices...")
                        wifiClient.discoverPeers()
                    }
                    else -> Unit
                }
            },
            focusChangeHandler = { hasFocus ->
                mBindingPair.updateView {
                    triggerFocus(hasFocus, btnDiscover, mBindingPair.checkIsLeft(this))
                }
            }
        )

        holder.addFocusTarget(discoverInfo)
        peers.forEach { device ->
            val handle = mBindingPair.addFocusView(
                parent = mBindingPair.left.peerListContainer,
                viewFactory = {
                    TextView(this).apply {
                        text = "${device.deviceName}\n${device.deviceAddress}"
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = 4 }
                    }
                },
                focusHolder = holder
            ) {
                eventHandler = { action ->
                    when (action) {
                        is TempleAction.Click -> wifiClient.connectToDevice(device)
                        else -> Unit
                    }
                }
                onFocusChange = { view, hasFocus, isLeft ->
                    make3DEffectForSide(view, isLeft, hasFocus)
                    view.alpha = if (hasFocus) 1f else 0.7f
                    view.setBackgroundColor(getColor(if (hasFocus) com.ffalcon.mercury.android.sdk.R.color.color_rayneo_theme_0 else R.color.black))
                }
            }
            peerFocusHandles.add(handle)
        }
        holder.currentFocus(mBindingPair.left.btnDiscover)

        fixPosFocusTracker = FixPosFocusTracker(holder, continuous = false, isVertical = true).apply {
            focusObj.hasFocus = true
        }
    }

    private fun triggerFocus(hasFocus: Boolean, view: View, isLeft: Boolean) {
        make3DEffectForSide(view, isLeft, hasFocus)
        view.alpha = if (hasFocus) 1f else 0.7f
        view.setBackgroundColor(getColor(if (hasFocus) com.ffalcon.mercury.android.sdk.R.color.color_rayneo_theme_0 else R.color.black))
    }

    private fun updatePeerList(peers: List<WifiP2pDevice>) {
        if (peers.isEmpty()) {
            updateStatus("No devices found")
        } else {
            updateStatus("Found ${peers.size} devices")
        }
        rebuildFocusHolder(peers)
    }

    private fun updateStatus(text: String) {
        mBindingPair.updateView {
            tvStatus.text = "Camera device\n$text"
        }
    }

    override fun onDestroy() {
        peerFocusHandles.forEach { it.clearFocusView() }
        peerFocusHandles.clear()
        wifiClient.release()
        super.onDestroy()
    }
}
