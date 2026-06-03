package com.clairvoyant.glasses.session

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.clairvoyant.glasses.R
import com.clairvoyant.glasses.databinding.ActivitySessionBinding
import com.clairvoyant.glasses.network.WifiConnector
import com.clairvoyant.glasses.scanner.ScannerActivity
import com.clairvoyant.glasses.voice.VoiceCommandListener

class SessionActivity : AppCompatActivity(), VoiceCommandListener.Callback {

    private lateinit var binding: ActivitySessionBinding
    private lateinit var wifiConnector: WifiConnector
    private var voiceListener: VoiceCommandListener? = null
    private var targetSessionUrl: String? = null
    private var isAuthenticated = false
    private var sessionStarted = false
    private var awaitingWifiEnable = false

    /** Captures hotspot creds from a Wi-Fi QR, then resumes connecting. */
    private val wifiScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val ssid = prefs.getString(KEY_SSID, null)
        if (!ssid.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWithCreds(ssid, prefs.getString(KEY_PASSWORD, "") ?: "")
        } else {
            showNoCredsOptions()
        }
    }

    companion object {
        const val EXTRA_SESSION_URL = "session_url"
        private const val TAG = "ClairvoyantSession"
        private const val PREFS = "clairvoyant"
        private const val KEY_SSID = "hotspot_ssid"
        private const val KEY_PASSWORD = "hotspot_password"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()

        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sessionUrl = intent.getStringExtra(EXTRA_SESSION_URL)
        if (sessionUrl == null) {
            Toast.makeText(this, "No session URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        targetSessionUrl = sessionUrl
        wifiConnector = WifiConnector(this)
        setupWebView()
        setupPermissionButtons()
        setupVoiceCommands()
        ensureConnectivityThenStart()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.sessionWebView

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 120
            userAgentString = "Mozilla/5.0 (Linux; Android 13) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        webView.addJavascriptInterface(
            PermissionBridge(this),
            "ClairvoyantBridge"
        )

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.loadingProgress.visibility = View.GONE

                val currentUrl = url ?: return
                Log.i(TAG, "Page loaded: $currentUrl")

                if (currentUrl.startsWith("https://claude.ai/code") ||
                    currentUrl.startsWith("https://claude.ai/chat")) {
                    if (!isAuthenticated) {
                        isAuthenticated = true
                        updateConnectionStatus(true)
                    }
                    injectPermissionDetector()
                    injectGlassesStyles()
                } else if (currentUrl == "https://claude.ai/" ||
                           currentUrl == "https://claude.ai") {
                    isAuthenticated = true
                    Log.i(TAG, "Login complete, redirecting to session")
                    view?.loadUrl(targetSessionUrl!!)
                } else if (currentUrl.contains("claude.ai/login") ||
                           currentUrl.contains("accounts.google.com") ||
                           currentUrl.contains("github.com/login")) {
                    updateConnectionStatus(false, "Sign in to claude.ai")
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "WebView error: ${error?.description}")
                    updateConnectionStatus(false, "Error: ${error?.description}")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.loadingProgress.visibility = View.VISIBLE
                } else {
                    binding.loadingProgress.visibility = View.GONE
                }
            }
        }

    }

    // -- Connectivity gate --
    //
    // The Rokid Glasses have no Wi-Fi UI and the Rokid AI app provides no general
    // internet route, so before loading the WebView we make sure the process is on a
    // network with real internet — joining the phone's hotspot ourselves if needed.

    private fun ensureConnectivityThenStart() {
        if (sessionStarted) return

        if (wifiConnector.hasInternet()) {
            Log.i(TAG, "Internet already available; starting session")
            startSession()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updateConnectionStatus(false, "No internet, and Wi-Fi join needs Android 10+")
            return
        }

        if (!wifiConnector.isWifiEnabled()) {
            Log.w(TAG, "Wi-Fi radio is off; cannot join. Prompting to enable.")
            updateConnectionStatus(false, "Wi-Fi is off")
            promptEnableWifi()
            return
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val ssid = prefs.getString(KEY_SSID, null)
        val password = prefs.getString(KEY_PASSWORD, null)
        if (ssid.isNullOrEmpty()) {
            // No saved creds — capture them from a Wi-Fi QR (keyboard-free), not a dialog.
            launchWifiScan()
        } else {
            connectWithCreds(ssid, password ?: "")
        }
    }

    private fun launchWifiScan() {
        val intent = Intent(this, ScannerActivity::class.java)
        intent.putExtra(ScannerActivity.EXTRA_WIFI_ONLY, true)
        wifiScanLauncher.launch(intent)
    }

    /** Shown when a Wi-Fi scan yields no creds: re-scan, or type as a last resort. */
    private fun showNoCredsOptions() {
        AlertDialog.Builder(this)
            .setTitle("Hotspot needed")
            .setMessage(
                "The glasses have no Wi-Fi screen. Scan your phone's hotspot QR to get " +
                    "online. (You can also type it if you have a Bluetooth keyboard.)"
            )
            .setCancelable(false)
            .setPositiveButton("Scan QR") { _, _ -> launchWifiScan() }
            .setNeutralButton("Type manually") { _, _ -> promptForHotspotCredentials() }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun startSession() {
        if (sessionStarted) return
        sessionStarted = true
        Log.i(TAG, "Loading session URL")
        binding.sessionWebView.loadUrl(targetSessionUrl!!)
    }

    private fun promptEnableWifi() {
        AlertDialog.Builder(this)
            .setTitle("Turn on Wi-Fi")
            .setMessage(
                "The glasses' Wi-Fi is off, so they can't join your hotspot. Open the " +
                    "Wi-Fi panel to turn it on, then come back."
            )
            .setCancelable(false)
            .setPositiveButton("Open Wi-Fi") { _, _ ->
                awaitingWifiEnable = true
                val opened = runCatching {
                    startActivity(Intent(Settings.Panel.ACTION_WIFI)); true
                }.getOrDefault(false) || runCatching {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); true
                }.getOrDefault(false)
                if (!opened) {
                    awaitingWifiEnable = false
                    Toast.makeText(this, "No Wi-Fi settings available on this device", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    /**
     * Prefer the suggestion path (one-time approval, then silent auto-join). If the
     * system doesn't auto-join within the timeout, fall back to the specifier path,
     * which forces an immediate connect (at the cost of a per-connect approval dialog).
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectWithCreds(ssid: String, password: String) {
        wifiConnector.connectViaSuggestion(ssid, password, object : WifiConnector.Listener {
            override fun onConnecting() = showJoining(ssid)
            override fun onConnected(network: Network) = onWifiReady()
            override fun onLost() = onWifiLost()
            override fun onFailed(reason: String) {
                Log.i(TAG, "Suggestion path failed ($reason); trying specifier")
                connectViaSpecifier(ssid, password)
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectViaSpecifier(ssid: String, password: String) {
        wifiConnector.connect(ssid, password, object : WifiConnector.Listener {
            override fun onConnecting() = showJoining(ssid)
            override fun onConnected(network: Network) = onWifiReady()
            override fun onLost() = onWifiLost()
            override fun onFailed(reason: String) = runOnUiThread {
                binding.loadingProgress.visibility = View.GONE
                updateConnectionStatus(false, reason)
                Toast.makeText(this@SessionActivity, reason, Toast.LENGTH_LONG).show()
                showNoCredsOptions()
            }
        })
    }

    private fun showJoining(ssid: String) = runOnUiThread {
        binding.loadingProgress.visibility = View.VISIBLE
        updateConnectionStatus(false, "Joining \"$ssid\"…")
    }

    private fun onWifiReady() = runOnUiThread {
        Log.i(TAG, "Wi-Fi ready; starting session")
        startSession()
    }

    private fun onWifiLost() = runOnUiThread {
        updateConnectionStatus(false, "Wi-Fi connection lost")
    }

    private fun promptForHotspotCredentials() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val ssidInput = EditText(this).apply {
            hint = "Hotspot name (SSID)"
            setText(prefs.getString(KEY_SSID, ""))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val passInput = EditText(this).apply {
            hint = "Hotspot password"
            setText(prefs.getString(KEY_PASSWORD, ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(ssidInput)
        container.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("Connect to phone hotspot")
            .setMessage(
                "The glasses have no Wi-Fi screen, so enter your phone's personal " +
                    "hotspot details to get online."
            )
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Connect") { _, _ ->
                val ssid = ssidInput.text.toString().trim()
                val password = passInput.text.toString()
                if (ssid.isEmpty()) {
                    Toast.makeText(this, "SSID is required", Toast.LENGTH_SHORT).show()
                    promptForHotspotCredentials()
                    return@setPositiveButton
                }
                prefs.edit()
                    .putString(KEY_SSID, ssid)
                    .putString(KEY_PASSWORD, password)
                    .apply()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    connectWithCreds(ssid, password)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun updateConnectionStatus(connected: Boolean, message: String? = null) {
        runOnUiThread {
            if (connected) {
                binding.connectionDot.setBackgroundColor(getColor(R.color.approve_green))
                binding.connectionStatus.text = getString(R.string.connected)
            } else {
                binding.connectionDot.setBackgroundColor(getColor(R.color.warning_amber))
                binding.connectionStatus.text = message ?: getString(R.string.disconnected)
            }
        }
    }

    private fun injectPermissionDetector() {
        val js = """
            (function() {
                if (window._clairvoyantObserver) return;

                function checkForPermissions() {
                    var buttons = document.querySelectorAll('button');
                    var approveBtn = null;
                    var denyBtn = null;
                    var description = '';

                    buttons.forEach(function(btn) {
                        var text = btn.textContent.toLowerCase().trim();
                        if (text === 'allow' || text === 'approve' || text === 'yes' ||
                            text === 'allow once' || text === 'allow for this session' ||
                            text === 'accept') {
                            approveBtn = btn;
                        }
                        if (text === 'deny' || text === 'reject' || text === 'no' ||
                            text === 'decline' || text === 'cancel') {
                            denyBtn = btn;
                        }
                    });

                    if (approveBtn && denyBtn) {
                        var modal = approveBtn.closest('[role="dialog"]') ||
                                    approveBtn.closest('[class*="modal"]') ||
                                    approveBtn.closest('[class*="permission"]') ||
                                    approveBtn.parentElement?.parentElement;

                        if (modal) {
                            var allText = modal.innerText || '';
                            description = allText.substring(0, 200);
                        }

                        if (window.ClairvoyantBridge) {
                            window.ClairvoyantBridge.onPermissionDetected(description);
                        }

                        window._clairvoyantApproveBtn = approveBtn;
                        window._clairvoyantDenyBtn = denyBtn;
                    } else {
                        if (window.ClairvoyantBridge) {
                            window.ClairvoyantBridge.onPermissionCleared();
                        }
                        window._clairvoyantApproveBtn = null;
                        window._clairvoyantDenyBtn = null;
                    }
                }

                window._clairvoyantObserver = new MutationObserver(function(mutations) {
                    checkForPermissions();
                });

                window._clairvoyantObserver.observe(document.body, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['class', 'style', 'hidden']
                });

                setInterval(checkForPermissions, 2000);
                checkForPermissions();
            })();
        """.trimIndent()

        binding.sessionWebView.evaluateJavascript(js, null)
    }

    private fun injectGlassesStyles() {
        val css = """
            (function() {
                var style = document.createElement('style');
                style.textContent = `
                    body { font-size: 16px !important; }
                    [role="dialog"], [class*="modal"] {
                        border: 2px solid #00D4AA !important;
                        box-shadow: 0 0 30px rgba(0, 212, 170, 0.3) !important;
                    }
                    button {
                        min-height: 44px !important;
                        min-width: 80px !important;
                        font-size: 15px !important;
                    }
                    ::-webkit-scrollbar { width: 8px; }
                    ::-webkit-scrollbar-thumb {
                        background: #00D4AA;
                        border-radius: 4px;
                    }
                `;
                document.head.appendChild(style);
            })();
        """.trimIndent()

        binding.sessionWebView.evaluateJavascript(css, null)
    }

    private fun setupPermissionButtons() {
        binding.btnApprove.setOnClickListener { clickApprove() }
        binding.btnDeny.setOnClickListener { clickDeny() }
    }

    fun showPermissionBar(description: String) {
        runOnUiThread {
            binding.permissionBar.visibility = View.VISIBLE
            binding.permissionDescription.text = description
            binding.permissionBar.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS
            )
        }
    }

    fun hidePermissionBar() {
        runOnUiThread {
            binding.permissionBar.visibility = View.GONE
        }
    }

    private fun clickApprove() {
        binding.sessionWebView.evaluateJavascript(
            "if(window._clairvoyantApproveBtn) window._clairvoyantApproveBtn.click();",
            null
        )
        hidePermissionBar()
        Log.i(TAG, "Permission APPROVED")
    }

    private fun clickDeny() {
        binding.sessionWebView.evaluateJavascript(
            "if(window._clairvoyantDenyBtn) window._clairvoyantDenyBtn.click();",
            null
        )
        hidePermissionBar()
        Log.i(TAG, "Permission DENIED")
    }

    // -- Voice commands --

    private fun setupVoiceCommands() {
        voiceListener = VoiceCommandListener(this, this)
        voiceListener?.startListening()
    }

    override fun onVoiceCommand(command: VoiceCommandListener.Command) {
        when (command) {
            VoiceCommandListener.Command.APPROVE -> {
                if (binding.permissionBar.visibility == View.VISIBLE) {
                    clickApprove()
                    Toast.makeText(this, "Approved by voice", Toast.LENGTH_SHORT).show()
                }
            }
            VoiceCommandListener.Command.DENY -> {
                if (binding.permissionBar.visibility == View.VISIBLE) {
                    clickDeny()
                    Toast.makeText(this, "Denied by voice", Toast.LENGTH_SHORT).show()
                }
            }
            VoiceCommandListener.Command.SCROLL_DOWN -> {
                binding.sessionWebView.evaluateJavascript("window.scrollBy(0, 300);", null)
            }
            VoiceCommandListener.Command.SCROLL_UP -> {
                binding.sessionWebView.evaluateJavascript("window.scrollBy(0, -300);", null)
            }
            VoiceCommandListener.Command.GO_BACK -> {
                finish()
            }
            VoiceCommandListener.Command.UNKNOWN -> {}
        }
    }

    override fun onVoiceListeningStateChanged(listening: Boolean) {
        runOnUiThread {
            binding.voiceStatus.text = if (listening) "Listening..." else "Voice ready"
        }
    }

    // -- Key events for Rokid touchpad --

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (binding.permissionBar.visibility == View.VISIBLE) {
                    clickApprove()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                binding.sessionWebView.evaluateJavascript("window.scrollBy(0, 300);", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                binding.sessionWebView.evaluateJavascript("window.scrollBy(0, -300);", null)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (binding.permissionBar.visibility == View.VISIBLE) {
                    clickApprove()
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (binding.permissionBar.visibility == View.VISIBLE) {
                    clickDeny()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        voiceListener?.startListening()
        if (awaitingWifiEnable && wifiConnector.isWifiEnabled()) {
            awaitingWifiEnable = false
            ensureConnectivityThenStart()
        }
    }

    override fun onPause() {
        super.onPause()
        voiceListener?.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceListener?.destroy()
        binding.sessionWebView.destroy()
        wifiConnector.release()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.sessionWebView.canGoBack()) {
            binding.sessionWebView.goBack()
        } else {
            super.onBackPressed()
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
