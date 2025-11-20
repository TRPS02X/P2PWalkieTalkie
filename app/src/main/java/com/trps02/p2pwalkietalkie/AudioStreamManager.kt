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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

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

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var sendAudioJob: Job? = null
    private var receiveAudioJob: Job? = null

    private val isGroupOwner = connectionInfo.isGroupOwner

    companion object {
        const val SERVER_PORT = 8988
    }

    fun startServerAndReceiver() {
        scope.launch {
            try {
                if (isGroupOwner) {
                    Log.d(TAG, "Starting ServerSocket...")
                    serverSocket = ServerSocket(SERVER_PORT)
                    clientSocket = serverSocket?.accept()
                } else {
                    Log.d(TAG, "Connecting to Server...")
                    val hostAddress = connectionInfo.groupOwnerAddress.hostAddress
                    if (hostAddress != null) {
                        clientSocket = Socket()
                        clientSocket?.connect(InetSocketAddress(hostAddress, SERVER_PORT), 5000)
                    } else {
                        return@launch
                    }
                }

                inputStream = clientSocket?.getInputStream()
                outputStream = clientSocket?.getOutputStream()

                startReceivingAudio()

            } catch (e: Exception) {
                Log.e(TAG, "Error starting server/client", e)
                disconnect()
            }
        }
    }

    private fun startReceivingAudio() {
        receiveAudioJob = scope.launch {
            audioTrack.play()
            val buffer = ByteArray(bufferSize)
            try {
                while (isActive && inputStream != null) {
                    val read = inputStream!!.read(buffer)
                    if (read > 0) {
                        audioTrack.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error receiving audio", e)
            } finally {
                audioTrack.stop()
            }
        }
    }

    fun startStreaming() {
        if (sendAudioJob?.isActive == true) return

        sendAudioJob = scope.launch {
            audioRecord.startRecording()
            val buffer = ByteArray(bufferSize)
            try {
                while (isActive && outputStream != null) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        outputStream!!.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming audio", e)
            } finally {
                audioRecord.stop()
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
            receiveAudioJob?.cancel()
            audioRecord.release()
            audioTrack.release()
            toneGenerator.release()
            inputStream?.close()
            outputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }
}