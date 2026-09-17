package com.localizafamilia.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VozHelper(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: () -> Unit
) {

    private var recon: SpeechRecognizer? = SpeechRecognizer.createSpeechRecognizer(context)

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val texto = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (!texto.isNullOrEmpty()) onResult(texto) else onError()
        }

        override fun onError(error: Int) {
            onError()
        }
    }

    fun iniciar() {
        val sr = recon ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        sr.setRecognitionListener(listener)
        sr.startListening(intent)
    }

    fun parar() {
        val sr = recon
        recon = null
        if (sr != null) {
            try {
                sr.stopListening()
                sr.cancel()
                sr.destroy()
            } catch (_: Exception) {
            }
        }
    }
}