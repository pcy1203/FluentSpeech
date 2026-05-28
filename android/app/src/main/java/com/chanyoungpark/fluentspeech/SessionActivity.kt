package com.chanyoungpark.fluentspeech

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat

class SessionActivity : ComponentActivity() {

    // ── UI Views ──────────────────────────────────────────────────
    private lateinit var tvTimer: TextView
    private lateinit var btnSession: Button
    private lateinit var cardFeedback: CardView
    private lateinit var tvFeedbackMessage: TextView
    private lateinit var progressVoiceTension: ProgressBar
    private lateinit var tvVoiceTensionPct: TextView
    private lateinit var progressFaceTension: ProgressBar
    private lateinit var tvFaceTensionPct: TextView
    private lateinit var layoutFaceTension: View
    private lateinit var cameraContainer: View

    // ── Session State ─────────────────────────────────────────────
    private var isRunning = false
    private var elapsedSeconds = 0
    private val timerHandler = Handler(Looper.getMainLooper())

    // ── Config (passed from MainActivity) ─────────────────────────
    private var isRealtime = true
    private var useCamera  = false

    // ── AudioProcessor ────────────────────────────────────────────
    private lateinit var audioProcessor: AudioProcessor

    // ── CameraProcessor ───────────────────────────────────────────
    private var cameraProcessor: CameraProcessor? = null

    // ── 권한 요청 런처 ─────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted  = results[Manifest.permission.RECORD_AUDIO] == true
        val cameraGranted = results[Manifest.permission.CAMERA] == true

        when {
            !audioGranted -> {
                Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
                btnSession.isEnabled = false
            }
            useCamera && !cameraGranted -> {
                Toast.makeText(this, "Camera permission is required for Voice+Camera mode.", Toast.LENGTH_LONG).show()
                btnSession.isEnabled = false
            }
            else -> startSession()
        }
    }

    // ── Timer Runnable ────────────────────────────────────────────
    private val timerRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++
            val min = elapsedSeconds / 60
            val sec = elapsedSeconds % 60
            tvTimer.text = String.format("%02d:%02d", min, sec)
            timerHandler.postDelayed(this, 1000)
        }
    }

    // ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session)

        // Intent extras
        isRealtime = intent.getBooleanExtra("IS_REALTIME", true)
        useCamera  = intent.getBooleanExtra("USE_CAMERA",  false)

        bindViews()
        setupUI()
        setupAudioProcessor()
    }

    // ── View Binding (findViewById) ───────────────────────────────
    private fun bindViews() {
        tvTimer              = findViewById(R.id.tv_timer)
        btnSession           = findViewById(R.id.btn_session)
        cardFeedback         = findViewById(R.id.card_feedback)
        tvFeedbackMessage    = findViewById(R.id.tv_feedback_message)
        progressVoiceTension = findViewById(R.id.progress_voice_tension)
        tvVoiceTensionPct    = findViewById(R.id.tv_voice_tension_pct)
        progressFaceTension  = findViewById(R.id.progress_face_tension)
        tvFaceTensionPct     = findViewById(R.id.tv_face_tension_pct)
        layoutFaceTension    = findViewById(R.id.layout_face_tension)
        cameraContainer      = findViewById(R.id.camera_container)
    }

    // ── UI 초기 설정 ──────────────────────────────────────────────
    private fun setupUI() {
        // 카메라 모드 여부에 따라 visibility 설정
        if (useCamera) {
            cameraContainer.visibility   = View.VISIBLE
            layoutFaceTension.visibility = View.VISIBLE
        } else {
            cameraContainer.visibility   = View.GONE
            layoutFaceTension.visibility = View.GONE
        }

        // Report Only 모드: 피드백 카드 완전 숨김
        if (!isRealtime) {
            cardFeedback.visibility = View.GONE
        } else {
            cardFeedback.alpha = 0f
        }

        btnSession.setOnClickListener {
            if (isRunning) stopSession() else checkPermissionsAndStart()
        }
    }

    // ── AudioProcessor 초기화 ─────────────────────────────────────
    private fun setupAudioProcessor() {
        audioProcessor = AudioProcessor(
            context      = this,
            modelPath    = "stutternet_int8.tflite",
            onResult     = { result -> runOnUiThread { handleInferenceResult(result) } },
            onError      = { err    -> runOnUiThread { showFeedback("Error: $err", isPositive = false) } }
        )
    }

    // ── 권한 체크 후 세션 시작 ────────────────────────────────────
    private fun checkPermissionsAndStart() {
        val neededPermissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (useCamera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA)
        }

        if (neededPermissions.isEmpty()) {
            // 이미 권한 있음 → 바로 시작
            startSession()
        } else {
            // 권한 요청
            permissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    // ── Session Control ───────────────────────────────────────────
    private fun startSession() {
        isRunning       = true
        elapsedSeconds  = 0
        btnSession.text = "END"

        audioProcessor.resetStats()
        timerHandler.post(timerRunnable)
        audioProcessor.start()

        // 카메라 모드면 CameraProcessor 시작
        if (useCamera) {
            val previewView = findViewById<androidx.camera.view.PreviewView>(R.id.camera_preview)
            cameraProcessor = CameraProcessor(
                context         = this,
                lifecycleOwner  = this,
                previewView     = previewView,
                onTensionUpdate = { tension -> updateFaceTension(tension) },
                onError         = { err -> Log.e("SessionActivity", err) }
            )
            cameraProcessor?.start()
        }

        if (isRealtime) showFeedback("Session started. Start speaking!", isPositive = true)
    }

    private fun stopSession() {
        isRunning = false
        btnSession.text = "START"

        timerHandler.removeCallbacks(timerRunnable)
        audioProcessor.stop()
        cameraProcessor?.stop()
        cameraProcessor = null

        // 요약 데이터 수집
        val summary        = audioProcessor.getSummary(elapsedSeconds)
        val tensionHistory = audioProcessor.getTensionHistory()

        // counts → JSON 문자열
        val countsJson = org.json.JSONObject().apply {
            summary.stutterCounts.forEach { (k, v) -> put(k, v) }
        }.toString()

        // tensionHistory → JSON 문자열
        val historyJson = org.json.JSONArray().apply {
            tensionHistory.forEach { put(it) }
        }.toString()

        val intent = Intent(this, ReportActivity::class.java).apply {
            putExtra("DURATION_SEC",    elapsedSeconds)
            putExtra("AVG_TENSION",     summary.avgVoiceTension)
            putExtra("PEAK_TENSION",    summary.peakVoiceTension)
            putExtra("STUTTER_COUNTS",  countsJson)
            putExtra("TENSION_HISTORY", historyJson)
        }
        startActivity(intent)
    }

    // ── Inference Result Handler ──────────────────────────────────
    private fun handleInferenceResult(result: InferenceResult) {
        // Voice Tension 업데이트 (0~100)
        val voiceTension = result.voiceTension
        progressVoiceTension.progress = voiceTension
        tvVoiceTensionPct.text = "$voiceTension%"

        // isRealtime 모드일 때만 피드백 메시지 표시
        if (!isRealtime) return

        if (result.stutterDetected) {
            val label = result.dominantStutterType ?: "Disfluency"
            showFeedback("⚠ $label detected. Take a breath.", isPositive = false)
        } else {
            showFeedback("✓ Good fluency! Keep going.", isPositive = true)
        }
    }

    // ── Face Tension 업데이트 (카메라 모드에서 외부 호출용) ─────────
    fun updateFaceTension(tension: Int) {
        runOnUiThread {
            progressFaceTension.progress = tension
            tvFaceTensionPct.text = "$tension%"
        }
    }

    // ── Feedback Message Fade In/Out ──────────────────────────────
    private fun showFeedback(message: String, isPositive: Boolean) {
        tvFeedbackMessage.text = message
        tvFeedbackMessage.setTextColor(
            if (isPositive) 0xFFAAFFCC.toInt()   // 초록빛
            else            0xFFFF6B6B.toInt()    // 붉은빛
        )

        // Fade in
        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration  = 400
            fillAfter = true
        }
        cardFeedback.startAnimation(fadeIn)
        cardFeedback.alpha = 1f

        // 3초 후 Fade out
        timerHandler.removeCallbacksAndMessages("FADE_OUT")
        timerHandler.postDelayed({
            val fadeOut = AlphaAnimation(1f, 0f).apply {
                duration  = 600
                fillAfter = true
            }
            cardFeedback.startAnimation(fadeOut)
            cardFeedback.alpha = 0f
        }, 3000)
    }

    // ─────────────────────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacksAndMessages(null)
        if (::audioProcessor.isInitialized) audioProcessor.stop()
        cameraProcessor?.stop()
    }
}