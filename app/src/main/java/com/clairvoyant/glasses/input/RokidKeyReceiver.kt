package com.clairvoyant.glasses.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Rokid Glasses system gestures arrive as broadcasts from PhoneWindowManager (see the
 * Rokid "System Button Events" guide). We intercept the ones Clairvoyant uses and abort
 * the ordered ones so the system doesn't also act on them:
 *
 *  - one-finger tap             → approve the visible pending permission
 *  - one-finger long-press      → "always allow" the visible pending permission
 *  - temple button click        → deny the visible pending permission
 *  - temple button long-press   → cycle to the next session
 *
 * The one-finger tap is NOT delivered to apps as a KeyEvent: the firmware sends
 * keycode 184, PhoneWindowManager strips FLAG_PASS_TO_USER and instead sends the
 * ordered broadcast ACTION_BOLON_TAP, whose default handler (the Rokid assist server)
 * takes a photo. Intercepting at priority 100 and aborting is the only way to claim
 * the tap and suppress the photo. One-finger swipes still reach the app as DPAD
 * KeyEvents and are handled in SessionActivity.dispatchKeyEvent (horizontal = switch
 * session, vertical = scroll). Temple double-click stays the system back/exit gesture
 * and is deliberately not intercepted.
 *
 * A touchpad long-press is likewise not a KeyEvent: the firmware re-emits it as the
 * ordered broadcast ACTION_AI_START (whose default handler launches the Rokid AI
 * assistant). We claim it for "always allow" and abort so the assistant doesn't open.
 *
 * The two-finger swipe is deliberately NOT handled: on Rokid it's the system volume
 * gesture — the assist server turns ACTION_TWO_FINGER_SWIPE_* into volume up/down, and
 * those broadcasts are non-ordered (sendBroadcastAsUser), so they can't be aborted.
 * Claiming them for session switching only added an unavoidable volume side effect.
 * isOrderedBroadcast still guards the aborts below for the ordered actions we do use.
 */
class RokidKeyReceiver(private val callback: Callback) : BroadcastReceiver() {

    interface Callback {
        fun onSingleTap()
        fun onLongPressTap()
        fun onTempleClick()
        fun onTempleLongPress()
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_TAP -> { callback.onSingleTap(); abortIfOrdered() }
            ACTION_AI_START -> { callback.onLongPressTap(); abortIfOrdered() }
            ACTION_CLICK -> { callback.onTempleClick(); abortIfOrdered() }
            ACTION_LONG_PRESS -> { callback.onTempleLongPress(); abortIfOrdered() }
            // Sent on the raw press/release around a temple click; abort so the system
            // camera doesn't also react to them.
            ACTION_BUTTON_DOWN, ACTION_BUTTON_UP -> abortIfOrdered()
            else -> Log.d(TAG, "Ignored broadcast ${intent?.action}")
        }
    }

    private fun abortIfOrdered() {
        if (isOrderedBroadcast) abortBroadcast()
    }

    companion object {
        private const val TAG = "RokidKeyReceiver"
        private const val ACTION_TAP = "com.android.action.ACTION_BOLON_TAP"
        private const val ACTION_AI_START = "com.android.action.ACTION_AI_START"
        private const val ACTION_CLICK = "com.android.action.ACTION_SPRITE_BUTTON_CLICK"
        private const val ACTION_LONG_PRESS = "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
        private const val ACTION_BUTTON_DOWN = "com.android.action.ACTION_SPRITE_BUTTON_DOWN"
        private const val ACTION_BUTTON_UP = "com.android.action.ACTION_SPRITE_BUTTON_UP"

        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(ACTION_TAP)
            addAction(ACTION_AI_START)
            addAction(ACTION_CLICK)
            addAction(ACTION_LONG_PRESS)
            addAction(ACTION_BUTTON_DOWN)
            addAction(ACTION_BUTTON_UP)
            priority = 100
        }
    }
}
