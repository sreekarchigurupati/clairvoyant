package com.clairvoyant.glasses.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Continuous voice command listener for hands-free permission control.
 * Listens for keywords: "approve", "deny", "allow", "reject", "scroll", "back".
 * Automatically restarts listening after each recognition result.
 */
class VoiceCommandListener(
    private val context: Context,
    private val callback: Callback
) {
    enum class Command {
        APPROVE, DENY, SCROLL_DOWN, SCROLL_UP, GO_BACK, UNKNOWN
    }

    interface Callback {
        fun onVoiceCommand(command: Command)
        fun onVoiceListeningStateChanged(listening: Boolean)
    }

    companion object {
        private const val TAG = "ClairvoyantVoice"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var shouldRestart = true

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }

        shouldRestart = true

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
        }

        beginListening()
    }

    fun stopListening() {
        shouldRestart = false
        isListening = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        callback.onVoiceListeningStateChanged(false)
    }

    fun destroy() {
        shouldRestart = false
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun beginListening() {
        if (!shouldRestart) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Short speech expected (single command words)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            callback.onVoiceListeningStateChanged(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isListening = false
        }
    }

    private fun parseCommand(text: String): Command {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("approve") || lower.contains("allow") ||
            lower.contains("accept") || lower.contains("yes") ||
            lower.contains("confirm") || lower.contains("okay") -> Command.APPROVE

            lower.contains("deny") || lower.contains("reject") ||
            lower.contains("decline") || lower.contains("no") ||
            lower.contains("cancel") || lower.contains("block") -> Command.DENY

            lower.contains("scroll down") || lower.contains("down") ||
            lower.contains("next") -> Command.SCROLL_DOWN

            lower.contains("scroll up") || lower.contains("up") ||
            lower.contains("previous") -> Command.SCROLL_UP

            lower.contains("back") || lower.contains("go back") ||
            lower.contains("exit") || lower.contains("close") -> Command.GO_BACK

            else -> Command.UNKNOWN
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            callback.onVoiceListeningStateChanged(true)
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech began")
        }

        override fun onRmsChanged(rmsdB: Float) { /* ignore */ }
        override fun onBufferReceived(buffer: ByteArray?) { /* ignore */ }

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
            isListening = false
            callback.onVoiceListeningStateChanged(false)
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                else -> "Error $error"
            }
            Log.d(TAG, "Recognition error: $errorMsg")
            isListening = false

            // Auto-restart for recoverable errors
            if (shouldRestart && error != SpeechRecognizer.ERROR_CLIENT) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    beginListening()
                }, 1000)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null) {
                for (match in matches) {
                    Log.d(TAG, "Heard: $match")
                    val command = parseCommand(match)
                    if (command != Command.UNKNOWN) {
                        callback.onVoiceCommand(command)
                        break
                    }
                }
            }

            // Restart listening for next command
            isListening = false
            if (shouldRestart) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    beginListening()
                }, 500)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null) {
                for (match in matches) {
                    val command = parseCommand(match)
                    // Only act on approve/deny from partial results (time-sensitive)
                    if (command == Command.APPROVE || command == Command.DENY) {
                        Log.d(TAG, "Partial match for command: $command")
                        callback.onVoiceCommand(command)
                        break
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) { /* ignore */ }
    }
}
