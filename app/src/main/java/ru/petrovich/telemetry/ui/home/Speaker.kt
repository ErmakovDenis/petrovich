package ru.petrovich.telemetry.ui.home

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Озвучка доклада системным синтезатором речи. */
class Speaker(context: Context) {
    var speaking by mutableStateOf(false)
        private set

    /** false — на устройстве нет русского синтезатора. */
    var available by mutableStateOf(true)
        private set

    /** Скорость речи: 1, 1.5 или 2× — настоящий параметр синтезатора, не декорация. */
    var rate by mutableStateOf(1f)
        private set

    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            val engine = tts
            ready = status == TextToSpeech.SUCCESS && engine != null &&
                engine.setLanguage(Locale("ru", "RU")) >= TextToSpeech.LANG_AVAILABLE
            available = ready
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { speaking = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { speaking = false }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { speaking = false }
            })
        }
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (!ready) { available = false; return }
        engine.setSpeechRate(rate)
        speaking = true
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "brief")
    }

    /** Переключает скорость по кругу 1× → 1.5× → 2× → 1×; на лету, даже во время чтения. */
    fun cycleRate() {
        rate = when {
            rate < 1.25f -> 1.5f
            rate < 1.75f -> 2f
            else -> 1f
        }
        if (speaking) tts?.setSpeechRate(rate)
    }

    fun stop() {
        tts?.stop()
        speaking = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        speaking = false
    }
}

@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current.applicationContext
    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}
