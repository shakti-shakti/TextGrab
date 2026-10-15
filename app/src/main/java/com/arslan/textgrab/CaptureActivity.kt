package com.arslan.textgrab

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Transparent bridge activity that requests the notification shade collapse, waits for the window
 * focus to settle on the underlying screen, takes the full-screen screenshot, and transitions
 * without flickering into [CaptureViewerActivity].
 */
class CaptureActivity : Activity() {

    private var captureInitiated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        val fromTile = intent.getBooleanExtra(EXTRA_FROM_TILE, false)
        if (fromTile) {
            CaptureService.instance.value?.dismissNotificationShade()
        }

        // Fallback timer: in case onWindowFocusChanged already fired before onCreate or in custom OEM launchers
        window.decorView.postDelayed({
            initiateCapture()
        }, if (fromTile) 650L else 250L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Once focus is confirmed, the shade has finished sliding away.
            // Wait a brief buffer (180ms) for any final surface animations to finish rendering.
            window.decorView.postDelayed({
                initiateCapture()
            }, 180L)
        }
    }

    private fun initiateCapture() {
        if (captureInitiated || isFinishing || isDestroyed) return
        captureInitiated = true

        ScreenCapture.captureAndOpen(
            context = this,
            delayMs = 0L,
            dismissShade = false,
            onComplete = {
                finish()
                if (Build.VERSION.SDK_INT >= 34) {
                    overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.no_anim, R.anim.no_anim)
                } else {
                    @Suppress("DEPRECATION")
                    overridePendingTransition(R.anim.no_anim, R.anim.no_anim)
                }
            }
        )
    }

    companion object {
        const val EXTRA_DELAY_MS = "com.arslan.textgrab.DELAY_MS"
        const val EXTRA_FROM_TILE = "com.arslan.textgrab.FROM_TILE"
    }
}
