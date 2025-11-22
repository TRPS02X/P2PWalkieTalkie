package com.trps02.p2pwalkietalkie.viewmodel

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppState {

    // --- 상태 변수 ---
    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers = _peers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo = _connectionInfo.asStateFlow()

    // 잠금 상태 (기본값: false)
    private val _isLocked = MutableStateFlow(false)
    val isLocked = _isLocked.asStateFlow()

    // 관리자 키 등록 모드
    private val _isRegisteringCard = MutableStateFlow(false)
    val isRegisteringCard = _isRegisteringCard.asStateFlow()

    // --- 설정 저장소 키 ---
    private const val PREFS_NAME = "WalkieTalkiePrefs"
    private const val KEY_ADMIN_CARD_ID = "admin_card_id"

    // --- 데이터 업데이트 함수 ---
    fun updatePeers(newPeers: List<WifiP2pDevice>) {
        _peers.value = newPeers
    }

    fun updateConnectionInfo(info: WifiP2pInfo?) {
        _connectionInfo.value = info
        // 연결되면 자동으로 잠금 모드 진입
        if (info?.groupFormed == true) {
            _isLocked.value = true
        } else {
            _isLocked.value = false
        }
    }

    fun setRegisteringMode(isRegistering: Boolean) {
        _isRegisteringCard.value = isRegistering
    }

    // NFC 태그 처리 로직
    fun onNfcTagScanned(context: Context, tagId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (_isRegisteringCard.value) {
            // 1. 등록 모드: 카드 ID 저장
            prefs.edit().putString(KEY_ADMIN_CARD_ID, tagId).apply()
            Toast.makeText(context, "관리자 키 등록 완료! ID: $tagId", Toast.LENGTH_SHORT).show()
            _isRegisteringCard.value = false
        } else {
            // 2. 일반 모드: 잠금 해제 시도
            val savedAdminId = prefs.getString(KEY_ADMIN_CARD_ID, null)

            if (savedAdminId == tagId) {
                if (_connectionInfo.value?.groupFormed == true) {
                    _isLocked.value = !_isLocked.value // 잠금 토글
                    val msg = if (_isLocked.value) "다시 잠김" else "관리자 권한: 잠금 해제됨"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "인증된 관리자 카드입니다.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "등록되지 않은 카드입니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}