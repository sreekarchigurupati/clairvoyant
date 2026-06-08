package com.clairvoyant.glasses.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.clairvoyant.glasses.R
import com.clairvoyant.glasses.databinding.ActivityScannerBinding
import com.clairvoyant.glasses.network.Pairing
import com.clairvoyant.glasses.session.SessionActivity
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * QR code scanner activity using CameraX + ML Kit.
 * Scans for Claude Code remote session QR codes and launches the session view.
 */
class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var hasScanned = false
    private var wifiSaved = false
    private var wifiOnlyMode = false

    companion object {
        private const val TAG = "ClairvoyantScanner"
        private const val CAMERA_PERMISSION_CODE = 1001

        /** When true, only capture a Wi-Fi QR, save creds, and finish with RESULT_OK. */
        const val EXTRA_WIFI_ONLY = "wifi_only"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()

        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiOnlyMode = intent.getBooleanExtra(EXTRA_WIFI_ONLY, false)
        if (wifiOnlyMode) {
            binding.scannerStatus.text = "Scan your phone's Wi-Fi hotspot QR"
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

            // Image analysis for QR scanning
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (hasScanned) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )
                            val scanner = BarcodeScanning.getClient()

                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    for (barcode in barcodes) {
                                        if (barcode.valueType == Barcode.TYPE_WIFI) {
                                            barcode.wifi?.let { handleScannedWifi(it) }
                                            break
                                        }
                                        if (!wifiOnlyMode &&
                                            (barcode.valueType == Barcode.TYPE_URL ||
                                                barcode.valueType == Barcode.TYPE_TEXT)) {
                                            val payload = barcode.url?.url ?: barcode.rawValue ?: continue
                                            handlePairingPayload(payload)
                                            break
                                        }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Barcode scan failed", e)
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            // Use back camera (Rokid glasses front-facing camera)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Handle a scanned Wi-Fi QR code (standard WIFI:S:..;T:..;P:..;; format, decoded
     * natively by ML Kit). Saves the hotspot credentials so SessionActivity can join
     * the network without any on-glasses typing. Scanning continues for the session QR.
     */
    private fun handleScannedWifi(wifi: Barcode.WiFi) {
        if (wifiSaved) return
        val ssid = wifi.ssid ?: return
        wifiSaved = true

        getSharedPreferences("clairvoyant", MODE_PRIVATE)
            .edit()
            .putString("hotspot_ssid", ssid)
            .putString("hotspot_password", wifi.password ?: "")
            .apply()
        Log.i(TAG, "Hotspot credentials saved from Wi-Fi QR")

        if (wifiOnlyMode) {
            // Launched just to capture the hotspot QR — hand creds back and return.
            hasScanned = true
            runOnUiThread {
                binding.scannerStatus.text = "Hotspot \"$ssid\" saved."
                binding.scannerStatus.setTextColor(getColor(R.color.approve_green))
                setResult(RESULT_OK)
                finish()
            }
        } else {
            runOnUiThread {
                binding.scannerStatus.text = "Hotspot \"$ssid\" saved. Now scan the session QR."
                binding.scannerStatus.setTextColor(getColor(R.color.approve_green))
            }
        }
    }

    private fun handlePairingPayload(raw: String) {
        if (hasScanned) return
        val pairing = Pairing.parse(raw)
        if (pairing != null) {
            hasScanned = true
            Log.i(TAG, "Valid pairing QR scanned: ${pairing.host}:${pairing.port}")

            runOnUiThread {
                binding.scannerStatus.text = "Paired! Connecting…"
                binding.scannerStatus.setTextColor(getColor(R.color.approve_green))
            }

            getSharedPreferences("clairvoyant", MODE_PRIVATE).edit()
                .putString("relay_host", pairing.host)
                .putInt("relay_port", pairing.port)
                .putString("relay_token", pairing.token)
                .putLong("last_pair_time", System.currentTimeMillis())
                .apply()

            val intent = Intent(this, SessionActivity::class.java).apply {
                putExtra(SessionActivity.EXTRA_HOST, pairing.host)
                putExtra(SessionActivity.EXTRA_PORT, pairing.port)
                putExtra(SessionActivity.EXTRA_TOKEN, pairing.token)
            }
            startActivity(intent)
            finish()
        } else {
            runOnUiThread {
                binding.scannerStatus.text = "Not a Clairvoyant pairing QR. Keep scanning…"
                binding.scannerStatus.setTextColor(getColor(R.color.warning_amber))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
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
