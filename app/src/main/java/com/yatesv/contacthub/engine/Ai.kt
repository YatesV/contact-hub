package com.yatesv.contacthub.engine

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}

/**
 * Smart replies, catch-up summaries, translation and voice-message transcription.
 * Works on the phone with no account. If you add an AI key (Grok or OpenAI) in Settings → AI,
 * replies sound more like you and summaries are written by the AI. Messages only leave the phone
 * when you've added a key.
 */
object Ai {
    private val s get() = Hub.settings.value
    val hasKey get() = s.aiProvider != "off" && s.aiKey.isNotBlank()

    private fun base(provider: String) = if (provider == "openai") "https://api.openai.com/v1" else "https://api.x.ai/v1"

    private fun http(url: String, key: String, body: JSONObject? = null): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 40000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            if (body != null) { requestMethod = "POST"; doOutput = true }
        }
        body?.let { b -> c.outputStream.use { it.write(b.toString().toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IllegalStateException(when (code) {
            401, 403 -> "The AI key wasn't accepted"
            429 -> "AI rate limit or out of credits"
            404 -> "AI model not found (tap Test in Settings → AI to pick one)"
            else -> "AI error $code"
        })
        return JSONObject(text)
    }

    /** Checks the key and picks a fast, cheap chat model from the provider's list. Returns the model id. */
    suspend fun setup(provider: String, key: String): String = withContext(Dispatchers.IO) {
        val ids = http("${base(provider)}/models", key).optJSONArray("data")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") } }.orEmpty()
        if (ids.isEmpty()) error("No models available for this key")
        val chat = ids.filter { id ->
            val l = id.lowercase()
            if (provider == "openai") (l.startsWith("gpt") || l.startsWith("o")) && listOf("audio", "realtime", "image", "tts", "transcribe", "search", "embedding").none { it in l }
            else "grok" in l && listOf("image", "vision", "imagine", "video").none { it in l }
        }.ifEmpty { ids }
        // Prefer small/fast models: these features need speed, not deep reasoning
        chat.firstOrNull { "mini" in it.lowercase() || "fast" in it.lowercase() } ?: chat.sortedDescending().first()
    }

    private suspend fun chat(system: String, user: String, maxTokens: Int = 300): String = withContext(Dispatchers.IO) {
        val st = s
        val model = st.aiModel.ifBlank { setup(st.aiProvider, st.aiKey).also { m -> Hub.settings.update { it.copy(aiModel = m) } } }
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user)))
        http("${base(st.aiProvider)}/chat/completions", st.aiKey, body)
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
    }

    // ---------------- Smart replies ----------------

    /**
     * Up to 3 short replies to the latest incoming message. [history] is oldest → newest.
     * With an AI key they copy how you text (from your own messages in this chat).
     */
    suspend fun replies(history: List<Msg>, nameOf: (Msg) -> String): List<String> {
        val recent = history.filter { it.body.isNotBlank() }.takeLast(12)
        if (recent.isEmpty() || recent.last().outgoing) return emptyList()
        if (hasKey) runCatching {
            val mine = history.filter { it.outgoing && it.body.isNotBlank() }.takeLast(25).joinToString("\n") { "- " + it.body.take(140) }
            val convo = recent.joinToString("\n") { (if (it.outgoing) "Me" else nameOf(it)) + ": " + it.body.take(300) }
            val out = chat(
                "You write text message replies for the user. Match the user's own texting style exactly (length, slang, capitalization, punctuation, emoji use) " +
                    "based on examples of their past messages. Give 3 different short replies (a yes-ish, a no-ish or question, and a casual one). " +
                    "One per line, no numbering, no quotes, nothing else.",
                "How I text:\n$mine\n\nConversation:\n$convo\n\nMy replies:", 120)
            return out.lines().map { it.trim().trimStart('-', '•', '*', ' ').removeSurrounding("\"") }.filter { it.isNotBlank() && it.length < 160 }.take(3)
        }.onFailure { HubLog.w("Ai", "AI replies failed, using on-device", it) }
        return onDeviceReplies(recent)
    }

    /** Google's on-device Smart Reply (English only, nothing leaves the phone). */
    suspend fun onDeviceReplies(recent: List<Msg>): List<String> = runCatching {
        val conv = recent.map { m ->
            if (m.outgoing) TextMessage.createForLocalUser(m.body, m.date)
            else TextMessage.createForRemoteUser(m.body, m.date, m.sender.ifBlank { "them" })
        }
        val r = SmartReply.getClient().suggestReplies(conv).await()
        if (r.status == SmartReplySuggestionResult.STATUS_SUCCESS) r.suggestions.map { it.text }.take(3) else emptyList()
    }.getOrElse { HubLog.w("Ai", "smart reply failed", it); emptyList() }

    // ---------------- Catch me up ----------------

    /** AI summary of a chat, or null without a key (the screen then shows the offline catch-up). */
    suspend fun summary(lines: List<Pair<String, String>>): String? {
        if (!hasKey || lines.isEmpty()) return null
        val convo = lines.takeLast(150).joinToString("\n") { (who, text) -> "$who: ${text.take(400)}" }
        return chat("Summarize this text conversation for the user in 3-6 short bullet points. Call out anything asked of the user, plans, times and decisions. Plain text, start each line with •.",
            convo, 350)
    }

    // ---------------- Translation ----------------

    /** Translates to [Settings.translateTo] (or the phone's language). Downloads the language pack the first time (~30 MB each). */
    suspend fun translate(text: String): Result<Pair<String, String>> = runCatching {
        val src = LanguageIdentification.getClient().identifyLanguage(text).await()
        if (src == "und") error("Couldn't tell what language that is")
        val target = s.translateTo.ifBlank { java.util.Locale.getDefault().language }
        val from = TranslateLanguage.fromLanguageTag(src) ?: error("Can't translate from $src yet")
        val to = TranslateLanguage.fromLanguageTag(target) ?: TranslateLanguage.ENGLISH
        if (from == to) return@runCatching text to java.util.Locale(src).displayLanguage
        val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            t.translate(text).await() to java.util.Locale(src).displayLanguage
        } finally { t.close() }
    }

    // ---------------- Voice messages ----------------

    /**
     * Turns a voice message into text using Android's on-device speech recognition (Android 13+).
     * The audio is decoded to 16 kHz mono and fed to the recognizer; nothing is uploaded.
     */
    suspend fun transcribe(ctx: Context, uri: Uri): Result<String> = runCatching {
        if (Build.VERSION.SDK_INT < 33) error("Needs Android 13 or newer")
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) error("On-device speech recognition isn't available. Turn on offline speech in Google settings.")
        val pcm = withContext(Dispatchers.IO) { decodePcm16kMono(ctx, uri) }
        if (pcm.isEmpty()) error("Couldn't read that audio")
        withTimeoutOrNull(120_000) { recognize(ctx, pcm) } ?: error("Took too long")
    }

    private suspend fun recognize(ctx: Context, pcm: ByteArray): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val pipe = ParcelFileDescriptor.createPipe()
            val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            val parts = ArrayList<String>()
            fun done(err: String?) {
                runCatching { rec.destroy() }; runCatching { pipe[0].close() }
                if (!cont.isActive) return
                val text = parts.joinToString(" ").trim()
                if (text.isNotBlank()) cont.resume(text) else cont.resumeWithException(IllegalStateException(err ?: "No speech found"))
            }
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onSegmentResults(b: Bundle) { b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { parts += it } }
                override fun onEndOfSegmentedSession() = done(null)
                override fun onResults(b: Bundle) { b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { parts += it }; done(null) }
                override fun onError(e: Int) = done(if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "No speech found" else "Speech error $e")
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            rec.startListening(intent)
            // Feed the audio, then close the pipe so the recognizer knows it's over
            Thread {
                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm) } }
            }.start()
            cont.invokeOnCancellation { runCatching { rec.destroy() } }
        }
    }

    /** Decodes any audio Android can play into 16-bit 16 kHz mono PCM. */
    private fun decodePcm16kMono(ctx: Context, uri: Uri): ByteArray {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return ByteArray(0)
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mono = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
            } else if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val samples = ShortArray(buf.remaining()); buf.get(samples)
                // Mix down to mono
                var k = 0
                while (k + channels <= samples.size) {
                    var sum = 0; for (c in 0 until channels) sum += samples[k + c]
                    val v = (sum / channels).toShort()
                    mono.write(v.toInt() and 0xFF); mono.write((v.toInt() shr 8) and 0xFF)
                    k += channels
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        return resample16k(mono.toByteArray(), rate)
    }

    /** Linear resample of 16-bit mono PCM to 16 kHz. */
    fun resample16k(pcm: ByteArray, rate: Int): ByteArray {
        if (rate == 16000 || pcm.size < 4) return pcm
        val src = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = src.remaining(); val inS = ShortArray(n); src.get(inS)
        val outN = (n.toLong() * 16000 / rate).toInt()
        val out = java.nio.ByteBuffer.allocate(outN * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outN) {
            val pos = i.toDouble() * rate / 16000
            val a = pos.toInt().coerceAtMost(n - 1); val b = (a + 1).coerceAtMost(n - 1); val f = pos - a
            out.putShort((inS[a] + (inS[b] - inS[a]) * f).toInt().toShort())
        }
        return out.array()
    }
}
