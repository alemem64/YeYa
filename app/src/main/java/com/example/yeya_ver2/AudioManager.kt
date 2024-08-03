import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer

class AudioManager(private val context: Context) {
    private val TAG = "AudioManager"
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private fun checkAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun startAudioCapture(onAudioAvailable: suspend (ByteArray) -> Unit) {
        if (!checkAudioPermission(context)) {
            Log.e(TAG, "Audio permission not granted")
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

            val buffer = ByteArray(bufferSize)
            while (true) {
                val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                if (bytesRead > 0) {
                    onAudioAvailable(buffer.copyOf(bytesRead))
                }
            }
        }
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
            audioTrack?.write(audioData, 0, audioData.size)
        }
    }

    fun stopAudioPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}