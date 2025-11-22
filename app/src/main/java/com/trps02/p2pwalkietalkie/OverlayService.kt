package com.trps02.p2pwalkietalkie

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.*
import com.trps02.p2pwalkietalkie.ui.theme.P2PWalkieTalkieTheme
import com.trps02.p2pwalkietalkie.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import java.lang.reflect.Method

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ComposeView
    private lateinit var viewModel: MainViewModel

    private var isHardwarePttPressed = false
    private lateinit var toneGenerator: ToneGenerator
    private lateinit var vibrator: Vibrator

    private lateinit var layoutParams: WindowManager.LayoutParams

    private val NOTIFICATION_ID = 102

    // 상태바 강제 닫기용 루프 변수
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // [핵심] 서비스 시작 시 접근성 서비스에 '상태바 차단' 명령 전송
        val blockIntent = Intent(this, DialogDetectorService::class.java)
        blockIntent.action = "ACTION_BLOCK_STATUS_BAR"
        startService(blockIntent)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        viewModel = MainViewModel(application)

        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // 보조 수단: 상태바 강제 닫기 루프 실행
        isRunning = true
        Thread {
            while (isRunning) {
                try {
                    Thread.sleep(500)
                    collapseStatusBar() // 0.5초마다 닫기 시도
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()

        overlayView = ComposeView(this).apply {
            val lifecycleOwner = MyLifecycleOwner()
            lifecycleOwner.performRestore(null)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = ViewModelStore()
            })

            // 상태바 아이콘 흰색으로 변경 (검은 배경에 잘 보이게)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var flags = systemUiVisibility
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                systemUiVisibility = flags
            }

            setContent {
                P2PWalkieTalkieTheme {
                    DisposableEffect(Unit) {
                        viewModel.bindService()
                        onDispose { viewModel.unbindService() }
                    }

                    // 전체 화면 UI 표시 (투명 차단막은 DialogDetectorService로 이동했으므로 여기선 제거됨)
                    OverlayUI(
                        viewModel = viewModel,
                        onExit = {
                            // 앱 종료 시 모든 서비스 정리
                            val stopIntent = Intent(this@OverlayService, WalkieTalkieService::class.java)
                            stopService(stopIntent)

                            // 차단막 해제 명령 전송
                            val unblockIntent = Intent(this@OverlayService, DialogDetectorService::class.java)
                            unblockIntent.action = "ACTION_UNBLOCK_STATUS_BAR"
                            startService(unblockIntent)

                            stopSelf()
                            System.exit(0)
                        },
                        onMinimize = { minimizeOverlay() },
                        onBrightnessChange = { brightness ->
                            updateBrightness(brightness)
                        }
                    )
                }
            }
        }

        // 볼륨키 PTT 리스너 (접근성 서비스가 없을 때를 대비한 보조 수단)
        overlayView.isFocusableInTouchMode = true
        overlayView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (!isHardwarePttPressed) {
                        isHardwarePttPressed = true
                        viewModel.startRecording()
                        performHapticAndSound(true)
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (isHardwarePttPressed) {
                        isHardwarePttPressed = false
                        viewModel.stopRecording()
                        performHapticAndSound(false)
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.LEFT
        layoutParams.screenBrightness = -1f

        windowManager.addView(overlayView, layoutParams)
    }

    // Reflection을 이용한 상태바 닫기
    @SuppressLint("WrongConstant")
    private fun collapseStatusBar() {
        try {
            val service = getSystemService("statusbar")
            val statusbarManager = Class.forName("android.app.StatusBarManager")
            val collapse: Method = if (Build.VERSION.SDK_INT <= 16) {
                statusbarManager.getMethod("collapse")
            } else {
                statusbarManager.getMethod("collapsePanels")
            }
            collapse.setAccessible(true)
            collapse.invoke(service)
        } catch (e: Exception) {}
    }

    private fun updateBrightness(value: Float) {
        layoutParams.screenBrightness = value
        windowManager.updateViewLayout(overlayView, layoutParams)
    }

    private fun minimizeOverlay() {
        layoutParams.width = 1
        layoutParams.height = 1
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(overlayView, layoutParams)

        android.os.Handler(Looper.getMainLooper()).postDelayed({
            restoreOverlay()
        }, 10000)
    }

    private fun restoreOverlay() {
        layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        windowManager.updateViewLayout(overlayView, layoutParams)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_MINIMIZE") {
            minimizeOverlay()
            return START_STICKY
        }
        if (intent?.action == "ACTION_PTT_DOWN") {
            if (!isHardwarePttPressed) {
                isHardwarePttPressed = true
                viewModel.startRecording()
                performHapticAndSound(true)
            }
            return START_STICKY
        }
        if (intent?.action == "ACTION_PTT_UP") {
            if (isHardwarePttPressed) {
                isHardwarePttPressed = false
                viewModel.stopRecording()
                performHapticAndSound(false)
            }
            return START_STICKY
        }

        val channelId = "overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Kiosk Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }.setContentTitle("키오스크 모드").setSmallIcon(R.drawable.ic_launcher_foreground).build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
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
        super.onDestroy()
        isRunning = false // 루프 종료

        // 종료 시 차단 해제 명령 전송 (안전장치)
        val unblockIntent = Intent(this, DialogDetectorService::class.java)
        unblockIntent.action = "ACTION_UNBLOCK_STATUS_BAR"
        startService(unblockIntent)

        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        toneGenerator.release()
    }
}

@Composable
fun OverlayUI(viewModel: MainViewModel, onExit: () -> Unit, onMinimize: () -> Unit, onBrightnessChange: (Float) -> Unit) {
    val peers by viewModel.peers.collectAsState()
    val connectionInfo by viewModel.connectionInfo.collectAsState()
    val isConnected = connectionInfo?.groupFormed == true

    val isLocked by viewModel.isLocked.collectAsState()
    val isRegistering by viewModel.isRegisteringCard.collectAsState()
    val isGroupOwner = connectionInfo?.isGroupOwner == true

    Column(modifier = Modifier.fillMaxSize().background(ComposeColor.Black)) {

        Spacer(modifier = Modifier.height(30.dp)) // 상태바 공간 확보

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            ConnectionStatus(isConnected, isGroupOwner)

            BrightnessControl(onBrightnessChange)

            Spacer(modifier = Modifier.height(16.dp))

            if (!isConnected) {
                if (isRegistering) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("관리자 키 등록 모드", fontWeight = FontWeight.Bold)
                            Text("NFC 카드를 태그하세요.", fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { viewModel.setRegisteringMode(false) }) { Text("취소") }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ComposeColor.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("자동 탐색 중입니다...", color = ComposeColor.LightGray, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.padding(8.dp))

                    OutlinedButton(onClick = { viewModel.setRegisteringMode(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("관리자 키(NFC) 등록")
                    }

                    Spacer(modifier = Modifier.padding(8.dp))
                    OutlinedButton(onClick = onMinimize, modifier = Modifier.fillMaxWidth()) {
                        Text("화면 잠시 숨기기")
                    }

                    Spacer(modifier = Modifier.padding(8.dp))
                    VolumeControl()

                    Spacer(modifier = Modifier.padding(8.dp))
                    OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = ComposeColor.Red)) {
                        Text("앱 종료")
                    }
                    Spacer(modifier = Modifier.padding(8.dp))

                    Text("발견된 기기", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White)
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(peers) { device ->
                            PeerItem(device.deviceName) { viewModel.connectToDevice(device.deviceAddress) }
                        }
                    }
                }
            } else {
                if (!isLocked) {
                    Button(onClick = { viewModel.disconnect() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("연결 끊기", fontSize = 16.sp)
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        Text("잠김: 관리자 키를 태그하여 해제", color = ComposeColor.Gray, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isGroupOwner) {
                    OutlinedButton(onClick = onMinimize, modifier = Modifier.fillMaxWidth()) {
                        Text("화면 잠시 숨기기")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                VolumeControl()

                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    PushToTalkButton({ viewModel.startRecording() }, { viewModel.stopRecording() })
                }
            }
        }
    }
}

@Composable
fun BrightnessControl(onBrightnessChange: (Float) -> Unit) {
    var sliderPosition by remember { mutableFloatStateOf(0.5f) }

    Card(
        colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF222222)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Text("화면 밝기", color = ComposeColor.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            Slider(
                value = sliderPosition,
                onValueChange = {
                    sliderPosition = it
                    val brightness = 0.01f + (it * 0.99f)
                    onBrightnessChange(brightness)
                },
                colors = SliderDefaults.colors(
                    thumbColor = ComposeColor.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = ComposeColor.Gray
                )
            )
        }
    }
}

@Composable
fun VolumeControl() {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var currentVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val maxVolume by remember { mutableIntStateOf(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) }

    Card(
        colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF222222)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("볼륨", color = ComposeColor.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                    currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }) {
                    Text("-", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }

                Text(text = "$currentVolume / $maxVolume", color = ComposeColor.White, modifier = Modifier.padding(horizontal = 16.dp))

                FilledIconButton(onClick = {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                    currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }) {
                    Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CustomStatusBar() {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf("") }
    var batteryPct by remember { mutableStateOf(0) }
    var wifiLevel by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("a hh:mm", Locale.getDefault()).format(Date())
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) batteryPct = (level * 100) / scale
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLevel = WifiManager.calculateSignalLevel(wifiManager.connectionInfo.rssi, 5)
            delay(1000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ComposeColor(0xFF121212))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = currentTime, color = ComposeColor.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val wifiIcon = when (wifiLevel) { 0 -> "⚪" 1 -> "🔈" 2 -> "🔉" 3 -> "🔊" else -> "📶" }
            Text(text = "$wifiIcon ", color = ComposeColor.White, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "$batteryPct%", color = if (batteryPct > 20) ComposeColor.White else ComposeColor.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
fun ConnectionStatus(isConnected: Boolean, isGroupOwner: Boolean) {
    val text = if (isConnected) if (isGroupOwner) "연결됨 (호스트)" else "연결됨 (클라이언트)" else "연결되지 않음"
    val color = if (isConnected) ComposeColor(0xFF4CAF50) else ComposeColor.Gray
    Box(
        modifier = Modifier.fillMaxWidth().background(color, MaterialTheme.shapes.medium).padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = ComposeColor.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun PeerItem(name: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f), color = ComposeColor.White)
        Button(onClick = onClick) { Text("연결") }
    }
}

@Composable
fun PushToTalkButton(onPress: () -> Unit, onRelease: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        else @Suppress("DEPRECATION") vibrator.vibrate(50)
                        try { awaitRelease() } finally {
                            isPressed = false
                            onRelease()
                            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 100)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                            else @Suppress("DEPRECATION") vibrator.vibrate(150)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(if (isPressed) "송신 중..." else "눌러서 말하기", color = ComposeColor.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

class MyLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    fun handleLifecycleEvent(event: Lifecycle.Event) { lifecycleRegistry.handleLifecycleEvent(event) }
    fun performRestore(savedState: Bundle?) { savedStateRegistryController.performRestore(savedState) }
}