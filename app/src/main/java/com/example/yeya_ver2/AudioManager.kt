import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import android.media.*
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import android.Manifest
import android.media.*
import kotlinx.coroutines.withContext


class AudioManager(private val context: Context) {
    private val TAG = "AudioManager"
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)




    private fun checkAudioRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun startAudioCapture(onAudioAvailable: suspend (ByteArray) -> Unit) {
        if (!checkAudioRecordPermission()) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        withContext(Dispatchers.IO) {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                return@withContext
            }

            audioRecord?.startRecording()

            val buffer = ShortArray(bufferSize / 2)
            while (true) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    val compressedData = compressAudio(buffer, bytesRead)
                    onAudioAvailable(compressedData)
                }
            }
        }
    }

    private fun compressAudio(audioData: ShortArray, size: Int): ByteArray {
        val byteOutputStream = ByteArrayOutputStream()
        for (i in 0 until size) {
            val sample = (audioData[i].toInt() shr 2).toShort()
            byteOutputStream.write(sample.toByte().toInt())
            byteOutputStream.write((sample.toInt() shr 8).toByte().toInt())
        }
        return byteOutputStream.toByteArray()
    }



    fun stopAudioCapture() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    suspend fun playAudio(audioData: ByteArray) {
        withContext(Dispatchers.IO) {
            if (audioTrack == null) {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }

            audioTrack?.play()
            val decompressedData = decompressAudio(audioData)
            audioTrack?.write(decompressedData, 0, decompressedData.size)
        }
    }

    private fun decompressAudio(compressedData: ByteArray): ShortArray {
        val shortBuffer = ShortArray(compressedData.size / 2)
        val byteBuffer = ByteBuffer.wrap(compressedData).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shortBuffer.indices) {
            val sample = byteBuffer.short
            shortBuffer[i] = (sample.toInt() shl 2).toShort()
        }
        return shortBuffer
    }

    fun stopAudioPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}