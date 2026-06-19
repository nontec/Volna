package ru.nontecstudio.volna.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ru.nontecstudio.volna.audio.AudioEngine
import ru.nontecstudio.volna.network.UdpReceiver
import ru.nontecstudio.volna.network.UdpSender

class WalkieTalkieService : Service() {

    private val binder = LocalBinder()

    lateinit var audioEngine: AudioEngine
        private set
    lateinit var udpSender: UdpSender
        private set
    lateinit var udpReceiver: UdpReceiver
        private set

    // Интерфейс обратного вызова для передачи имени устройства в Activity лог
    var onFrameReceivedListener: ((String, ByteArray) -> Unit)? = null

    // Приемник системных событий: гашение экрана принудительно глушит передачу звука
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                if (::audioEngine.isInitialized) {
                    audioEngine.stopRecording()
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundService()

        // Регистрируем ресивер блокировки экрана
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        udpSender = UdpSender()

        // Настройка приемника со встроенной защитой от акустической петли (эха)
        udpReceiver = UdpReceiver { deviceName, audioData ->
            // Проверяем, инициализирован ли движок и идет ли запись в данный момент
            if (::audioEngine.isInitialized && audioEngine.isRecording.get()) {
                return@UdpReceiver
            }

            onFrameReceivedListener?.invoke(deviceName, audioData)
            audioEngine.playFrame(audioData, audioData.size)
        }

        audioEngine = AudioEngine { buffer, size ->
            udpSender.sendFrame(buffer, size)
        }

        udpReceiver.startListening()
        audioEngine.startPlaying()
    }

    private fun startForegroundService() {
        val channelId = "volna_service_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Volna Walkie-Talkie",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Volna")
            .setContentText("Рация активна в фоновом режиме")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        udpReceiver.stopListening()
        udpSender.release()
        audioEngine.stopRecording()
        audioEngine.stopPlaying()
    }
}