package com.example.video.show.demo.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket

/**
 * Wi-Fi Direct server (Group Owner).
 * Device B (relay side) acts as the Group Owner, creates a P2P group, and listens for Socket connections.
 */
class WifiDirectServer(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectServer"
        private const val SERVER_PORT = 8888
    }

    private val manager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serverSocket: ServerSocket? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onGroupCreated: ((Boolean) -> Unit)? = null
    var onClientConnected: ((Socket) -> Unit)? = null

    fun initialize() {
        channel = manager.initialize(context, context.mainLooper, null)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        Log.d(TAG, "Wi-Fi P2P state: $state")
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        manager.requestGroupInfo(channel) { group ->
                            Log.d(TAG, "Group info: ${group?.isGroupOwner}")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        Log.d(TAG, "WifiDirectServer initialized")
    }

    fun createGroup() {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P Group created (Group Owner)")
                startServerSocket()
                onGroupCreated?.invoke(true)
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Create group failed: $reason")
                onGroupCreated?.invoke(false)
            }
        })
    }

    private fun startServerSocket() {
        ioScope.launch {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                Log.d(TAG, "ServerSocket listening on port $SERVER_PORT")
                while (true) {
                    val client = serverSocket?.accept() ?: break
                    Log.d(TAG, "Client connected: ${client.inetAddress}")
                    onClientConnected?.invoke(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ServerSocket error", e)
            }
        }
    }

    fun removeGroup() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Group removed")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Remove group failed: $reason")
            }
        })
        try {
            serverSocket?.close()
        } catch (e: Exception) { }
        serverSocket = null
    }

    fun release() {
        removeGroup()
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
        channel = null
        Log.d(TAG, "WifiDirectServer released")
    }
}
