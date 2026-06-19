package ru.nontecstudio.volna.network

import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class UdpSender(private val port: Int = 50005) {
    private var socket: DatagramSocket? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val broadcastAddress = InetAddress.getByName("255.255.255.255")

    // Берем имя модели телефона, ограничиваем 32 байтами для предсказуемости размера
    private val deviceNameBytes = Build.MODEL.take(32).toByteArray(Charsets.UTF_8)
    private val packetPrefix = "VOLNA".toByteArray(Charsets.UTF_8) // 5 байт

    init {
        executor.execute {
            socket = DatagramSocket().apply { broadcast = true }
        }
    }

    fun sendFrame(buffer: ByteArray, size: Int) {
        if (size <= 0) return
        executor.execute {
            try {
                // Структура пакета: [PREFIX (5B)] [NAME_LEN (1B)] [NAME (max 32B)] [AUDIO_DATA]
                val headerSize = packetPrefix.size + 1 + deviceNameBytes.size
                val totalSize = headerSize + size
                val packetData = ByteArray(totalSize)

                // Собираем заголовок
                System.arraycopy(packetPrefix, 0, packetData, 0, packetPrefix.size)
                packetData[packetPrefix.size] = deviceNameBytes.size.toByte()
                System.arraycopy(deviceNameBytes, 0, packetData, packetPrefix.size + 1, deviceNameBytes.size)

                // Добавляем аудио
                System.arraycopy(buffer, 0, packetData, headerSize, size)

                val packet = DatagramPacket(packetData, totalSize, broadcastAddress, port)
                socket?.send(packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun release() {
        executor.execute { socket?.close(); socket = null }
        executor.shutdown()
    }
}