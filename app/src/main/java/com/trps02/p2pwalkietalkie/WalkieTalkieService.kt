package com.trps02.p2pwalkietalkie

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class WalkieTalkieService : Service() {

    private val TAG = "WalkieTalkieService"
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val NOTIFICATION_CHANNEL_ID = "p2p_walkie_talkie_channel"
    private val NOTIFICATION_ID = 101

    private lateinit var wifiP2pManager: WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val intentFilter = IntentFilter()

    private var audioStreamManager: AudioStreamManager? = null

    // UI에 공개할 상태
    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        if (info.groupFormed) {
            startAudioStreamer(info)
        }
        _connectionInfo.value = info
    }

    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        val newPeers = peerList.deviceList.toList()
        _peers.value = newPeers
    }

    override fun onCreate() {
        super.onCreate()
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        initializeChannel()
        setupIntentFilter()
        registerReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("무전기 대기 중..."))
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver()
        stopAudioStreamer()
        super.onDestroy()
    }

    private fun createNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "P2P Walkie Talkie", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }

        return builder.setContentTitle("P2P 무전기")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun initializeChannel() {
        channel = wifiP2pManager.initialize(this, Looper.getMainLooper(), null)
    }

    private fun setupIntentFilter() {
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private fun registerReceiver() {
        if (receiver == null && channel != null) {
            receiver = WiFiDirectBroadcastReceiver()
            registerReceiver(receiver, intentFilter)
        }
    }

    private fun unregisterReceiver() {
        receiver?.let {
            unregisterReceiver(it)
            receiver = null
        }
    }

    fun discoverPeers() {
        channel?.let {
            wifiP2pManager.discoverPeers(it, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        }
    }

    fun connectToDevice(deviceAddress: String) {
        if (channel == null) return
        val config = WifiP2pConfig().apply { this.deviceAddress = deviceAddress }
        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
    }

    fun disconnect() {
        if (channel != null) {
            wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    _connectionInfo.value = null
                    stopAudioStreamer()
                }
            })
        } else {
            _connectionInfo.value = null
            stopAudioStreamer()
        }
    }

    private fun startAudioStreamer(info: WifiP2pInfo) {
        if (audioStreamManager != null) return

        audioStreamManager = AudioStreamManager(info).apply {
            startServerAndReceiver()
        }
        updateNotification("연결됨")
    }

    private fun stopAudioStreamer() {
        audioStreamManager?.disconnect()
        audioStreamManager = null
        updateNotification("무전기 대기 중...")
    }

    fun startRecording() {
        audioStreamManager?.startStreaming()
        updateNotification("송신 중...")
    }

    fun stopRecording() {
        audioStreamManager?.stopStreaming()
        updateNotification("연결됨")
    }

    private inner class WiFiDirectBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    channel?.let { wifiP2pManager.requestPeers(it, peerListListener) }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true && channel != null) {
                        wifiP2pManager.requestConnectionInfo(channel, connectionInfoListener)
                    } else {
                        _connectionInfo.value = null
                        stopAudioStreamer()
                    }
                }
            }
        }
    }
}