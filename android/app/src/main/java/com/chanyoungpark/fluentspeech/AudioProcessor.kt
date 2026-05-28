package com.chanyoungpark.fluentspeech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.java.TfLite
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.PI
import kotlin.math.sqrt

// ── Inference 결과 데이터 클래스 ──────────────────────────────────
data class InferenceResult(
    val stutterDetected:    Boolean,
    val dominantStutterType: String?,   // "Block", "Prolongation", etc.
    val probabilities:      FloatArray, // [Prolongation, Block, SoundRep, WordRep, Interjection]
    val voiceTension:       Int         // 0~100
)

// ── AudioProcessor ────────────────────────────────────────────────
class AudioProcessor(
    private val context:   Context,
    private val modelPath: String,
    private val onResult:  (InferenceResult) -> Unit,
    private val onError:   (String) -> Unit
) {

    companion object {
        // 오디오 설정
        private const val SAMPLE_RATE    = 16000
        private const val WINDOW_SEC     = 3
        private const val WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_SEC  // 48000
        private const val HOP_SAMPLES    = SAMPLE_RATE               // 1초 hop (sliding window)

        // MFCC 설정 (학습과 동일하게)
        private const val N_MFCC      = 20
        private const val N_FFT       = 512
        private const val HOP_LENGTH  = 160
        private const val WIN_LENGTH  = 400
        private const val MAX_FRAMES  = 300

        // 모델 출력 레이블
        private val STUTTER_LABELS = arrayOf(
            "Prolongation", "Block", "Sound Repetition",
            "Word Repetition", "Interjection"
        )
        private const val STUTTER_THRESHOLD = 0.5f
    }

    // ── 내부 상태 ──────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private var interpreter: InterpreterApi? = null
    private var isRecording = false

    private val audioBuffer  = ShortArray(WINDOW_SAMPLES)
    private val slidingBuffer = FloatArray(WINDOW_SAMPLES)
    private var slidingWritePos = 0
    private var bufferedSamples = 0

    // ── 초기화 ─────────────────────────────────────────────────────
    init {
        loadModel()
    }

    private fun loadModel() {
        // GMS TFLite 초기화 (비동기)
        TfLite.initialize(
            context,
            TfLiteInitializationOptions.builder()
                .setEnableGpuDelegateSupport(false)
                .build()
        ).addOnSuccessListener {
            try {
                val afd         = context.assets.openFd(modelPath)
                val inputStream = FileInputStream(afd.fileDescriptor)
                val channel     = inputStream.channel
                val modelBuffer = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
                val options = InterpreterApi.Options()
                    .setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
                interpreter = InterpreterApi.create(modelBuffer, options)
            } catch (e: Exception) {
                onError("Model load failed: ${e.message}")
            }
        }.addOnFailureListener { e ->
            onError("TFLite init failed: ${e.message}")
        }
    }

    // ── 시작 / 정지 ────────────────────────────────────────────────
    fun start() {
        if (isRecording) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, HOP_SAMPLES * 2)
            )
        } catch (e: SecurityException) {
            onError("Microphone permission denied: ${e.message}")
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        Thread { recordingLoop() }.start()
    }

    fun stop() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    // ── 녹음 루프 (별도 스레드) ────────────────────────────────────
    private fun recordingLoop() {
        val hopBuffer = ShortArray(HOP_SAMPLES)

        while (isRecording) {
            val read = audioRecord?.read(hopBuffer, 0, HOP_SAMPLES) ?: break
            if (read <= 0) continue

            // sliding buffer 업데이트
            for (i in 0 until read) {
                slidingBuffer[slidingWritePos] = hopBuffer[i] / 32768f
                slidingWritePos = (slidingWritePos + 1) % WINDOW_SAMPLES
            }
            bufferedSamples += read

            // 3초 이상 쌓이면 추론
            if (bufferedSamples >= WINDOW_SAMPLES) {
                val window = getOrderedWindow()
                runInference(window)
            }
        }
    }

    // Ring buffer → 순서 정렬된 배열
    private fun getOrderedWindow(): FloatArray {
        val result = FloatArray(WINDOW_SAMPLES)
        for (i in 0 until WINDOW_SAMPLES) {
            result[i] = slidingBuffer[(slidingWritePos + i) % WINDOW_SAMPLES]
        }
        return result
    }

    // ── TFLite 추론 ────────────────────────────────────────────────
    private fun runInference(samples: FloatArray) {
        val interpreter = interpreter ?: return

        // MFCC 추출
        val mfcc = extractMFCC(samples) ?: return   // (MAX_FRAMES, N_MFCC)

        // 입력 텐서: (1, MAX_FRAMES, N_MFCC)
        val inputBuffer = ByteBuffer
            .allocateDirect(1 * MAX_FRAMES * N_MFCC * 4)
            .order(ByteOrder.nativeOrder())

        for (t in 0 until MAX_FRAMES) {
            for (c in 0 until N_MFCC) {
                inputBuffer.putFloat(mfcc[t][c])
            }
        }
        inputBuffer.rewind()

        // 출력 텐서: (1, 5)
        val outputBuffer = Array(1) { FloatArray(5) }
        interpreter.run(inputBuffer, outputBuffer)

        // Sigmoid
        val probs = FloatArray(5) { sigmoid(outputBuffer[0][it]) }

        // 결과 분석
        val stutterDetected = probs.any { it > STUTTER_THRESHOLD }
        val dominantIdx     = probs.indices.maxByOrNull { probs[it] }
        val dominantType    = if (stutterDetected && dominantIdx != null)
            STUTTER_LABELS[dominantIdx] else null

        // Voice tension: 최대 확률 기반 0~100 스케일
        val voiceTension = (probs.max() * 100).toInt().coerceIn(0, 100)

        onResult(InferenceResult(stutterDetected, dominantType, probs, voiceTension))
    }

    // ── MFCC 추출 (Java/Kotlin 구현) ──────────────────────────────
    private fun extractMFCC(samples: FloatArray): Array<FloatArray>? {
        return try {
            val frames    = mutableListOf<FloatArray>()
            val hammingWin = FloatArray(WIN_LENGTH) { i ->
                (0.54 - 0.46 * cos(2.0 * PI * i / (WIN_LENGTH - 1))).toFloat()
            }
            val melFilters = buildMelFilterBank(
                numFilters = 40, nFft = N_FFT, sr = SAMPLE_RATE
            )

            var start = 0
            while (start + WIN_LENGTH <= samples.size && frames.size < MAX_FRAMES) {
                // 윈도우 추출 + Hamming
                val frame = FloatArray(WIN_LENGTH) { i ->
                    samples[start + i] * hammingWin[i]
                }

                // FFT magnitude spectrum
                val paddedSize = N_FFT
                val fftInput   = FloatArray(paddedSize)
                frame.copyInto(fftInput, 0, 0, minOf(WIN_LENGTH, paddedSize))
                val mag = fftMagnitude(fftInput)

                // Power spectrum
                val power = FloatArray(N_FFT / 2 + 1) { i -> mag[i] * mag[i] }

                // Mel filterbank energies
                val melEnergies = FloatArray(40) { m ->
                    var energy = 0f
                    for (k in power.indices) energy += melFilters[m][k] * power[k]
                    energy
                }

                // Log Mel → DCT → MFCC
                val logMel  = FloatArray(40) { ln(melEnergies[it].coerceAtLeast(1e-10f)) }
                val mfccVec = dct(logMel, N_MFCC)

                frames.add(mfccVec)
                start += HOP_LENGTH
            }

            // 패딩 or 트리밍 → (MAX_FRAMES, N_MFCC)
            val result = Array(MAX_FRAMES) { FloatArray(N_MFCC) }
            for (i in 0 until minOf(frames.size, MAX_FRAMES)) {
                result[i] = frames[i]
            }

            // 프레임별 정규화
            for (c in 0 until N_MFCC) {
                val col  = FloatArray(MAX_FRAMES) { result[it][c] }
                val mean = col.average().toFloat()
                val std  = sqrt(col.map { (it - mean) * (it - mean) }.average()).toFloat()
                for (t in 0 until MAX_FRAMES) {
                    result[t][c] = (result[t][c] - mean) / (std + 1e-8f)
                }
            }
            result
        } catch (e: Exception) {
            onError("MFCC extraction failed: ${e.message}")
            null
        }
    }

    // ── Mel Filterbank ────────────────────────────────────────────
    private fun buildMelFilterBank(numFilters: Int, nFft: Int, sr: Int): Array<FloatArray> {
        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

        val lowMel  = hzToMel(0.0)
        val highMel = hzToMel(sr / 2.0)
        val melPts  = DoubleArray(numFilters + 2) { i ->
            melToHz(lowMel + i * (highMel - lowMel) / (numFilters + 1))
        }
        val bins = IntArray(numFilters + 2) { i ->
            ((melPts[i] / (sr / 2.0)) * (nFft / 2)).toInt().coerceIn(0, nFft / 2)
        }
        val filters = Array(numFilters) { FloatArray(nFft / 2 + 1) }
        for (m in 1..numFilters) {
            for (k in bins[m - 1]..bins[m + 1]) {
                filters[m - 1][k] = when {
                    k <= bins[m] -> (k - bins[m - 1]).toFloat() / (bins[m] - bins[m - 1] + 1)
                    else         -> (bins[m + 1] - k).toFloat() / (bins[m + 1] - bins[m] + 1)
                }
            }
        }
        return filters
    }

    // ── Cooley-Tukey FFT (magnitude) ─────────────────────────────
    private fun fftMagnitude(x: FloatArray): FloatArray {
        val n    = x.size
        val real = DoubleArray(n) { x[it].toDouble() }
        val imag = DoubleArray(n)
        fftInPlace(real, imag, n)
        return FloatArray(n / 2 + 1) { i ->
            sqrt(real[i] * real[i] + imag[i] * imag[i]).toFloat()
        }
    }

    private fun fftInPlace(real: DoubleArray, imag: DoubleArray, n: Int) {
        if (n <= 1) return
        val half = n / 2
        val evenR = DoubleArray(half) { real[it * 2] }
        val evenI = DoubleArray(half) { imag[it * 2] }
        val oddR  = DoubleArray(half) { real[it * 2 + 1] }
        val oddI  = DoubleArray(half) { imag[it * 2 + 1] }
        fftInPlace(evenR, evenI, half)
        fftInPlace(oddR,  oddI,  half)
        for (k in 0 until half) {
            val angle = -2.0 * PI * k / n
            val wr = kotlin.math.cos(angle); val wi = kotlin.math.sin(angle)
            val tr = wr * oddR[k] - wi * oddI[k]
            val ti = wr * oddI[k] + wi * oddR[k]
            real[k]        = evenR[k] + tr; imag[k]        = evenI[k] + ti
            real[k + half] = evenR[k] - tr; imag[k + half] = evenI[k] - ti
        }
    }

    // ── DCT-II (Type-2) ───────────────────────────────────────────
    private fun dct(input: FloatArray, numCoeffs: Int): FloatArray {
        val n = input.size
        return FloatArray(numCoeffs) { k ->
            var sum = 0.0
            for (i in 0 until n) {
                sum += input[i] * cos(PI * k * (2 * i + 1) / (2.0 * n))
            }
            (sum * sqrt(2.0 / n)).toFloat()
        }
    }

    private fun sigmoid(x: Float) = 1f / (1f + kotlin.math.exp(-x))

    // ── 결과 JSON (Report용) ─────────────────────────────────────
    fun getResultJson(): String = "{}"  // TODO: 누적 결과 직렬화
}