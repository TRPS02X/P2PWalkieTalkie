package com.trps02.p2pwalkietalkie.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.IBinder
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trps02.p2pwalkietalkie.WalkieTalkieService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var walkieTalkieService: WalkieTalkieService? = null
    private var isBound = false

    // 저장소 (관리자 키 저장용)
    private val prefs = application.getSharedPreferences("WalkieTalkiePrefs", Context.MODE_PRIVATE)
    private val KEY_ADMIN_CARD_ID = "admin_card_id"

    // --- 상태 변수들 ---
    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    // 현재 UI 잠금 상태 (true면 조작 불가)
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    // 관리자 키 등록 모드인지 여부
    private val _isRegisteringCard = MutableStateFlow(false)
    val isRegisteringCard: StateFlow<Boolean> = _isRegisteringCard.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WalkieTalkieService.LocalBinder
            walkieTalkieService = binder.getService()
            isBound = true
            collectServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            walkieTalkieService = null
        }
    }

    private fun collectServiceState() {
        viewModelScope.launch {
            walkieTalkieService?.peers?.collect { _peers.value = it }
        }
        viewModelScope.launch {
            walkieTalkieService?.connectionInfo?.collect { info ->
                _connectionInfo.value = info
                // 연결이 성립되면 자동으로 잠금 모드 진입
                if (info?.groupFormed == true) {
                    _isLocked.value = true
                } else {
                    _isLocked.value = false
                }
            }
        }
    }

    // --- NFC 카드 처리 로직 ---

    fun setRegisteringMode(isRegistering: Boolean) {
        _isRegisteringCard.value = isRegistering
    }

    // NFC 태그가 감지되었을 때 호출됨 (MainActivity에서 호출)
    fun onNfcTagScanned(tagId: String) {
        if (_isRegisteringCard.value) {
            // 1. 등록 모드: 카드 ID 저장
            prefs.edit().putString(KEY_ADMIN_CARD_ID, tagId).apply()
            Toast.makeText(getApplication(), "관리자 키 등록 완료!", Toast.LENGTH_SHORT).show()
            _isRegisteringCard.value = false
        } else {
            // 2. 일반 모드: 관리자 키 확인
            val adminId = prefs.getString(KEY_ADMIN_CARD_ID, null)
            if (adminId == tagId) {
                if (_connectionInfo.value?.groupFormed == true) {
                    // 연결 상태라면 잠금 토글 (해제 <-> 잠금)
                    _isLocked.value = !_isLocked.value
                    val msg = if (_isLocked.value) "다시 잠김" else "관리자 권한: 잠금 해제됨"
                    Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(getApplication(), "인증된 관리자 카드입니다.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(getApplication(), "등록되지 않은 카드입니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun hasRegisteredKey(): Boolean {
        return prefs.contains(KEY_ADMIN_CARD_ID)
    }

    // --- 기존 기능들 ---

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