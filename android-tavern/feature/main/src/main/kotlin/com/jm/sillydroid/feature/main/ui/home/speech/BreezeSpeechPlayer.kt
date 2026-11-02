package com.jm.sillydroid.feature.main.ui.home.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class BreezeVoiceConfig(val apiKey: String, val voiceId: String)

/**
 * Breeze TTS 流式朗读：24 kHz 单声道 16 位 PCM 边收边播，首包约 0.5–0.7 秒。
 *
 * 请求参数沿用 clawd 实测语调最稳的组合：breeze-tts-2、zh、只开 use_speaker_boost、不加 instructions。
 * 下载和播放分成两个协程，下一段可以在上一段还没播完时就开始请求，段与段之间不断档。
 */
class BreezeSpeechPlayer(
    context: Context,
    private val scope: CoroutineScope,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val connections = CopyOnWriteArraySet<HttpURLConnection>()
    @Volatile private var activeTrack: AudioTrack? = null
    private var job: Job? = null

    val isSpeaking: Boolean
        get() = job?.isActive == true

    fun speak(config: BreezeVoiceConfig, segments: List<String>) {
        stop()
        if (segments.isEmpty()) {
            return
        }
        job = scope.launch(Dispatchers.IO) {
            val track = createTrack()
            activeTrack = track
            val focusRequest = requestFocus()
            withContext(Dispatchers.Main) { onSpeakingChanged(true) }
            try {
                play(config, segments, track)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isActive) {
                    withContext(Dispatchers.Main) { onError(error.message ?: error.javaClass.simpleName) }
                }
            } finally {
                activeTrack = null
                runCatching { track.pause() }
                runCatching { track.flush() }
                track.release()
                audioManager.abandonAudioFocusRequest(focusRequest)
                withContext(Dispatchers.Main + NonCancellable) { onSpeakingChanged(false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // 网络读和 AudioTrack 写都是阻塞调用，取消协程打断不了，直接断连接、暂停播放让它们返回。
        connections.forEach { connection -> connection.disconnect() }
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    private suspend fun play(config: BreezeVoiceConfig, segments: List<String>, track: AudioTrack) {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val producer = scope.launch(Dispatchers.IO) {
            try {
                for (segment in segments) {
                    if (!isActive) break
                    streamSegment(config, segment) { chunk -> chunks.send(chunk) }
                }
                chunks.close()
            } catch (error: Exception) {
                chunks.close(error)
            }
        }
        try {
            var writtenBytes = 0L
            var started = false
            val pending = ArrayList<ByteArray>()
            var pendingBytes = 0
            for (chunk in chunks) {
                if (started) {
                    writtenBytes += writeFully(track, chunk)
                    continue
                }
                pending += chunk
                pendingBytes += chunk.size
                if (pendingBytes >= PREBUFFER_BYTES) {
                    pending.forEach { part -> writtenBytes += writeFully(track, part) }
                    pending.clear()
                    currentCoroutineContext().ensureActive()
                    track.play()
                    started = true
                }
            }
            if (!started) {
                pending.forEach { part -> writtenBytes += writeFully(track, part) }
                currentCoroutineContext().ensureActive()
                track.play()
            }
            awaitDrained(track, writtenBytes / BYTES_PER_FRAME)
        } finally {
            producer.cancel()
        }
    }

    /** 读一段的 PCM 流；奇数字节留到下一块，保证交给 AudioTrack 的都是完整采样。 */
    private suspend fun streamSegment(config: BreezeVoiceConfig, text: String, emit: suspend (ByteArray) -> Unit) {
        val url = URL("$API_BASE/${URLEncoder.encode(config.voiceId, "UTF-8")}/stream?output_format=pcm")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("xi-api-key", config.apiKey)
            setRequestProperty("Content-Type", "application/json")
        }
        connections += connection
        try {
            val body = JSONObject()
                .put("text", text)
                .put("model_id", "breeze-tts-2")
                .put("language_code", "zh")
                .put("voice_settings", JSONObject().put("use_speaker_boost", true))
                .toString()
                .toByteArray()
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { output -> output.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
                throw IOException("Breeze $code：${detail.take(200)}")
            }
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                // 上一块多出来的半个采样（-1 表示没有）。
                var carry = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    val offset = if (carry >= 0) 1 else 0
                    val total = offset + read
                    val usable = total - total % BYTES_PER_FRAME
                    if (usable > 0) {
                        val chunk = ByteArray(usable)
                        if (carry >= 0) chunk[0] = carry.toByte()
                        System.arraycopy(buffer, 0, chunk, offset, usable - offset)
                        emit(chunk)
                    }
                    carry = if (total % BYTES_PER_FRAME != 0) buffer[read - 1].toInt() and 0xFF else -1
                }
            }
        } finally {
            connections -= connection
            connection.disconnect()
        }
    }

    private fun writeFully(track: AudioTrack, chunk: ByteArray): Int {
        var offset = 0
        while (offset < chunk.size) {
            val written = track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
        return offset
    }

    /** 等缓冲里的声音播完再释放，避免最后半句被截掉。 */
    private suspend fun awaitDrained(track: AudioTrack, totalFrames: Long) {
        val deadline = System.currentTimeMillis() + totalFrames * 1000 / SAMPLE_RATE + 2_000
        while ((track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < totalFrames && System.currentTimeMillis() < deadline) {
            delay(50)
        }
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(speechAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            // 至少 1 秒缓冲：开播前先写入的预缓冲必须放得下，否则 play() 之前的写入会一直阻塞。
            .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE * BYTES_PER_FRAME))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun requestFocus(): AudioFocusRequest {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(speechAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    stop()
                }
            }
            .build()
        audioManager.requestAudioFocus(request)
        return request
    }

    private companion object {
        const val API_BASE = "https://api.breeze.blue/v1/text-to-speech"
        const val SAMPLE_RATE = 24_000
        const val BYTES_PER_FRAME = 2
        // 攒够 0.4 秒再开播，防止网络抖动造成开头断续。
        const val PREBUFFER_BYTES = SAMPLE_RATE * BYTES_PER_FRAME * 2 / 5

        val speechAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
