package ru.nontecstudio.volna.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean

class UdpReceiver(
    private val port: Int = 50005,
    private val onFrameReceived: (deviceName: String, audioData: ByteArray) -> Unit
) {
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var thread: Thread? = null
    private val packetPrefix = "VOLNA"

    fun startListening() {
        if (isRunning.getAndSet(true)) return

        thread = Thread {
            try {
                socket = DatagramSocket(port).apply { reuseAddress = true }
                val buffer = ByteArray(4096)

                while (isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    if (packet.length > 6) { // Минимальный размер с префиксом и длиной имени
                        val data = packet.data

                        // Проверяем наш ли это пакет
                        val prefix = String(data, 0, 5, Charsets.UTF_8)
                        if (prefix == packetPrefix) {
                            val nameLen = data[5].toInt() and 0xFF
                            val deviceName = String(data, 6, nameLen, Charsets.UTF_8)

                            val headerSize = 6 + nameLen
                            val audioSize = packet.length - headerSize

                            if (audioSize > 0) {
                                val audioData = ByteArray(audioSize)
                                System.arraycopy(data, headerSize, audioData, 0, audioSize)
                                onFrameReceived(deviceName, audioData)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                socket?.close()
            }
        }.apply { start() }
    }

    fun stopListening() {
        isRunning.set(false)
        socket?.close()
        thread?.interrupt()
        thread = null
    }
}