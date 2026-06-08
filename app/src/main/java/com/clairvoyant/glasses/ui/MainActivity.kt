package com.clairvoyant.glasses.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.clairvoyant.glasses.R
import com.clairvoyant.glasses.databinding.ActivityMainBinding
import com.clairvoyant.glasses.scanner.ScannerActivity
import com.clairvoyant.glasses.session.SessionActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scanCard.setOnClickListener {
            startScannerActivity()
        }

        binding.scanCard.isFocusable = true
        binding.scanCard.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        updateStatus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            startScannerActivity()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startScannerActivity() {
        startActivity(Intent(this, ScannerActivity::class.java))
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences("clairvoyant", MODE_PRIVATE)
        val host = prefs.getString("relay_host", null)
        val port = prefs.getInt("relay_port", 0)
        val token = prefs.getString("relay_token", null)
        val pairedAt = prefs.getLong("last_pair_time", 0)

        if (host != null && token != null && port > 0) {
            binding.statusDot.setBackgroundColor(getColor(R.color.warning_amber))
            binding.statusText.text = "Paired"
            binding.sessionInfo.text = "$host:$port"
            binding.lastAction.text = "Last paired: ${formatTime(pairedAt)}"
            binding.lastAction.visibility = View.VISIBLE

            binding.statusCard.setOnClickListener {
                val intent = Intent(this, SessionActivity::class.java).apply {
                    putExtra(SessionActivity.EXTRA_HOST, host)
                    putExtra(SessionActivity.EXTRA_PORT, port)
                    putExtra(SessionActivity.EXTRA_TOKEN, token)
                }
                startActivity(intent)
            }
        } else {
            binding.statusDot.setBackgroundColor(getColor(R.color.on_surface))
            binding.statusText.text = getString(R.string.disconnected)
            binding.sessionInfo.text = "Not paired"
            binding.lastAction.visibility = View.GONE
            binding.statusCard.setOnClickListener(null)
        }
    }

    private fun formatTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            else -> "${diff / 86400_000}d ago"
        }
    }

    private fun enterImmersiveMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }
}
