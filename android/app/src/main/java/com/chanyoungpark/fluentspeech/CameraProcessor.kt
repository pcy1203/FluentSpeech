package com.chanyoungpark.fluentspeech

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.sqrt

class CameraProcessor(
    private val context:        Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView:    PreviewView,
    private val onTensionUpdate: (faceTension: Int) -> Unit,
    private val onError:         (String) -> Unit
) {
    companion object {
        private const val TAG = "CameraProcessor"

        // MediaPipe FaceMesh 모델 파일명 (assets 에 넣어야 함)
        private const val MODEL_FILE = "face_landmarker.task"

        // MAR/EAR 계산에 사용할 랜드마크 인덱스 (MediaPipe 478점 기준)
        // ── 입술 (MAR) ──
        private val LIPS_UPPER  = intArrayOf(13, 312, 311, 310, 415, 308)
        private val LIPS_LOWER  = intArrayOf(14, 87,  88,  178, 87,  14)
        private val LIPS_CORNER = intArrayOf(61, 291)

        // ── 눈 (EAR) — 왼쪽 / 오른쪽 ──
        private val LEFT_EYE  = intArrayOf(362, 385, 387, 263, 373, 380)
        private val RIGHT_EYE = intArrayOf(33,  160, 158, 133, 153, 144)

        // 정규화 기준값 (경험적)
        private const val MAR_THRESHOLD = 0.4f   // 이상이면 입 긴장
        private const val EAR_THRESHOLD = 0.2f   // 이하이면 눈 긴장
    }

    private var faceLandmarker: FaceLandmarker? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // ── 초기화 ─────────────────────────────────────────────────────
    init {
        setupFaceLandmarker()
    }

    private fun setupFaceLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setResultListener { result, _ -> processLandmarks(result) }
                .setErrorListener { error -> onError("FaceLandmarker error: ${error.message}") }
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            onError("FaceLandmarker init failed: ${e.message}")
            Log.e(TAG, "FaceLandmarker init failed", e)
        }
    }

    // ── CameraX 시작 ───────────────────────────────────────────────
    fun start() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCamera(cameraProvider)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

        // 전면 카메라 선택
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (e: Exception) {
            onError("Camera bind failed: ${e.message}")
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    // ── 프레임 처리 ────────────────────────────────────────────────
    private fun processImageProxy(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        imageProxy.close()

        val mpImage = BitmapImageBuilder(bitmap).build()
        val timestamp = System.currentTimeMillis()

        try {
            faceLandmarker?.detectAsync(mpImage, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "detectAsync failed", e)
        }
    }

    // ── 랜드마크 → MAR/EAR → Tension ──────────────────────────────
    private fun processLandmarks(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isEmpty()) return

        val landmarks = result.faceLandmarks()[0]

        fun dist(i: Int, j: Int): Float {
            val a = landmarks[i]; val b = landmarks[j]
            val dx = a.x() - b.x(); val dy = a.y() - b.y()
            return sqrt(dx * dx + dy * dy)
        }

        // ── MAR (Mouth Aspect Ratio) ──
        // 세로 거리 / 가로 거리
        val mouthVertical   = dist(13, 14)
        val mouthHorizontal = dist(61, 291)
        val mar = if (mouthHorizontal > 0f) mouthVertical / mouthHorizontal else 0f

        // ── EAR (Eye Aspect Ratio) — 양쪽 평균 ──
        fun ear(eye: IntArray): Float {
            val v1 = dist(eye[1], eye[5])
            val v2 = dist(eye[2], eye[4])
            val h  = dist(eye[0], eye[3])
            return if (h > 0f) (v1 + v2) / (2f * h) else 0f
        }
        val earLeft  = ear(LEFT_EYE)
        val earRight = ear(RIGHT_EYE)
        val earAvg   = (earLeft + earRight) / 2f

        // ── Tension 계산 (0~100) ──
        // MAR 높을수록 입 긴장, EAR 낮을수록 눈 긴장
        val marTension = ((mar / MAR_THRESHOLD).coerceIn(0f, 1f) * 100).toInt()
        val earTension = ((1f - (earAvg / EAR_THRESHOLD).coerceIn(0f, 1f)) * 100).toInt()
        val faceTension = ((marTension * 0.5f) + (earTension * 0.5f)).toInt()

        onTensionUpdate(faceTension)
    }

    // ── 해제 ───────────────────────────────────────────────────────
    fun stop() {
        cameraExecutor.shutdown()
        faceLandmarker?.close()
        faceLandmarker = null
    }
}