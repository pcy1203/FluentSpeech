package com.chanyoungpark.fluentspeech

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.chanyoungpark.fluentspeech.databinding.ActivityMainBinding

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupModeSelection()
        setupSensorSelection()
        setupStartButton()
    }

    // ── Mode: Real-Time vs Report Only (radio-style: 둘 중 하나만 선택) ──
    private fun setupModeSelection() {
        binding.checkboxRealtime.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) binding.checkboxReport.isChecked = false
            updateStartButton()
        }
        binding.checkboxReport.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) binding.checkboxRealtime.isChecked = false
            updateStartButton()
        }
    }

    // ── Sensor: Voice+Camera vs Voice Only (radio-style) ──
    private fun setupSensorSelection() {
        binding.checkboxVoiceCamera.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) binding.checkboxVoiceOnly.isChecked = false
            updateStartButton()
        }
        binding.checkboxVoiceOnly.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) binding.checkboxVoiceCamera.isChecked = false
            updateStartButton()
        }
    }

    private fun updateStartButton() {
        val modeSelected   = binding.checkboxRealtime.isChecked || binding.checkboxReport.isChecked
        val sensorSelected = binding.checkboxVoiceCamera.isChecked || binding.checkboxVoiceOnly.isChecked
        binding.btnStart.isEnabled = modeSelected && sensorSelected
        binding.btnStart.alpha = if (binding.btnStart.isEnabled) 1.0f else 0.4f
    }

    private fun setupStartButton() {
        binding.btnStart.isEnabled = false
        binding.btnStart.alpha = 0.4f

        binding.btnStart.setOnClickListener {
            val isRealtime    = binding.checkboxRealtime.isChecked
            val useCamera     = binding.checkboxVoiceCamera.isChecked

             val intent = Intent(this, SessionActivity::class.java).apply {
                 putExtra("IS_REALTIME", isRealtime)
                 putExtra("USE_CAMERA",  useCamera)
             }
             startActivity(intent)

            val mode   = if (isRealtime) "Real-Time" else "Report Only"
            val sensor = if (useCamera)  "Voice + Camera" else "Voice Only"
            Toast.makeText(this, "$mode | $sensor", Toast.LENGTH_SHORT).show()
        }
    }
}