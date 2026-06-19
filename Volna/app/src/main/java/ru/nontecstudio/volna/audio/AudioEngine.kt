package ru.nontecstudio.volna.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

class AudioEngine(private val onFrameRecorded: (ByteArray, Int) -> Unit) {

    private val sampleRate = 16000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = 1024

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // ОТКРЫТЫЙ ДОСТУП: Убран private, изменен на val
    val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording.getAndSet(true)) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfigIn, audioFormat, bufferSize
        )

        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (isRecording.get()) {
                val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readBytes > 0) {
                    onFrameRecorded(buffer, readBytes)
                }
            }
        }.apply { start() }
    }

    fun stopRecording() {
        isRecording.set(false)
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
    }

    @SuppressLint("MissingPermission")
    fun startPlaying() {
        audioTrack = AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            sampleRate, channelConfigOut, audioFormat, bufferSize,
            AudioTrack.MODE_STREAM
        )
        audioTrack?.play()
    }

    fun playFrame(data: ByteArray, size: Int) {
        audioTrack?.write(data, 0, size)
    }

    fun stopPlaying() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null
    }
}