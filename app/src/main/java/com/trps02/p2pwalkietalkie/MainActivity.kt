package com.trps02.p2pwalkietalkie

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trps02.p2pwalkietalkie.ui.theme.P2PWalkieTalkieTheme
import com.trps02.p2pwalkietalkie.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val viewModel: MainViewModel by viewModels()

    private var isHardwarePttPressed = false
    private lateinit var toneGenerator: ToneGenerator
    private lateinit var vibrator: Vibrator
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 관리자 권한 설정 (키오스크 모드용)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        Intent(this, WalkieTalkieService::class.java).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        setContent {
            P2PWalkieTalkieTheme {
                val permissionsToRequest = remember {
                    val basePermissions = mutableListOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.VIBRATE,
                        Manifest.permission.ACCESS_WIFI_STATE // 와이파이 상태 확인용
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        basePermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        basePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        basePermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    } else {
                        basePermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    basePermissions.toTypedArray()
                }

                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
                LaunchedEffect(true) { launcher.launch(permissionsToRequest) }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.bindService()
                            // NFC 리더 모드 자동 활성화 (기본 모드로 세팅)
                            nfcAdapter?.enableReaderMode(
                                this@MainActivity,
                                this@MainActivity,
                                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                                null
                            )
                        }
                        if (event == Lifecycle.Event.ON_PAUSE) {
                            viewModel.unbindService()
                            nfcAdapter?.disableReaderMode(this@MainActivity)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PeerScreen(viewModel)
                }
            }
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        tag?.let {
            val tagId = it.id.joinToString("") { byte -> "%02x".format(byte) }
            runOnUiThread {
                viewModel.onNfcTagScanned(tagId)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (!isHardwarePttPressed) {
                isHardwarePttPressed = true
                viewModel.startRecording()
                performHapticAndSound(true)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (isHardwarePttPressed) {
                isHardwarePttPressed = false
                viewModel.stopRecording()
                performHapticAndSound(false)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun performHapticAndSound(isStart: Boolean) {
        if (isStart) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            vibrateCompat(50)
        } else {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 100)
            vibrateCompat(150)
        }
    }

    private fun vibrateCompat(duration: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    override fun onDestroy() {
        toneGenerator.release()
        super.onDestroy()
    }
}

// ▼▼▼ 1. 커스텀 상태 표시줄 Composable ▼▼▼
@Composable
fun CustomStatusBar() {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf("") }
    var batteryPct by remember { mutableStateOf(0) }
    var wifiLevel by remember { mutableStateOf(0) }

    // 1초마다 정보 갱신
    LaunchedEffect(Unit) {
        while (true) {
            // 시간 업데이트
            val sdf = SimpleDateFormat("a hh:mm", Locale.getDefault())
            currentTime = sdf.format(Date())

            // 배터리 업데이트
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                batteryPct = (level * 100) / scale
            }

            // 와이파이 업데이트
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val rssi = wifiManager.connectionInfo.rssi
            wifiLevel = WifiManager.calculateSignalLevel(rssi, 5) // 0~4 단계

            delay(1000) // 1초 대기
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black) // 검은색 배경
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 왼쪽: 시간
        Text(
            text = currentTime,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        // 오른쪽: 와이파이 & 배터리
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 와이파이 강도 (텍스트로 표현: 📶)
            val wifiIcon = when (wifiLevel) {
                0 -> "⚪"
                1 -> "🔈"
                2 -> "🔉"
                3 -> "🔊"
                else -> "📶"
            }
            Text(text = "$wifiIcon ", color = Color.White, fontSize = 12.sp)

            Spacer(modifier = Modifier.width(8.dp))

            // 배터리 잔량
            Text(
                text = "$batteryPct%",
                color = if (batteryPct > 20) Color.White else Color.Red, // 20% 이하면 빨간색
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}
// ▲▲▲ 1. 커스텀 상태 표시줄 Composable ▲▲▲

@Composable
fun PeerScreen(viewModel: MainViewModel) {
    val peers by viewModel.peers.collectAsState()
    val connectionInfo by viewModel.connectionInfo.collectAsState()
    val isConnected = connectionInfo?.groupFormed == true
    val isLocked by viewModel.isLocked.collectAsState()
    val isRegistering by viewModel.isRegisteringCard.collectAsState()

    val context = LocalContext.current
    val activity = context as? Activity

    LaunchedEffect(isLocked) {
        if (isLocked) {
            try {
                activity?.startLockTask()
            } catch (e: Exception) { }
        } else {
            try {
                activity?.stopLockTask()
            } catch (e: Exception) { }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ▼▼▼ 2. 상단에 상태 표시줄 배치 ▼▼▼
        CustomStatusBar()
        // ▲▲▲ 2. 상단에 상태 표시줄 배치 ▲▲▲

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            ConnectionStatus(isConnected, connectionInfo?.isGroupOwner == true)
            Spacer(modifier = Modifier.height(16.dp))

            if (!isConnected) {
                if (isRegistering) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("관리자 키 등록 모드", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("지금 NFC 카드나 태그를 뒷면에 대세요.", fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { viewModel.setRegisteringMode(false) }) {
                                Text("취소")
                            }
                        }
                    }
                } else {
                    Button(onClick = { viewModel.discoverPeers() }, modifier = Modifier.fillMaxWidth()) {
                        Text("주변 기기 탐색", fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.padding(8.dp))
                    OutlinedButton(onClick = { viewModel.setRegisteringMode(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("관리자 키(NFC) 등록")
                    }
                    Spacer(modifier = Modifier.padding(8.dp))
                    Text("발견된 기기 (${peers.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(peers) { device ->
                            PeerItem(device.deviceName) { viewModel.connectToDevice(device.deviceAddress) }
                        }
                    }
                }
            } else {
                if (!isLocked) {
                    Button(
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("연결 끊기", fontSize = 16.sp)
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("잠김: 관리자 키를 태그하여 해제", color = Color.Gray, fontSize = 12.sp)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PushToTalkButton(
                            onPress = { viewModel.startRecording() },
                            onRelease = { viewModel.stopRecording() }
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "또는 볼륨 버튼을 눌러 말하세요",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStatus(isConnected: Boolean, isGroupOwner: Boolean) {
    val text = if (isConnected) if (isGroupOwner) "연결됨 (서버)" else "연결됨 (클라이언트)" else "연결되지 않음"
    val color = if (isConnected) Color(0xFF4CAF50) else Color.Gray
    Box(
        modifier = Modifier.fillMaxWidth().background(color, MaterialTheme.shapes.medium).padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun PeerItem(name: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f))
        Button(onClick = onClick) { Text("연결") }
    }
}

@Composable
fun PushToTalkButton(onPress: () -> Unit, onRelease: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    val color = if (isPressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(CircleShape)
            .background(color)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPress()
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION") vibrator.vibrate(50)
                        }

                        try { awaitRelease() } finally {
                            isPressed = false
                            onRelease()
                            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 100)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION") vibrator.vibrate(150)
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(if (isPressed) "송신 중..." else "눌러서 말하기", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}