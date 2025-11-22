package com.trps02.p2pwalkietalkie

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.trps02.p2pwalkietalkie.ui.theme.P2PWalkieTalkieTheme
import com.trps02.p2pwalkietalkie.viewmodel.MainViewModel

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val viewModel: MainViewModel by viewModels()

    private var isHardwarePttPressed = false
    private lateinit var toneGenerator: ToneGenerator
    private lateinit var vibrator: Vibrator
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [삭제됨] 관리자 권한 설정 로직 제거

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // 백그라운드 서비스 시작
        Intent(this, WalkieTalkieService::class.java).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        // 앱이 NFC 태그로 실행된 경우 처리
        intent?.let { processNfcIntent(it) }

        setContent {
            P2PWalkieTalkieTheme {
                val permissionsToRequest = arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.VIBRATE,
                    Manifest.permission.ACCESS_WIFI_STATE
                ) + if (Build.VERSION.SDK_INT >= 33) {
                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS)
                } else if (Build.VERSION.SDK_INT >= 31) {
                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }

                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                    onResult = {
                        checkOverlayPermission()
                    }
                )

                LaunchedEffect(Unit) {
                    launcher.launch(permissionsToRequest)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { processNfcIntent(it) }
    }

    private fun processNfcIntent(intent: Intent) {
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {

            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            tag?.let {
                val tagId = it.id.joinToString("") { byte -> "%02x".format(byte) }
                runOnUiThread {
                    viewModel.onNfcTagScanned(tagId)
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1234)
            Toast.makeText(this, "필수 권한: '다른 앱 위에 표시'를 허용해주세요.", Toast.LENGTH_LONG).show()
        } else {
            startOverlayService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1234) {
            if (Settings.canDrawOverlays(this)) {
                startOverlayService()
            } else {
                Toast.makeText(this, "권한이 없어 앱을 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.bindService()
        nfcAdapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            null
        )

        if (!isAccessibilityServiceEnabled(this, DialogDetectorService::class.java)) {
            Toast.makeText(this, "팝업 감지를 위해 '접근성' 권한을 켜주세요.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.unbindService()
        nfcAdapter?.disableReaderMode(this)
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