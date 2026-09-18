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
        speaking = true
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "brief")
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
