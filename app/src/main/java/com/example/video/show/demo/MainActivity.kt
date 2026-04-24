package com.example.video.show.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.video.show.demo.databinding.ActivityMainBinding
import com.example.video.show.demo.streaming.RtmpPushModule
import com.example.video.show.demo.streaming.StreamReceiver
import com.example.video.show.demo.wifi.WifiDirectServer

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiServer: WifiDirectServer
    private var streamReceiver: StreamReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            Toast.makeText(this, "Permissions are required to continue", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun initComponents() {
        wifiServer = WifiDirectServer(this)
        wifiServer.initialize()

        wifiServer.onGroupCreated = { success ->
            runOnUiThread {
                if (success) {
                    updateStatus("Group created. Waiting for camera device...")
                } else {
                    updateStatus("Failed to create group")
                }
            }
        }

        wifiServer.onClientConnected = { socket ->
            runOnUiThread {
                updateStatus("Camera connected. Receiving video...")
                startStreamReceiver(socket)
            }
        }

        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })

        binding.btnCreateGroup.setOnClickListener {
            updateStatus("Creating group...")
            wifiServer.createGroup()
        }

        binding.btnStartPush.setOnClickListener {
            streamReceiver?.getRtmpModule()?.let { rtmp ->
                if (rtmp.isPushing()) {
                    rtmp.stopPush()
                    binding.btnStartPush.text = "Start cloud streaming"
                    binding.etPushUrl.isEnabled = true
                    updateStatus("Cloud streaming stopped")
                } else {
                    val url = binding.etPushUrl.text.toString().trim()
                    if (url.isEmpty()) {
                        updateStatus("Please enter a stream URL first")
                        return@setOnClickListener
                    }
                    rtmp.setPushUrl(url)
                    binding.btnStartPush.isEnabled = false
                    binding.etPushUrl.isEnabled = false
                    updateStatus("Connecting to RTMP server...")
                    rtmp.startPush { success, errorHint ->
                        binding.btnStartPush.isEnabled = true
                        binding.etPushUrl.isEnabled = !success
                        if (success) {
                            binding.btnStartPush.text = "Stop streaming"
                            updateStatus("Streaming to cloud")
                        } else {
                            updateStatus("RTMP connection failed: ${errorHint ?: "Please check the URL"}")
                        }
                    }
                }
            }
        }

    }

    private fun startStreamReceiver(socket: java.net.Socket) {
        streamReceiver?.release()
        val surface = binding.surfaceView.holder.surface
        streamReceiver = StreamReceiver(surface)
        streamReceiver!!.start(socket) { success, width, height, fps, audioSr, audioCh ->
            runOnUiThread {
                if (success) {
                    updateStatus("Receiving audio/video")
                    binding.tvResolution.text = "Resolution: ${width}×${height}"
                    binding.tvFrameRate.text = if (audioSr > 0) {
                        "Frame rate: ${fps} fps · Audio ${audioSr}Hz ${if (audioCh >= 2) "Stereo" else "Mono"}"
                    } else {
                        "Frame rate: ${fps} fps · Audio off (video-only from glasses)"
                    }
                    if (binding.etPushUrl.text.toString().isBlank()) {
                        binding.etPushUrl.setText(RtmpPushModule.MOCK_RTMP_URL)
                    }
                    binding.btnStartPush.isEnabled = true
                    binding.btnStartPush.text = "Start cloud streaming"
                } else {
                    binding.tvResolution.text = "Resolution: —"
                    binding.tvFrameRate.text = "Frame rate: —"
                    updateStatus("Receive failed (invalid stream header or decoder init failed)")
                }
            }
        }
    }

    private fun updateStatus(text: String) {
        binding.tvStatus.text = "Relay\n$text"
    }

    override fun onDestroy() {
        streamReceiver?.release()
        streamReceiver = null
        binding.btnStartPush.isEnabled = false
        wifiServer.release()
        super.onDestroy()
    }
}
