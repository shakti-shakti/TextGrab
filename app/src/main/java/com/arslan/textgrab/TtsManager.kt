package com.arslan.textgrab

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentSpeechRate = 1.0f

    var onStatusChanged: ((Boolean) -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onStatusChanged?.invoke(true)
                }

                override fun onDone(utteranceId: String?) {
                    onStatusChanged?.invoke(false)
                }

                override fun onError(utteranceId: String?) {
                    onStatusChanged?.invoke(false)
                }
            })
        }
    }

    fun speak(text: String, rate: Float = currentSpeechRate) {
        if (!isInitialized || tts == null) return
        currentSpeechRate = rate
        tts?.setSpeechRate(rate)
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TextGrabUtterance")
        onStatusChanged?.invoke(true)
    }

    fun stop() {
        tts?.stop()
        onStatusChanged?.invoke(false)
    }

    val isSpeaking: Boolean
        get() = tts?.isSpeaking == true

    fun setLanguage(locale: Locale) {
        if (isInitialized) {
            tts?.language = locale
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
