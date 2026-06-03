package com.clairvoyant.glasses.session

import android.webkit.JavascriptInterface

/**
 * JavaScript bridge that receives permission detection events from the injected
 * DOM observer in the Claude Code WebView and forwards them to the native UI.
 */
class PermissionBridge(private val activity: SessionActivity) {

    @JavascriptInterface
    fun onPermissionDetected(description: String) {
        activity.showPermissionBar(description)
    }

    @JavascriptInterface
    fun onPermissionCleared() {
        activity.hidePermissionBar()
    }

    @JavascriptInterface
    fun log(message: String) {
        android.util.Log.d("ClairvoyantBridge", message)
    }
}
