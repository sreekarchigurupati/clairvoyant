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
 *  - two-finger swipe fwd/back  → next / previous session page
 *
 * The one-finger tap is NOT delivered to apps as a KeyEvent: the firmware sends
 * keycode 184, PhoneWindowManager strips FLAG_PASS_TO_USER and instead sends the
 * ordered broadcast ACTION_BOLON_TAP, whose default handler (the Rokid assist server)
 * takes a photo. Intercepting at priority 100 and aborting is the only way to claim
 * the tap and suppress the photo. One-finger swipes still reach the app as DPAD
 * KeyEvents and are handled in SessionActivity.onKeyDown. Temple double-click stays
 * the system back/exit gesture and is deliberately not intercepted.
 *
 * A touchpad long-press is likewise not a KeyEvent: the firmware re-emits it as the
 * ordered broadcast ACTION_AI_START (whose default handler launches the Rokid AI
 * assistant). We claim it for "always allow" and abort so the assistant doesn't open.
 *
 * The two-finger swipe broadcasts are sent non-ordered (sendBroadcastAsUser), so they
 * cannot be aborted — hence the isOrderedBroadcast guard.
 */
class RokidKeyReceiver(private val callback: Callback) : BroadcastReceiver() {

    interface Callback {
        fun onSingleTap()
        fun onLongPressTap()
        fun onTempleClick()
        fun onTwoFingerSwipeForward()
        fun onTwoFingerSwipeBack()
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_TAP -> { callback.onSingleTap(); abortIfOrdered() }
            ACTION_AI_START -> { callback.onLongPressTap(); abortIfOrdered() }
            ACTION_CLICK -> { callback.onTempleClick(); abortIfOrdered() }
            // Sent on the raw press/release around a temple click; abort so the system
            // camera doesn't also react to them.
            ACTION_BUTTON_DOWN, ACTION_BUTTON_UP -> abortIfOrdered()
            ACTION_TWO_FINGER_FWD -> { callback.onTwoFingerSwipeForward(); abortIfOrdered() }
            ACTION_TWO_FINGER_BACK -> { callback.onTwoFingerSwipeBack(); abortIfOrdered() }
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
        private const val ACTION_BUTTON_DOWN = "com.android.action.ACTION_SPRITE_BUTTON_DOWN"
        private const val ACTION_BUTTON_UP = "com.android.action.ACTION_SPRITE_BUTTON_UP"
        private const val ACTION_TWO_FINGER_FWD = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
        private const val ACTION_TWO_FINGER_BACK = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"

        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(ACTION_TAP)
            addAction(ACTION_AI_START)
            addAction(ACTION_CLICK)
            addAction(ACTION_BUTTON_DOWN)
            addAction(ACTION_BUTTON_UP)
            addAction(ACTION_TWO_FINGER_FWD)
            addAction(ACTION_TWO_FINGER_BACK)
            priority = 100
        }
    }
}
