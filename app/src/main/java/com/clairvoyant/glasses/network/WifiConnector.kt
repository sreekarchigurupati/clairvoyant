package com.clairvoyant.glasses.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Brings up a Wi-Fi connection to a specific access point (typically the phone's
 * personal hotspot) from inside the app, then binds the process to it so the
 * WebView's traffic uses that link.
 *
 * This exists because the Rokid Glasses expose no Wi-Fi settings UI and the Rokid AI
 * companion app provides no general internet route to third-party apps — the Bluetooth
 * pairing is an app-level data channel (CXR/SPP), not an IP tether. The Wi-Fi radio
 * itself is standard Android, so [WifiNetworkSpecifier] can drive it directly.
 */
class WifiConnector(context: Context) {

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var suggestions: List<WifiNetworkSuggestion>? = null

    interface Listener {
        fun onConnecting()
        fun onConnected(network: Network)
        fun onFailed(reason: String)
        fun onLost()
    }

    /** Whether the Wi-Fi radio is on. WifiNetworkSpecifier/Suggestion can't join if off. */
    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    /** Diagnostic snapshot for logcat when a join fails. */
    fun diagnostics(ssid: String): String =
        "ssid=[$ssid] len=${ssid.length} wifiEnabled=${wifiManager.isWifiEnabled} " +
            "hasInternet=${hasInternet()}"

    /** True if the device already has a validated internet route on any transport. */
    fun hasInternet(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Preferred path: register [ssid] as a network suggestion. The user approves the
     * app's suggestions **once, ever** (a system notification); thereafter the platform
     * auto-joins the network whenever it is in range — even across app restarts — and it
     * becomes a normal default network. We then wait (up to [SUGGESTION_TIMEOUT_MS]) for
     * an internet-capable network to appear and report it via [listener]. On timeout the
     * caller should fall back to [connect].
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun connectViaSuggestion(ssid: String, password: String, listener: Listener) {
        unregister()
        Log.i(TAG, "connectViaSuggestion ${diagnostics(ssid)}")

        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        if (password.isNotEmpty()) builder.setWpa2Passphrase(password)
        val list = listOf(builder.build())

        // Drop any prior suggestion we added so a changed password takes effect.
        suggestions?.let { wifiManager.removeNetworkSuggestions(it) }
        val status = wifiManager.addNetworkSuggestions(list)
        suggestions = list

        if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS &&
            status != WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
        ) {
            listener.onFailed("Could not register Wi-Fi suggestion (code $status)")
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Suggested Wi-Fi network available; binding process")
                cm.bindProcessToNetwork(network)
                listener.onConnected(network)
            }

            override fun onUnavailable() {
                Log.w(TAG, "Suggested network did not connect within timeout")
                listener.onFailed("Hotspot not joined automatically")
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Suggested Wi-Fi network lost")
                listener.onLost()
            }
        }
        callback = cb

        listener.onConnecting()
        cm.requestNetwork(request, cb, SUGGESTION_TIMEOUT_MS)
    }

    /**
     * Fallback path: request a connection to [ssid] directly. The system shows an
     * approval dialog for the specific network **each** time; on approval the callback
     * fires [Listener.onConnected] and the process is bound. Times out after
     * [REQUEST_TIMEOUT_MS].
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun connect(ssid: String, password: String, listener: Listener) {
        unregister()
        Log.i(TAG, "connect (specifier) ${diagnostics(ssid)}")

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Wi-Fi network available; binding process")
                val bound = cm.bindProcessToNetwork(network)
                if (bound) {
                    listener.onConnected(network)
                } else {
                    listener.onFailed("Connected to \"$ssid\" but could not route traffic to it.")
                }
            }

            override fun onUnavailable() {
                Log.e(TAG, "Wi-Fi request unavailable / timed out for \"$ssid\"")
                listener.onFailed(
                    "Could not join \"$ssid\". Check the hotspot is on and the password is correct."
                )
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Wi-Fi network lost for \"$ssid\"")
                listener.onLost()
            }
        }
        callback = cb

        listener.onConnecting()
        cm.requestNetwork(request, cb, REQUEST_TIMEOUT_MS)
    }

    /**
     * Unbind the process and drop the network request. Call from onDestroy.
     * Note: the registered network *suggestion* is intentionally left in place so the
     * system keeps auto-joining the hotspot on future launches without re-approval.
     */
    fun release() {
        unregister()
        cm.bindProcessToNetwork(null)
    }

    private fun unregister() {
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
                // Callback was never registered or already removed.
            }
        }
        callback = null
    }

    companion object {
        private const val TAG = "ClairvoyantWifi"
        private const val REQUEST_TIMEOUT_MS = 30_000
        private const val SUGGESTION_TIMEOUT_MS = 25_000
    }
}
