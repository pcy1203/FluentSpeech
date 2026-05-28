package com.chanyoungpark.fluentspeech

import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ReportActivity : ComponentActivity() {

    // ── Views ──────────────────────────────────────────────────────
    private lateinit var tvDuration:     TextView
    private lateinit var tvAvgTension:   TextView
    private lateinit var tvTotalEvents:  TextView
    private lateinit var tvFeedback:     TextView
    private lateinit var tensionGraph:   TensionGraphView
    private lateinit var btnClose:       MaterialButton

    // Disfluency bars
    private lateinit var barProlongation:  ProgressBar
    private lateinit var barBlock:         ProgressBar
    private lateinit var barSoundRep:      ProgressBar
    private lateinit var barWordRep:       ProgressBar
    private lateinit var barInterjection:  ProgressBar

    private lateinit var tvProlongation: TextView
    private lateinit var tvBlock:        TextView
    private lateinit var tvSoundRep:     TextView
    private lateinit var tvWordRep:      TextView
    private lateinit var tvInterjection: TextView

    // ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        bindViews()

        val summary       = parseSummaryFromIntent()
        val tensionHistory = parseTensionHistoryFromIntent()

        populateSummary(summary)
        populateDisfluency(summary.stutterCounts)
        tensionGraph.setData(tensionHistory)

        generateFeedback(summary)

        btnClose.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
        }
    }

    // ── View Binding ───────────────────────────────────────────────
    private fun bindViews() {
        tvDuration        = findViewById(R.id.tv_duration)
        tvAvgTension      = findViewById(R.id.tv_avg_tension)
        tvTotalEvents     = findViewById(R.id.tv_total_events)
        tvFeedback        = findViewById(R.id.tv_feedback)
        tensionGraph      = findViewById(R.id.tension_graph)
        btnClose          = findViewById(R.id.btn_close)

        barProlongation   = findViewById(R.id.bar_prolongation)
        barBlock          = findViewById(R.id.bar_block)
        barSoundRep       = findViewById(R.id.bar_sound_rep)
        barWordRep        = findViewById(R.id.bar_word_rep)
        barInterjection   = findViewById(R.id.bar_interjection)

        tvProlongation    = findViewById(R.id.tv_prolongation)
        tvBlock           = findViewById(R.id.tv_block)
        tvSoundRep        = findViewById(R.id.tv_sound_rep)
        tvWordRep         = findViewById(R.id.tv_word_rep)
        tvInterjection    = findViewById(R.id.tv_interjection)
    }

    // ── Intent 파싱 ───────────────────────────────────────────────
    private fun parseSummaryFromIntent(): SessionSummary {
        val durationSec  = intent.getIntExtra("DURATION_SEC", 0)
        val avgTension   = intent.getIntExtra("AVG_TENSION",  0)
        val peakTension  = intent.getIntExtra("PEAK_TENSION", 0)
        val countsJson   = intent.getStringExtra("STUTTER_COUNTS") ?: "{}"

        val countsMap = mutableMapOf<String, Int>()
        val json = JSONObject(countsJson)
        json.keys().forEach { key -> countsMap[key] = json.getInt(key) }

        return SessionSummary(
            durationSec      = durationSec,
            stutterCounts    = countsMap,
            avgVoiceTension  = avgTension,
            peakVoiceTension = peakTension
        )
    }

    private fun parseTensionHistoryFromIntent(): List<Int> {
        val json = intent.getStringExtra("TENSION_HISTORY") ?: "[]"
        val arr  = JSONArray(json)
        return List(arr.length()) { arr.getInt(it) }
    }

    // ── UI 채우기 ─────────────────────────────────────────────────
    private fun populateSummary(s: SessionSummary) {
        val m = s.durationSec / 60
        val sec = s.durationSec % 60
        tvDuration.text    = if (m > 0) "${m}m ${sec}s" else "${sec}s"
        tvAvgTension.text  = "${s.avgVoiceTension}%"
        tvTotalEvents.text = s.stutterCounts.values.sum().toString()
    }

    private fun populateDisfluency(counts: Map<String, Int>) {
        val max = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1

        fun setBar(bar: ProgressBar, tv: TextView, key: String) {
            val v = counts[key] ?: 0
            bar.progress = (v.toFloat() / max * 100).toInt()
            tv.text      = v.toString()
        }

        setBar(barProlongation, tvProlongation, "Prolongation")
        setBar(barBlock,        tvBlock,        "Block")
        setBar(barSoundRep,     tvSoundRep,     "Sound Repetition")
        setBar(barWordRep,      tvWordRep,      "Word Repetition")
        setBar(barInterjection, tvInterjection, "Interjection")
    }

    // ── Feedback 생성 (코루틴) ────────────────────────────────────
    private fun generateFeedback(summary: SessionSummary) {
        tvFeedback.text = "Generating feedback..."
        lifecycleScope.launch {
            val feedback = FeedbackGenerator.generate(summary)
            tvFeedback.text = feedback
        }
    }
}