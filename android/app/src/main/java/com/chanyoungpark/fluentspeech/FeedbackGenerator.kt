package com.chanyoungpark.fluentspeech

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// ── 세션 요약 데이터 ───────────────────────────────────────────────
data class SessionSummary(
    val durationSec:      Int,
    val stutterCounts:    Map<String, Int>,   // {"Block": 3, "Prolongation": 2, ...}
    val avgVoiceTension:  Int,                // 0~100
    val peakVoiceTension: Int                 // 0~100
)

// ── FeedbackGenerator ─────────────────────────────────────────────
object FeedbackGenerator {

    // ══════════════════════════════════════════════════════════════
    //  ★ 설정: true → OpenAI GPT 호출 / false → Rule-based
    // ══════════════════════════════════════════════════════════════
    private const val USE_AI = false

    // OpenAI API Key (USE_AI = true 일 때만 사용)
    private const val OPENAI_API_KEY = "YOUR_OPENAI_API_KEY"
    private const val OPENAI_MODEL   = "gpt-4o-mini"

    // ── 외부 호출 진입점 ──────────────────────────────────────────
    suspend fun generate(summary: SessionSummary): String {
        return if (USE_AI) {
            try {
                generateWithAI(summary)
            } catch (e: Exception) {
                Log.e("FeedbackGenerator", "AI failed, fallback to rule-based", e)
                generateRuleBased(summary)
            }
        } else {
            generateRuleBased(summary)
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  OpenAI GPT 호출
    // ══════════════════════════════════════════════════════════════
    private suspend fun generateWithAI(summary: SessionSummary): String =
        withContext(Dispatchers.IO) {

            val prompt = buildPrompt(summary)

            val requestBody = JSONObject().apply {
                put("model", OPENAI_MODEL)
                put("max_tokens", 300)
                put("temperature", 0.7)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content",
                            "You are a supportive speech fluency coach. " +
                                    "Provide concise, encouraging, and actionable feedback " +
                                    "based on a stuttering detection session report. " +
                                    "Keep your response under 100 words. " +
                                    "Be empathetic and constructive, never discouraging."
                        )
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }.toString()

            val url  = URL("https://api.openai.com/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $OPENAI_API_KEY")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout    = 15000
            }

            OutputStreamWriter(conn.outputStream).use { it.write(requestBody) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                throw Exception("OpenAI API error: $responseCode")
            }

            val response = conn.inputStream.bufferedReader().readText()
            val json     = JSONObject(response)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }

    // ── 프롬프트 구성 ─────────────────────────────────────────────
    private fun buildPrompt(s: SessionSummary): String {
        val duration = formatDuration(s.durationSec)
        val totalEvents = s.stutterCounts.values.sum()

        val countLines = s.stutterCounts
            .filter { it.value > 0 }
            .entries
            .sortedByDescending { it.value }
            .joinToString("\n") { "  - ${it.key}: ${it.value} time(s)" }
            .ifEmpty { "  - None detected" }

        val dominant = s.stutterCounts
            .filter { it.value > 0 }
            .maxByOrNull { it.value }?.key ?: "none"

        return """
            Here is the summary of a speech fluency session:

            - Session Duration     : $duration
            - Total Disfluency Events: $totalEvents
            - Disfluency Breakdown :
            $countLines
            - Average Voice Tension: ${s.avgVoiceTension}%
            - Peak Voice Tension   : ${s.peakVoiceTension}%
            - Most Frequent Type   : $dominant

            Please provide personalized, encouraging feedback for this speaker.
            Focus on what they did well and give 1-2 specific actionable tips
            based on the most frequent disfluency type.
        """.trimIndent()
    }

    // ══════════════════════════════════════════════════════════════
    //  Rule-based 피드백
    // ══════════════════════════════════════════════════════════════
    private fun generateRuleBased(s: SessionSummary): String {
        val totalEvents = s.stutterCounts.values.sum()
        val dominant    = s.stutterCounts
            .filter { it.value > 0 }
            .maxByOrNull { it.value }?.key

        val sb = StringBuilder()

        // ── 1. 전반적 평가 ──
        sb.appendLine(when {
            totalEvents == 0         -> "Great session! No disfluency events were detected. Keep up the excellent work."
            totalEvents <= 3         -> "Good session. Only a few disfluency events were detected — you're doing well."
            totalEvents <= 8         -> "Decent session. Some disfluency events were noted. With practice, you'll improve steadily."
            else                     -> "This was a challenging session. Don't be discouraged — awareness is the first step to improvement."
        })

        // ── 2. 긴장도 평가 ──
        sb.appendLine(when {
            s.avgVoiceTension < 30   -> "Your overall voice tension was low, which is a positive sign of relaxed speech."
            s.avgVoiceTension < 60   -> "Your voice tension was moderate. Try to stay relaxed and breathe steadily."
            else                     -> "High voice tension was detected. Remember to take slow, deep breaths before speaking."
        })

        // ── 3. 유형별 팁 ──
        if (dominant != null) {
            sb.appendLine(tipForType(dominant))
        }

        return sb.toString().trim()
    }

    private fun tipForType(type: String): String = when (type) {
        "Block"            -> "Tip: For blocks, try the 'easy onset' technique — start words gently without tension, letting air flow smoothly."
        "Prolongation"     -> "Tip: For prolongations, practice pausing intentionally before speaking rather than stretching sounds."
        "Sound Repetition" -> "Tip: For sound repetitions, slow down your speech rate and focus on smooth syllable transitions."
        "Word Repetition"  -> "Tip: For word repetitions, try pausing briefly to gather your thoughts before continuing."
        "Interjection"     -> "Tip: For interjections (um/uh), practice pausing silently instead of filling gaps with filler words."
        else               -> "Tip: Keep practicing regularly and consider working with a speech therapist for personalized guidance."
    }

    // ── 시간 포맷 ──────────────────────────────────────────────────
    private fun formatDuration(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}