package com.trps02.p2pwalkietalkie.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trps02.p2pwalkietalkie.WalkieTalkieService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var walkieTalkieService: WalkieTalkieService? = null
    private var isBound = false

    val peers = AppState.peers
    val connectionInfo = AppState.connectionInfo
    val isLocked = AppState.isLocked
    val isRegisteringCard = AppState.isRegisteringCard

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WalkieTalkieService.LocalBinder
            walkieTalkieService = binder.getService()
            isBound = true
            collectServiceState()
            startAutoDiscovery()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            walkieTalkieService = null
        }
    }

    // ▼▼▼ 자동 탐색 로직 수정됨 ▼▼▼
    private fun startAutoDiscovery() {
        viewModelScope.launch {
            while (isActive) {
                val info = AppState.connectionInfo.value
                val isConnected = info?.groupFormed == true
                val isGroupOwner = info?.isGroupOwner == true

                // 1. 연결 안 됐으면 무조건 탐색
                // 2. 연결 됐더라도 내가 '방장(Group Owner)'이면 탐색 유지 (그래야 새 멤버가 찾음)
                if (!isConnected || isGroupOwner) {
                    walkieTalkieService?.discoverPeers()
                }

                // 방장일 때는 오디오 끊김 방지를 위해 탐색 간격을 좀 더 길게(15초) 둠
                val delayTime = if (isGroupOwner) 15000L else 10000L
                delay(delayTime)
            }
        }
    }
    // ▲▲▲ 자동 탐색 로직 수정됨 ▲▲▲

    private fun collectServiceState() {
        viewModelScope.launch {
            walkieTalkieService?.peers?.collect { AppState.updatePeers(it) }
        }
        viewModelScope.launch {
            walkieTalkieService?.connectionInfo?.collect { AppState.updateConnectionInfo(it) }
        }
    }

    fun onNfcTagScanned(tagId: String) {
        AppState.onNfcTagScanned(getApplication(), tagId)
    }

    fun setRegisteringMode(isRegistering: Boolean) {
        AppState.setRegisteringMode(isRegistering)
    }

    fun bindService() {
        if (!isBound) {
            Intent(getApplication(), WalkieTalkieService::class.java).also { intent ->
                getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    fun unbindService() {
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
            walkieTalkieService = null
        }
    }

    fun discoverPeers() { walkieTalkieService?.discoverPeers() }
    fun connectToDevice(deviceAddress: String) { walkieTalkieService?.connectToDevice(deviceAddress) }
    fun startRecording() { walkieTalkieService?.startRecording() }
    fun stopRecording() { walkieTalkieService?.stopRecording() }
    fun disconnect() { walkieTalkieService?.disconnect() }

    override fun onCleared() {
        unbindService()
        super.onCleared()
    }
}