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
            val clean = expr.replace(" ", "").replace("x", "*").replace("times", "*")
            val result = MathEval(clean).evaluate()
            if (result == result.toLong().toDouble()) result.toLong().toString()
            else "%.4f".format(result).trimEnd('0').trimEnd('.')
        } catch (e: Exception) {
            null
        }
    }

    private fun tryConvert(s: String): String? {
        val km = Regex("(\\d+\\.?\\d*)\\s*km\\s*to\\s*miles?").find(s)
        if (km != null) return "%.2f miles".format(km.groupValues[1].toDouble() * 0.621371)

        val mi = Regex("(\\d+\\.?\\d*)\\s*miles?\\s*to\\s*km").find(s)
        if (mi != null) return "%.2f km".format(mi.groupValues[1].toDouble() * 1.60934)

        val kg = Regex("(\\d+\\.?\\d*)\\s*kg\\s*to\\s*(lbs?|pounds?)").find(s)
        if (kg != null) return "%.2f lbs".format(kg.groupValues[1].toDouble() * 2.20462)

        val lb = Regex("(\\d+\\.?\\d*)\\s*(lbs?|pounds?)\\s*to\\s*kg").find(s)
        if (lb != null) return "%.2f kg".format(lb.groupValues[1].toDouble() * 0.453592)

        val mft = Regex("(\\d+\\.?\\d*)\\s*m\\s*to\\s*(ft|feet)").find(s)
        if (mft != null) return "%.2f feet".format(mft.groupValues[1].toDouble() * 3.28084)

        val ftm = Regex("(\\d+\\.?\\d*)\\s*(ft|feet)\\s*to\\s*m").find(s)
        if (ftm != null) return "%.2f m".format(ftm.groupValues[1].toDouble() * 0.3048)

        val num = Regex("(\\d+\\.?\\d*)").find(s)?.groupValues?.get(1)?.toDoubleOrNull()
        if (num != null && s.contains("celsius") && s.contains("fahrenheit"))
            return "%.1f F".format(num * 9.0 / 5.0 + 32)
        if (num != null && s.contains("fahrenheit") && s.contains("celsius"))
            return "%.1f C".format((num - 32) * 5.0 / 9.0)

        return null
    }

    fun close() {}
}

private class MathEval(private val expr: String) {
    private var pos = 0

    fun evaluate(): Double = parseExpr()

    private fun parseExpr(): Double {
        var result = parseTerm()
        while (pos < expr.length && (expr[pos] == '+' || expr[pos] == '-')) {
            val op = expr[pos++]
            result = if (op == '+') result + parseTerm() else result - parseTerm()
        }
        return result
    }

    private fun parseTerm(): Double {
        var result = parseFactor()
        while (pos < expr.length && (expr[pos] == '*' || expr[pos] == '/')) {
            val op = expr[pos++]
            result = if (op == '*') result * parseFactor() else result / parseFactor()
        }
        return result
    }

    private fun parseFactor(): Double {
        if (pos < expr.length && expr[pos] == '(') {
            pos++
            val result = parseExpr()
            if (pos < expr.length && expr[pos] == ')') pos++
            return result
        }
        val start = pos
        if (pos < expr.length && (expr[pos] == '-' || expr[pos] == '+')) pos++
        while (pos < expr.length && (expr[pos].isDigit() || expr[pos] == '.')) pos++
        return expr.substring(start, pos).toDouble()
    }
}
