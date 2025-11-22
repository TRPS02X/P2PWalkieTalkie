package com.trps02.p2pwalkietalkie

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.wifi.p2p.WifiP2pInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("MissingPermission")
class AudioStreamManager(private val connectionInfo: WifiP2pInfo) {

    private val TAG = "AudioStreamManager"
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private val audioRecord: AudioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize
    )

    private val audioTrack: AudioTrack = AudioTrack(
        AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, audioFormat, bufferSize, AudioTrack.MODE_STREAM
    )

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    // --- 소켓 관련 (다중 접속 지원) ---
    private var serverSocket: ServerSocket? = null

    // 클라이언트용 (내가 게스트일 때)
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    // 서버용 (내가 호스트일 때 관리할 접속자 목록)
    // CopyOnWriteArrayList는 쓰기보다 읽기가 많을 때 안전한 리스트입니다.
    private val clientSockets = CopyOnWriteArrayList<Socket>()

    private var sendAudioJob: Job? = null
    // 수신 작업은 여러 개일 수 있으므로 리스트로 관리하지 않고, 각 소켓 연결 시 바로 실행합니다.

    // 상태 변수
    private var isReceiving = false
    private var lastReceiveTime = 0L

    private val isGroupOwner = connectionInfo.isGroupOwner

    companion object {
        const val SERVER_PORT = 8988
    }

    fun startServerAndReceiver() {
        scope.launch {
            try {
                if (isGroupOwner) {
                    // [호스트/서버]
                    Log.d(TAG, "Starting ServerSocket (Multi-Client)...")
                    serverSocket = ServerSocket(SERVER_PORT)

                    // 서버는 계속해서 새로운 클라이언트를 받습니다. (무한 루프)
                    while (isActive) {
                        try {
                            val client = serverSocket?.accept()
                            if (client != null) {
                                Log.d(TAG, "New client connected: ${client.inetAddress}")
                                clientSockets.add(client)
                                // 접속한 클라이언트로부터 오디오 수신 시작 (별도 코루틴)
                                startReceivingFrom(client.getInputStream())
                            }
                        } catch (e: Exception) {
                            // accept 중 오류 발생 시 (소켓 닫힘 등) 루프 종료
                            if (isActive) Log.e(TAG, "Error accepting client", e)
                            break
                        }
                    }
                } else {
                    // [게스트/클라이언트]
                    Log.d(TAG, "Connecting to Server...")
                    val hostAddress = connectionInfo.groupOwnerAddress.hostAddress
                    if (hostAddress != null) {
                        // 재시도 로직 (서버가 준비될 때까지 3번 시도)
                        for (i in 1..3) {
                            try {
                                clientSocket = Socket()
                                clientSocket?.connect(InetSocketAddress(hostAddress, SERVER_PORT), 5000)
                                Log.d(TAG, "Connected to server.")
                                break
                            } catch (e: Exception) {
                                Log.w(TAG, "Connection attempt $i failed. Retrying...")
                                delay(1000)
                            }
                        }

                        if (clientSocket?.isConnected == true) {
                            inputStream = clientSocket?.getInputStream()
                            outputStream = clientSocket?.getOutputStream()
                            startReceivingFrom(inputStream!!)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in startServerAndReceiver", e)
            }
        }

        // 수신 모니터링 (비프음 처리용)
        startMonitoringReceiving()
    }

    // 개별 스트림에서 오디오 수신
    private fun startReceivingFrom(stream: InputStream) {
        scope.launch {
            try {
                if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.play()
                }

                val buffer = ByteArray(bufferSize)
                while (isActive) {
                    val read = stream.read(buffer)
                    if (read > 0) {
                        // 데이터 수신 감지 (비프음 로직)
                        if (!isReceiving) {
                            isReceiving = true
                            toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 150)
                        }
                        lastReceiveTime = System.currentTimeMillis()

                        // 오디오 재생
                        audioTrack.write(buffer, 0, read)
                    } else if (read == -1) {
                        break // 스트림 종료
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error receiving audio stream", e)
            }
        }
    }

    // 수신 상태 모니터링 (말이 끝났는지 체크)
    private fun startMonitoringReceiving() {
        scope.launch {
            while (isActive) {
                delay(100)
                if (isReceiving && (System.currentTimeMillis() - lastReceiveTime > 400)) {
                    isReceiving = false
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                }
            }
        }
    }

    fun startStreaming() {
        if (sendAudioJob?.isActive == true) return
        if (isReceiving) return // 반이중 통신 (듣는 중엔 말하기 금지)

        sendAudioJob = scope.launch {
            try {
                audioRecord.startRecording()
                val buffer = ByteArray(bufferSize)

                while (isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // [전송 로직]
                        if (isGroupOwner) {
                            // 호스트는 연결된 모든 게스트에게 전송
                            for (client in clientSockets) {
                                try {
                                    client.getOutputStream().write(buffer, 0, read)
                                } catch (e: Exception) {
                                    // 전송 실패한 클라이언트는 나중에 정리됨
                                }
                            }
                        } else {
                            // 게스트는 호스트에게만 전송
                            outputStream?.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming audio", e)
            } finally {
                try { audioRecord.stop() } catch (e: Exception) {}
            }
        }
    }

    fun stopStreaming() {
        sendAudioJob?.cancel()
        sendAudioJob = null
    }

    fun disconnect() {
        try {
            sendAudioJob?.cancel()
            // 리소스 정리
            serverSocket?.close()
            clientSocket?.close()

            // 모든 클라이언트 소켓 닫기
            clientSockets.forEach { try { it.close() } catch (e: Exception) {} }
            clientSockets.clear()

            audioRecord.release()
            audioTrack.release()
            toneGenerator.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }
}