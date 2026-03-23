package com.offlinepal.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * LocalAiEngine — Real on-device LLM using SmolLM2 135M via llama.cpp.
 *
 * Model is bundled in assets/model/smollm2.gguf at build time.
 * Falls back gracefully to smart intent engine if model fails to load.
 */
class LocalAiEngine(private val context: Context) {

    private var llamaInference: Any? = null
    private var isLoaded = false
    private val conversationHistory = mutableListOf<Pair<String, String>>() // user, assistant

    data class AiResult(
        val text: String,
        val intent: DetectedIntent? = null
    )

    data class DetectedIntent(
        val type: IntentType,
        val params: Map<String, String> = emptyMap()
    )

    enum class IntentType {
        SET_TIMER, CANCEL_TIMER,
        SET_ALARM, CANCEL_ALARM,
        ADD_NOTE, SHOW_NOTES,
        ADD_TODO, SHOW_TODOS,
        SHOW_TIMERS, SHOW_ALARMS,
        NONE
    }

    // ─── Initialisation ───────────────────────────────────────────────────────

    fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelFile = ensureModel()
                if (!modelFile.exists() || modelFile.length() < 1_000_000) {
                    withContext(Dispatchers.Main) { onError("Model file missing or too small") }
                    return@launch
                }

                Log.i(TAG, "Loading SmolLM2 (${modelFile.length() / 1_000_000}MB) ...")

                // Load llama.cpp model via JNI
                llamaInference = MediaPipeLLM.createInference(context, modelFile.absolutePath)

                if (llamaInference == null) {
                    withContext(Dispatchers.Main) { onError("llama.cpp returned null context") }
                    return@launch
                }

                isLoaded = true
                Log.i(TAG, "SmolLM2 loaded OK, context=$llamaContext")
                withContext(Dispatchers.Main) { onReady() }

            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "llama.cpp native library not found — smart fallback mode: ${e.message}")
                withContext(Dispatchers.Main) { onError("native lib not found") }
            } catch (e: Exception) {
                Log.e(TAG, "Model load error: ${e.message}", e)
                withContext(Dispatchers.Main) { onError(e.message ?: "unknown error") }
            }
        }
    }

    private fun ensureModel(): File {
        val dest = File(context.filesDir, "smollm2.bin")
        if (dest.exists() && dest.length() > 1_000_000) {
            Log.i(TAG, "Model already in internal storage (${dest.length() / 1_000_000}MB)")
            return dest
        }
        Log.i(TAG, "Copying model from assets ...")
        try {
            context.assets.open("model/smollm2.bin").use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1024 * 512)
                }
            }
            Log.i(TAG, "Model copied: ${dest.length() / 1_000_000}MB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model: ${e.message}")
        }
        return dest
    }

    // ─── Main Processing ──────────────────────────────────────────────────────

    suspend fun process(userInput: String): AiResult = withContext(Dispatchers.IO) {
        val trimmed = userInput.trim()
        val lower = trimmed.lowercase()

        // Detect intent regardless of LLM
        val intent = detectIntent(trimmed, lower)

        val responseText = if (isLoaded && llamaInference != null) {
            try {
                generateWithLLM(trimmed)
            } catch (e: Exception) {
                Log.e(TAG, "LLM generation failed: ${e.message}")
                fallbackResponse(lower, intent)
            }
        } else {
            fallbackResponse(lower, intent)
        }

        AiResult(text = responseText, intent = intent)
    }

    private fun generateWithLLM(userInput: String): String {
        // Build ChatML prompt
        val historyPrompt = conversationHistory.takeLast(4).joinToString("") { (u, a) ->
            "<|im_start|>user\n$u<|im_end|>\n<|im_start|>assistant\n$a<|im_end|>\n"
        }

        val systemPrompt = """You are Pal, a helpful personal AI assistant on someone's phone.
You help with timers, alarms, notes, tasks, math, general questions, and conversation.
Keep responses SHORT (1-3 sentences). Be friendly and natural."""

        val prompt = "<|im_start|>system\n$systemPrompt<|im_end|>\n" +
                historyPrompt +
                "<|im_start|>user\n$userInput<|im_end|>\n" +
                "<|im_start|>assistant\n"

        val response = MediaPipeLLM.generate(llamaInference!!, prompt).trim()

        // Store in history
        conversationHistory.add(Pair(userInput, response))
        if (conversationHistory.size > 6) conversationHistory.removeAt(0)

        return response.ifBlank { fallbackResponse(userInput.lowercase(), null) }
    }

    // ─── Intent Detection ─────────────────────────────────────────────────────

    private fun detectIntent(input: String, lower: String): DetectedIntent? {

        // Cancel timer
        if (lower.contains("cancel") && (lower.contains("timer") || lower.contains("countdown"))) {
            return DetectedIntent(IntentType.CANCEL_TIMER)
        }

        // Timer — very flexible matching
        val timerRegexes = listOf(
            Regex("(\\d+(?:\\.\\d+)?)\\s*(?:and\\s*a\\s*half\\s*)?(?:min(?:ute)?s?|m)(?:\\s*(?:timer|countdown))?"),
            Regex("(\\d+)\\s*(?:hour|hr)s?(?:\\s*(?:timer|countdown))?"),
            Regex("(\\d+)\\s*sec(?:ond)?s?(?:\\s*(?:timer|countdown))?"),
            Regex("(?:set|start|create|put|add|make)?\\s*(?:a\\s*)?(?:timer|countdown)\\s*(?:for|of)?\\s*(\\d+)")
        )
        val timerUnits = mapOf("min" to "minutes", "minute" to "minutes", "minutes" to "minutes",
            "m" to "minutes", "hour" to "hours", "hr" to "hours", "hours" to "hours",
            "sec" to "seconds", "second" to "seconds", "seconds" to "seconds")

        for (regex in timerRegexes) {
            val match = regex.find(lower) ?: continue
            val value = match.groupValues[1].toDoubleOrNull() ?: continue
            val unit = when {
                lower.contains("hour") || lower.contains("hr") -> "hours"
                lower.contains("sec") -> "seconds"
                else -> "minutes"
            }
            val seconds = when (unit) {
                "hours" -> (value * 3600).toLong()
                "seconds" -> value.toLong()
                else -> (value * 60).toLong()
            }
            // Extract label - anything after "for" that isn't the time
            val label = extractTimerLabel(input) ?: "Timer"
            return DetectedIntent(IntentType.SET_TIMER, mapOf("seconds" to seconds.toString(), "label" to label))
        }

        // Alarm — time patterns
        val alarmWords = listOf("alarm", "wake", "remind", "reminder", "alert", "notify", "notification")
        val hasAlarmWord = alarmWords.any { lower.contains(it) }
        val timeRegex = Regex("(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|AM|PM)?")
        val timeMatch = timeRegex.find(lower)

        if ((hasAlarmWord || lower.contains(" at ")) && timeMatch != null) {
            var hour = timeMatch.groupValues[1].toIntOrNull() ?: return null
            val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
            val ampm = timeMatch.groupValues[3].lowercase()
            when {
                ampm == "pm" && hour != 12 -> hour += 12
                ampm == "am" && hour == 12 -> hour = 0
                ampm.isEmpty() && hour < 7 -> hour += 12 // Assume PM for ambiguous times like "3"
            }
            val label = extractAlarmLabel(input) ?: "Alarm"
            return DetectedIntent(IntentType.SET_ALARM, mapOf("hour" to hour.toString(), "minute" to minute.toString(), "label" to label))
        }

        // Notes
        val noteWords = listOf("note", "remember", "save", "write", "jot", "record")
        if (noteWords.any { lower.contains(it) }) {
            val content = extractNoteContent(input)
            if (content.isNotBlank()) return DetectedIntent(IntentType.ADD_NOTE, mapOf("content" to content))
        }

        // Show notes
        if ((lower.contains("show") || lower.contains("see") || lower.contains("list") || lower.contains("what")) && lower.contains("note"))
            return DetectedIntent(IntentType.SHOW_NOTES)

        // Todo
        val todoAddWords = listOf("add", "buy", "get", "pick up", "grab", "need to", "todo", "task")
        val listWords = listOf("shopping", "grocery", "work", "personal", "list")
        if (todoAddWords.any { lower.contains(it) } && (listWords.any { lower.contains(it) } || lower.contains("task") || lower.contains("todo"))) {
            val task = extractTodoTask(input)
            val cat = when {
                lower.contains("shopping") || lower.contains("grocery") || lower.contains("buy") || lower.contains("get") -> "shopping"
                lower.contains("work") -> "work"
                lower.contains("personal") -> "personal"
                else -> "general"
            }
            if (task.isNotBlank()) return DetectedIntent(IntentType.ADD_TODO, mapOf("task" to task, "category" to cat))
        }

        // Show todos
        if ((lower.contains("show") || lower.contains("see") || lower.contains("list") || lower.contains("what")) &&
            (lower.contains("todo") || lower.contains("task") || lower.contains("shopping")))
            return DetectedIntent(IntentType.SHOW_TODOS)

        // Show timers / alarms
        if (lower.contains("show") || lower.contains("active")) {
            if (lower.contains("timer")) return DetectedIntent(IntentType.SHOW_TIMERS)
            if (lower.contains("alarm")) return DetectedIntent(IntentType.SHOW_ALARMS)
        }

        return null
    }

    private fun extractTimerLabel(input: String): String? {
        val patterns = listOf(
            Regex("(?:for|called|named|label)\\s+([a-zA-Z][a-zA-Z ]{2,20})", RegexOption.IGNORE_CASE),
            Regex("([a-zA-Z][a-zA-Z ]{2,15})\\s+timer", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(input) ?: continue
            val candidate = m.groupValues[1].trim()
            if (candidate.isNotBlank() && !candidate.matches(Regex("\\d+.*"))) return candidate
        }
        return null
    }

    private fun extractAlarmLabel(input: String): String? {
        val patterns = listOf(
            Regex("(?:to|for)\\s+([a-zA-Z][\\w\\s]{2,30})$", RegexOption.IGNORE_CASE),
            Regex("(?:remind me to|alarm for)\\s+([a-zA-Z][\\w\\s]{2,30})", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(input) ?: continue
            val c = m.groupValues[1].trim()
            if (c.isNotBlank()) return c
        }
        return null
    }

    private fun extractNoteContent(input: String): String {
        return input
            .replace(Regex("(?i)^(note|remember|save|write down|jot|record)\\s*(that|this|:)?\\s*"), "")
            .replace(Regex("(?i)^(pal|hey pal|ok pal),?\\s*"), "")
            .trim()
    }

    private fun extractTodoTask(input: String): String {
        return input
            .replace(Regex("(?i)(add|put|create|make|set|include)\\s*"), "")
            .replace(Regex("(?i)(to my|in my|on my|to the|in the)\\s*(shopping|grocery|todo|task|work|personal)?\\s*(list|tasks?)?"), "")
            .replace(Regex("(?i)(shopping|grocery|todo|task|work|personal)\\s*(list|tasks?)?"), "")
            .trim()
            .ifBlank { input.trim() }
    }

    // ─── Smart Fallback ───────────────────────────────────────────────────────

    private fun fallbackResponse(lower: String, intent: DetectedIntent?): String {
        if (intent != null) {
            return when (intent.type) {
                IntentType.SET_TIMER -> {
                    val secs = intent.params["seconds"]?.toLongOrNull() ?: 60
                    val label = intent.params["label"] ?: "Timer"
                    val display = formatSeconds(secs)
                    "Done! \u23F1\uFE0F I've set a **$display** timer. I'll notify you when it's done."
                }
                IntentType.SET_ALARM -> {
                    val h = intent.params["hour"]?.toIntOrNull() ?: 8
                    val m = intent.params["minute"]?.toIntOrNull() ?: 0
                    val ampm = if (h >= 12) "PM" else "AM"
                    val dh = if (h > 12) h - 12 else if (h == 0) 12 else h
                    val label = intent.params["label"] ?: "Alarm"
                    "Got it! \uD83D\uDD14 Alarm set for **$dh:${m.toString().padStart(2,'0')} $ampm** — \"$label\"."
                }
                IntentType.ADD_NOTE -> "Saved! \uD83D\uDCDD Your note has been stored."
                IntentType.ADD_TODO -> {
                    val task = intent.params["task"] ?: "item"
                    val cat = intent.params["category"] ?: "general"
                    "Added \"${task.trim()}\" to your **$cat** list! ✅"
                }
                IntentType.SHOW_TIMERS -> "Here are your active timers:"
                IntentType.SHOW_NOTES -> "Here are your notes:"
                IntentType.SHOW_TODOS -> "Here are your tasks:"
                IntentType.SHOW_ALARMS -> "Here are your scheduled alarms:"
                IntentType.CANCEL_TIMER -> "Cancelling your timer now. ✋"
                else -> generateKnowledgeResponse(lower)
            }
        }
        return generateKnowledgeResponse(lower)
    }

    private fun generateKnowledgeResponse(lower: String): String {
        if (lower.matches(Regex("(hi|hello|hey|howdy|sup|yo).*")))
            return "Hey! \uD83D\uDC4B What can I help you with? Try setting a timer, adding a note, or just ask me anything!"

        if (lower.contains("who are you") || lower.contains("what can you do") || lower.contains("help"))
            return "I'm Pal, your offline AI. I can:\n⏱ Set timers — \"2 min timer\"\n🔔 Set alarms — \"wake me at 7am\"\n📝 Save notes — \"remember that...\"\n✅ Add tasks — \"add milk to shopping\"\n🧮 Do math — \"15% of 240\"\n💬 Answer questions — just ask!"

        if (lower.contains("thank")) return "You're welcome! 😊 Anything else?"

        val math = tryMath(lower)
        if (math != null) return math

        val conv = tryConvert(lower)
        if (conv != null) return conv

        val kb = KnowledgeBase.answer(lower)
        if (kb != null) return kb

        return "I'm not sure about that one. The AI model is initialising — try again shortly, or ask me to set a timer, alarm, or save a note!"
    }

    private fun tryMath(expr: String): String? {
        val raw = expr
            .replace(Regex("(?i)(calculate|compute|what'?s|what is|=|math|solve)"), "")
            .replace(Regex("(?i)percent of"), "/100*")
            .replace(Regex("(?i)(times|multiplied by|x)"), "*")
            .replace(Regex("(?i)(divided by|over)"), "/")
            .replace(Regex("(?i)(plus|added to)"), "+")
            .replace(Regex("(?i)(minus|subtract)"), "-")
            .trim()
        if (raw.length < 2) return null
        return try {
            val clean = raw.replace(" ", "")
            val result = MathEval(clean).evaluate()
            val formatted = if (result == result.toLong().toDouble()) result.toLong().toString()
            else "%.4f".format(result).trimEnd('0').trimEnd('.')
            "\uD83E\uDDEE $raw = **$formatted**"
        } catch (e: Exception) { null }
    }

    private fun tryConvert(s: String): String? {
        Regex("(\\d+\\.?\\d*)\\s*km\\s*to\\s*miles?").find(s)?.let {
            return "📏 %.2f miles".format(it.groupValues[1].toDouble() * 0.621371) }
        Regex("(\\d+\\.?\\d*)\\s*miles?\\s*to\\s*km").find(s)?.let {
            return "📏 %.2f km".format(it.groupValues[1].toDouble() * 1.60934) }
        Regex("(\\d+\\.?\\d*)\\s*kg\\s*to\\s*(lbs?|pounds?)").find(s)?.let {
            return "⚖️ %.2f lbs".format(it.groupValues[1].toDouble() * 2.20462) }
        Regex("(\\d+\\.?\\d*)\\s*(lbs?|pounds?)\\s*to\\s*kg").find(s)?.let {
            return "⚖️ %.2f kg".format(it.groupValues[1].toDouble() * 0.453592) }
        val num = Regex("(\\d+\\.?\\d*)").find(s)?.groupValues?.get(1)?.toDoubleOrNull()
        if (num != null) {
            if (s.contains("celsius") && s.contains("fahrenheit")) return "🌡️ %.1f°F".format(num * 9 / 5 + 32)
            if (s.contains("fahrenheit") && s.contains("celsius")) return "🌡️ %.1f°C".format((num - 32) * 5 / 9)
        }
        return null
    }

    private fun formatSeconds(s: Long): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${if(sec>0) "${sec}s" else ""}"
            else -> "${sec}s"
        }
    }

    fun clearHistory() = conversationHistory.clear()

    fun close() {
        if (llamaInference != null) {
            llamaInference?.let { MediaPipeLLM.close(it) }
            llamaInference = null
        }
        isLoaded = false
    }

    companion object {
        private const val TAG = "LocalAiEngine"
    }
}


// MediaPipe LLM Inference bridge
// Uses MediaPipe Tasks GenAI which supports GGUF models
private object MediaPipeLLM {
    fun createInference(context: android.content.Context, modelPath: String): Any? {
        return try {
            val cls = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
            val optCls = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
            val builderCls = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions\$Builder")
            val builder = optCls.getMethod("builder").invoke(null)
            builderCls.getMethod("setModelPath", String::class.java).invoke(builder, modelPath)
            builderCls.getMethod("setMaxTokens", Int::class.java).invoke(builder, 512)
            builderCls.getMethod("setTemperature", Float::class.java).invoke(builder, 0.7f)
            val options = builderCls.getMethod("build").invoke(builder)
            cls.getMethod("createFromOptions", android.content.Context::class.java, optCls).invoke(null, context, options)
        } catch (e: Exception) {
            android.util.Log.w("MediaPipeLLM", "Failed to init: ${e.message}")
            null
        }
    }

    fun generate(inference: Any, prompt: String): String {
        return try {
            val cls = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
            cls.getMethod("generateResponse", String::class.java).invoke(inference, prompt) as? String ?: ""
        } catch (e: Exception) {
            android.util.Log.e("MediaPipeLLM", "Generate failed: ${e.message}")
            ""
        }
    }

    fun close(inference: Any) {
        try {
            val cls = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
            cls.getMethod("close").invoke(inference)
        } catch (e: Exception) {}
    }
}

// Math evaluator
private class MathEval(private val expr: String) {
    private var pos = 0
    fun evaluate(): Double = parseExpr()
    private fun parseExpr(): Double {
        var r = parseTerm()
        while (pos < expr.length && (expr[pos] == '+' || expr[pos] == '-')) {
            val op = expr[pos++]; r = if (op == '+') r + parseTerm() else r - parseTerm()
        }
        return r
    }
    private fun parseTerm(): Double {
        var r = parseFactor()
        while (pos < expr.length && (expr[pos] == '*' || expr[pos] == '/')) {
            val op = expr[pos++]; r = if (op == '*') r * parseFactor() else r / parseFactor()
        }
        return r
    }
    private fun parseFactor(): Double {
        if (pos < expr.length && expr[pos] == '(') {
            pos++; val r = parseExpr(); if (pos < expr.length && expr[pos] == ')') pos++; return r
        }
        val start = pos
        if (pos < expr.length && (expr[pos] == '-' || expr[pos] == '+')) pos++
        while (pos < expr.length && (expr[pos].isDigit() || expr[pos] == '.')) pos++
        return expr.substring(start, pos).toDouble()
    }
}
