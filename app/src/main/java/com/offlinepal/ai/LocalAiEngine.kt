package com.offlinepal.ai

import android.content.Context

class LocalAiEngine(private val context: Context) {

    data class AiResponse(val text: String)

    fun process(input: String): AiResponse {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return AiResponse("Please type something.")
        val lower = trimmed.lowercase()

        if (lower == "help") return AiResponse(
            "I can help with:\n" +
            "- General knowledge questions\n" +
            "- Math: type 'calculate 25 times 4'\n" +
            "- Unit conversion: type '10 km to miles'\n" +
            "- Summarize: type 'summarize: your text here'\n" +
            "- Word count: type 'count words your text'"
        )

        if (lower.startsWith("calculate ") || lower.startsWith("compute ")) {
            val expr = lower.replace("calculate", "").replace("compute", "").trim()
            val r = tryMath(expr)
            if (r != null) return AiResponse("Result: $r")
        }

        val conv = tryConvert(lower)
        if (conv != null) return AiResponse(conv)

        if (lower.startsWith("summarize:") || lower.startsWith("summarize ")) {
            val text = trimmed.substringAfter(":").trim().ifBlank {
                trimmed.removePrefix("summarize").trim()
            }
            return AiResponse(summarize(text))
        }

        if (lower.startsWith("count words ")) {
            val words = trimmed.removePrefix("count words").trim()
                .split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            return AiResponse("Word count: $words words")
        }

        if (lower.matches(Regex("(hi|hello|hey|howdy).*"))) {
            return AiResponse("Hello! How can I help you? Type 'help' to see what I can do.")
        }

        if (lower.contains("thank")) return AiResponse("You are welcome!")

        val kb = KnowledgeBase.findAnswer(trimmed)
        if (kb != null) return AiResponse(kb)

        return if (trimmed.split("\\s+".toRegex()).size > 15)
            AiResponse(summarize(trimmed))
        else
            AiResponse("I am not sure about that. Try a knowledge question or type 'help'.")
    }

    private fun summarize(text: String): String {
        if (text.length < 80) return "Text is already short: $text"
        val sentences = text.split(Regex("[.!?]+")).map { it.trim() }.filter { it.length > 10 }
        if (sentences.isEmpty()) return "Could not summarize this text."
        val summary = sentences.take(3).joinToString(". ") + "."
        val pct = ((1 - summary.length.toFloat() / text.length) * 100).toInt().coerceAtLeast(0)
        return "Summary ($pct% shorter):\n\n$summary"
    }

    private fun tryMath(expr: String): String? {
        return try {
            val clean = expr.replace(" ", "").replace("percent", "/100.0*")
            val result = evalExpr(clean)
            if (result == result.toLong().toDouble()) result.toLong().toString()
            else "%.4f".format(result).trimEnd('0').trimEnd('.')
        } catch (e: Exception) {
            null
        }
    }

    private fun evalExpr(e: String): Double {
        var pos = 0

        fun parseNum(): Double {
            val start = pos
            if (pos < e.length && (e[pos] == '-' || e[pos] == '+')) pos++
            while (pos < e.length && (e[pos].isDigit() || e[pos] == '.')) pos++
            return e.substring(start, pos).toDouble()
        }

        fun parseFactor(): Double {
            return if (pos < e.length && e[pos] == '(') {
                pos++
                val r = parseExpr()
                if (pos < e.length && e[pos] == ')') pos++
                r
            } else parseNum()
        }

        fun parseTerm(): Double {
            var r = parseFactor()
            while (pos < e.length && (e[pos] == '*' || e[pos] == '/')) {
                val op = e[pos++]
                r = if (op == '*') r * parseFactor() else r / parseFactor()
            }
            return r
        }

        fun parseExpr(): Double {
            var r = parseTerm()
            while (pos < e.length && (e[pos] == '+' || e[pos] == '-')) {
                val op = e[pos++]
                r = if (op == '+') r + parseTerm() else r - parseTerm()
            }
            return r
        }

        return parseExpr()
    }

    private fun tryConvert(s: String): String? {
        val patterns = listOf(
            Pair(Regex("(\\d+\\.?\\d*)\\s*km\\s*to\\s*miles?"), Pair(0.621371, "miles")),
            Pair(Regex("(\\d+\\.?\\d*)\\s*miles?\\s*to\\s*km"), Pair(1.60934, "km")),
            Pair(Regex("(\\d+\\.?\\d*)\\s*kg\\s*to\\s*(lbs?|pounds?)"), Pair(2.20462, "lbs")),
            Pair(Regex("(\\d+\\.?\\d*)\\s*(lbs?|pounds?)\\s*to\\s*kg"), Pair(0.453592, "kg")),
            Pair(Regex("(\\d+\\.?\\d*)\\s*m\\s*to\\s*(ft|feet)"), Pair(3.28084, "feet")),
            Pair(Regex("(\\d+\\.?\\d*)\\s*(ft|feet)\\s*to\\s*m"), Pair(0.3048, "m"))
        )
        for ((rx, conv) in patterns) {
            val m = rx.find(s) ?: continue
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            return "%.2f".format(v * conv.first) + " " + conv.second
        }
        if (s.contains("celsius") && s.contains("fahrenheit")) {
            val v = Regex("(\\d+\\.?\\d*)").find(s)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: return null
            return "%.1f".format(v * 9.0 / 5.0 + 32) + " F"
        }
        if (s.contains("fahrenheit") && s.contains("celsius")) {
            val v = Regex("(\\d+\\.?\\d*)").find(s)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: return null
            return "%.1f".format((v - 32) * 5.0 / 9.0) + " C"
        }
        return null
    }

    fun close() {}
}
