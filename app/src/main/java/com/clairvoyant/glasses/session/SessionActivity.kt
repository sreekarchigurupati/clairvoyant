package com.clairvoyant.glasses.session

import android.content.Intent
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.clairvoyant.glasses.R
import com.clairvoyant.glasses.databinding.ActivitySessionBinding
import com.clairvoyant.glasses.network.WifiConnector
import com.clairvoyant.glasses.relay.RelayClient
import com.clairvoyant.glasses.relay.ServerMessage
import com.clairvoyant.glasses.scanner.ScannerActivity
import com.clairvoyant.glasses.voice.VoiceCommandListener
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Monitors and approves Claude Code sessions over the relay. Reuses the connectivity gate
 * (join the phone hotspot if there's no LAN route) and voice/key control from the old WebView
 * build, but the content is now a ViewPager of [SessionFragment]s fed by [RelayClient] events
 * through a pure [SessionStore].
 */
class SessionActivity : AppCompatActivity(), VoiceCommandListener.Callback, SessionHost,
    RelayClient.Listener {

    private lateinit var binding: ActivitySessionBinding
    private lateinit var wifiConnector: WifiConnector
    private lateinit var pagerAdapter: SessionPagerAdapter
    private var mediator: TabLayoutMediator? = null
    private var voiceListener: VoiceCommandListener? = null

    private val relay = RelayClient()
    override val store = SessionStore()
    private val fragments = HashMap<String, SessionFragment>()

    private var host: String = ""
    private var port: Int = 0
    private var token: String = ""
    private var relayStarted = false
    private var awaitingWifiEnable = false
    private var awaitingWifiScan = false

    companion object {
        const val EXTRA_HOST = "relay_host"
        const val EXTRA_PORT = "relay_port"
        const val EXTRA_TOKEN = "relay_token"
        private const val TAG = "ClairvoyantSession"
        private const val PREFS = "clairvoyant"
        private const val KEY_SSID = "hotspot_ssid"
        private const val KEY_PASSWORD = "hotspot_password"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        port = intent.getIntExtra(EXTRA_PORT, 0)
        token = intent.getStringExtra(EXTRA_TOKEN) ?: ""
        if (host.isEmpty() || port == 0 || token.isEmpty()) {
            Toast.makeText(this, "No relay pairing", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        wifiConnector = WifiConnector(this)
        setupPager()
        setupVoiceCommands()
        ensureConnectivityThenStart()
    }

    // -- ViewPager / tabs --

    private fun setupPager() {
        pagerAdapter = SessionPagerAdapter(this)
        binding.sessionPager.adapter = pagerAdapter
        // TabLayoutMediator listens to the adapter and repopulates tabs on every change, so it
        // is created once; we only call pagerAdapter.submit() when the session set changes.
        mediator = TabLayoutMediator(binding.sessionTabs, binding.sessionPager) { tab, position ->
            val id = pagerAdapter.idAt(position)
            tab.text = id?.let { store.data(it)?.title ?: it.take(6) } ?: "—"
        }.also { it.attach() }
        binding.sessionPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = refreshBadges()
        })
    }

    private fun rebuildPages() {
        pagerAdapter.submit(store.ids())
        binding.noSessions.visibility = if (store.ids().isEmpty()) View.VISIBLE else View.GONE
        refreshBadges()
    }

    /** Badge any tab whose session has a pending request and isn't the one being viewed. */
    private fun refreshBadges() {
        val current = binding.sessionPager.currentItem
        store.ids().forEachIndexed { index, id ->
            val tab = binding.sessionTabs.getTabAt(index) ?: return@forEachIndexed
            val pending = store.data(id)?.pending != null
            if (pending && index != current) tab.orCreateBadge else tab.removeBadge()
        }
    }

    // -- SessionHost --

    override fun register(sessionId: String, fragment: SessionFragment) { fragments[sessionId] = fragment }
    override fun unregister(sessionId: String, fragment: SessionFragment) {
        if (fragments[sessionId] === fragment) fragments.remove(sessionId)
    }

    override fun answerPermission(session: String, id: String, decision: String) {
        relay.sendPermissionResponse(session, id, decision)
        store.clearPending(session)
        fragments[session]?.refreshPending()
        refreshBadges()
        Log.i(TAG, "Permission $decision for $session/$id")
    }

    // -- RelayClient.Listener (always on main thread) --

    override fun onConnecting() = setStatus(false, getString(R.string.connecting))

    override fun onReady() {
        binding.reconnectBanner.visibility = View.GONE
        setStatus(true, getString(R.string.connected))
    }

    override fun onServerMessage(msg: ServerMessage) {
        when (val change = store.apply(msg)) {
            is SessionStore.Change.SessionsChanged -> rebuildPages()
            is SessionStore.Change.Transcript -> fragments[change.session]?.refreshTranscript()
            is SessionStore.Change.PendingChanged -> onPending(change.session)
            SessionStore.Change.None -> {}
        }
    }

    override fun onClosed(reason: String) {
        binding.reconnectBanner.visibility = View.VISIBLE
        setStatus(false, getString(R.string.reconnecting))
        Log.w(TAG, "Relay closed: $reason")
    }

    override fun onAuthFailed(message: String) {
        setStatus(false, message)
        AlertDialog.Builder(this)
            .setTitle("Pairing expired")
            .setMessage(getString(R.string.pairing_expired))
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> finish() }
            .show()
    }

    private fun onPending(session: String) {
        val visible = pagerAdapter.idAt(binding.sessionPager.currentItem)
        if (session == visible) {
            fragments[session]?.refreshPending()
        } else {
            // Background session: badge its tab and buzz so the user can swipe to it.
            refreshBadges()
            binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun setStatus(connected: Boolean, message: String) {
        binding.connectionDot.setBackgroundColor(
            getColor(if (connected) R.color.approve_green else R.color.warning_amber)
        )
        binding.connectionStatus.text = message
    }

    // -- Connectivity gate (reused) --

    private fun ensureConnectivityThenStart() {
        if (relayStarted) return
        // A loopback relay (e.g. reached over `adb reverse` for dev/testing) needs no LAN route.
        if (isLoopbackHost() || wifiConnector.hasInternet()) { startRelay(); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setStatus(false, "No network, and Wi-Fi join needs Android 10+"); return
        }
        if (!wifiConnector.isWifiEnabled()) { setStatus(false, "Wi-Fi is off"); promptEnableWifi(); return }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val ssid = prefs.getString(KEY_SSID, null)
        val password = prefs.getString(KEY_PASSWORD, null)
        if (ssid.isNullOrEmpty()) launchWifiScan() else connectWithCreds(ssid, password ?: "")
    }

    private fun launchWifiScan() {
        awaitingWifiScan = true
        val intent = Intent(this, ScannerActivity::class.java)
        intent.putExtra(ScannerActivity.EXTRA_WIFI_ONLY, true)
        startActivity(intent)
        // After the user scans their hotspot QR and returns, onResume re-runs the gate.
    }

    private fun promptEnableWifi() {
        AlertDialog.Builder(this)
            .setTitle("Turn on Wi-Fi")
            .setMessage("The glasses' Wi-Fi is off, so they can't reach the relay. Open the Wi-Fi panel to turn it on, then come back.")
            .setCancelable(false)
            .setPositiveButton("Open Wi-Fi") { _, _ ->
                awaitingWifiEnable = true
                val opened = runCatching { startActivity(Intent(Settings.Panel.ACTION_WIFI)); true }.getOrDefault(false) ||
                    runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); true }.getOrDefault(false)
                if (!opened) { awaitingWifiEnable = false; Toast.makeText(this, "No Wi-Fi settings on this device", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectWithCreds(ssid: String, password: String) {
        wifiConnector.connectViaSuggestion(ssid, password, object : WifiConnector.Listener {
            override fun onConnecting() = runOnUiThread { setStatus(false, "Joining \"$ssid\"…") }
            override fun onConnected(network: Network) = runOnUiThread { startRelay() }
            override fun onLost() = runOnUiThread { setStatus(false, "Wi-Fi lost") }
            override fun onFailed(reason: String) {
                Log.i(TAG, "Suggestion failed ($reason); trying specifier")
                wifiConnector.connect(ssid, password, object : WifiConnector.Listener {
                    override fun onConnecting() = runOnUiThread { setStatus(false, "Joining \"$ssid\"…") }
                    override fun onConnected(network: Network) = runOnUiThread { startRelay() }
                    override fun onLost() = runOnUiThread { setStatus(false, "Wi-Fi lost") }
                    override fun onFailed(reason: String) = runOnUiThread {
                        setStatus(false, reason); Toast.makeText(this@SessionActivity, reason, Toast.LENGTH_LONG).show()
                    }
                })
            }
        })
    }

    private fun isLoopbackHost(): Boolean =
        host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) || host == "::1"

    private fun startRelay() {
        if (relayStarted) return
        relayStarted = true
        Log.i(TAG, "Connecting to relay ws://$host:$port/ws")
        relay.connect(host, port, token, this)
    }

    // -- Voice + keys: act on the visible session --

    private fun visibleSessionId(): String? = pagerAdapter.idAt(binding.sessionPager.currentItem)

    private fun setupVoiceCommands() {
        voiceListener = VoiceCommandListener(this, this)
        voiceListener?.startListening()
    }

    override fun onVoiceCommand(command: VoiceCommandListener.Command) {
        val sid = visibleSessionId()
        when (command) {
            VoiceCommandListener.Command.APPROVE -> answerVisible(sid, "allow", "Approved by voice")
            VoiceCommandListener.Command.DENY -> answerVisible(sid, "deny", "Denied by voice")
            VoiceCommandListener.Command.SCROLL_DOWN -> sid?.let { fragments[it]?.scrollBy(300) }
            VoiceCommandListener.Command.SCROLL_UP -> sid?.let { fragments[it]?.scrollBy(-300) }
            VoiceCommandListener.Command.GO_BACK -> finish()
            VoiceCommandListener.Command.UNKNOWN -> {}
        }
    }

    private fun answerVisible(sid: String?, decision: String, toast: String) {
        val pending = sid?.let { store.data(it)?.pending } ?: return
        answerPermission(sid, pending.id, decision)
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    override fun onVoiceListeningStateChanged(listening: Boolean) = runOnUiThread {
        binding.voiceStatus.text = if (listening) "🎤 Listening…" else "🎤 Voice ready"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val sid = visibleSessionId()
        val pending = sid?.let { store.data(it)?.pending }
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_VOLUME_UP -> {
                if (sid != null && pending != null) { answerPermission(sid, pending.id, "allow"); return true }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (sid != null && pending != null) { answerPermission(sid, pending.id, "deny"); return true }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { sid?.let { fragments[it]?.scrollBy(300) }; return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { sid?.let { fragments[it]?.scrollBy(-300) }; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // -- Lifecycle --

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        voiceListener?.startListening()
        if (awaitingWifiEnable && wifiConnector.isWifiEnabled()) {
            awaitingWifiEnable = false; ensureConnectivityThenStart()
        } else if (awaitingWifiScan) {
            awaitingWifiScan = false; ensureConnectivityThenStart()
        }
        refreshBadges()
    }

    override fun onPause() {
        super.onPause()
        voiceListener?.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceListener?.destroy()
        relay.close()
        wifiConnector.release()
        mediator?.detach()
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
