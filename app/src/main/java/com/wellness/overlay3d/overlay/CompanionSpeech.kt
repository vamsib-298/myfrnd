package com.wellness.overlay3d.overlay

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Synthesizes the bundled US English RMS voice offline. Call playback controls on the main thread. */
internal class CompanionSpeech {
    private val scope = MainScope()
    private var work: Job? = null
    private var player: MediaPlayer? = null
    private var session = 0L

    @Keep private external fun synthesize(text: String): ByteArray?

    fun speak(text: String, talking: (Boolean) -> Unit, fallback: (String) -> Unit) {
        stop()
        if (text.isBlank()) return
        // Preserve the device engine's pronunciation for scripts outside this English voice's lexicon.
        if (text.any { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }) {
            fallback(text)
            return
        }
        val ticket = session
        fun next(start: Int) {
            if (ticket != session) return
            if (start >= text.length) {
                talking(false)
                return
            }
            var end = (start + 320).coerceAtMost(text.length)
            if (end < text.length) {
                val split = text.lastIndexOf(' ', end)
                if (split > start) end = split + 1
            }
            work = scope.launch {
                try {
                    val audio = withContext(Dispatchers.Default) {
                        System.loadLibrary("companion_voice")
                        checkNotNull(synthesize(text.substring(start, end)))
                    }
                    if (ticket != session) return@launch
                    val media = MediaPlayer()
                    player = media
                    media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    media.setDataSource(WaveSource(audio))
                    media.setOnPreparedListener {
                        if (ticket == session) {
                            it.start()
                            talking(true)
                        }
                    }
                    media.setOnCompletionListener {
                        if (ticket == session) {
                            it.release()
                            player = null
                            next(end)
                        }
                    }
                    media.setOnErrorListener { _, _, _ ->
                        if (ticket == session) {
                            stop()
                            talking(false)
                            fallback(text.substring(start))
                        }
                        true
                    }
                    media.prepareAsync()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (ticket == session) {
                        Log.w("CompanionSpeech", "Offline playback unavailable", error)
                        stop()
                        talking(false)
                        fallback(text.substring(start))
                    }
                } catch (error: LinkageError) {
                    if (ticket == session) {
                        Log.w("CompanionSpeech", "Voice unavailable for this runtime", error)
                        talking(false)
                        fallback(text.substring(start))
                    }
                }
            }
        }
        next(0)
    }

    fun stop() {
        session++
        work?.cancel()
        work = null
        player?.release()
        player = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private class WaveSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun getSize() = bytes.size.toLong()
        override fun close() = Unit
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position !in 0 until bytes.size.toLong()) return -1
            val start = position.toInt()
            val count = minOf(size, bytes.size - start)
            bytes.copyInto(buffer, offset, start, start + count)
            return count
        }
    }
}
