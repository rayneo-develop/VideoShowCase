package com.example.video.show.glass.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Wi-Fi Direct client.
 * Device A (camera side) acts as a Client connecting to Device B (relay side, Group Owner).
 */
class WifiDirectClient(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectClient"
        private const val SERVER_PORT = 8888
    }

    private val manager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** When the group is formed but the GO IP has not been delivered yet, retry fetching ConnectionInfo */
    private var goAddressRetryCount = 0
    /** Prevent multiple CONNECTION_CHANGED events from triggering startActivity repeatedly */
    private var hasEmittedConnectedForSession = false

    var onPeersAvailable: ((List<WifiP2pDevice>) -> Unit)? = null
    var onConnected: ((String) -> Unit)? = null
    var onConnectionFailed: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun initialize() {
        channel = manager.initialize(context, context.mainLooper, null)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            Log.d(TAG, "Wi-Fi Direct enabled")
                        } else {
                            Log.w(TAG, "Wi-Fi Direct disabled")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager.requestPeers(channel) { peers ->
                            val deviceList = peers?.deviceList?.toList().orEmpty()
                            Log.d(
                                TAG,
                                "onPeersAvailable: count=${deviceList.size}, " +
                                    deviceList.joinToString(prefix = "[", postfix = "]") {
                                        "${it.deviceName}(${it.deviceAddress})"
                                    }
                            )
                            onPeersAvailable?.invoke(deviceList)
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        manager.requestConnectionInfo(channel) { info ->
                            handleConnectionInfo(info)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        Log.d(TAG, "WifiDirectClient initialized")
    }

    /**
     * After group formation, if [WifiP2pInfo.groupOwnerAddress] is not yet ready, retry with a short delay
     * (otherwise EXTRA_HOST is empty and [com.example.video.show.glass.streaming.ResolutionSelectActivity]
     * finishes immediately, appearing as if navigation failed).
     */
    private fun handleConnectionInfo(info: WifiP2pInfo?) {
        if (info?.groupFormed != true) {
            goAddressRetryCount = 0
            hasEmittedConnectedForSession = false
            mainHandler.removeCallbacksAndMessages(null)
            onDisconnected?.invoke()
            return
        }
        val goAddress = info.groupOwnerAddress?.hostAddress?.trim().orEmpty()
        Log.d(
            TAG,
            "ConnectionInfo: groupFormed=true, isGroupOwner=${info.isGroupOwner}, goAddress='$goAddress'"
        )
        if (goAddress.isNotEmpty()) {
            goAddressRetryCount = 0
            mainHandler.removeCallbacksAndMessages(null)
            if (!hasEmittedConnectedForSession) {
                hasEmittedConnectedForSession = true
                onConnected?.invoke(goAddress)
            }
            return
        }
        if (goAddressRetryCount >= 12) {
            goAddressRetryCount = 0
            Log.e(TAG, "Group Owner IP still empty after retries")
            onConnectionFailed?.invoke("Unable to get relay IP. Please pair again")
            return
        }
        goAddressRetryCount++
        mainHandler.postDelayed({
            manager.requestConnectionInfo(channel) { handleConnectionInfo(it) }
        }, 350L)
    }

    fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery started")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Peer discovery failed: $reason")
            }
        })
    }

    fun connectToDevice(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connect request sent to ${device.deviceName}")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connect failed: $reason")
                onConnectionFailed?.invoke("Connection failed: $reason")
            }
        })
    }

    /**
     * Connect to the Group Owner's Socket server.
     */
    fun connectSocket(host: String, timeout: Int = 5000): Socket? {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, SERVER_PORT), timeout)
            Log.d(TAG, "Socket connected to $host:$SERVER_PORT")
            socket
        } catch (e: Exception) {
            Log.e(TAG, "Socket connect failed", e)
            null
        }
    }

    fun release() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
        channel = null
        Log.d(TAG, "WifiDirectClient released")
    }
}
