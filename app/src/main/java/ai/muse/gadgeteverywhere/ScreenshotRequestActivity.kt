package ai.muse.gadgeteverywhere

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * Transparent prompt for one still screenshot (MediaProjection).
 *
 * MediaProjection can only be requested from an Activity, never from the
 * background service — so this activity exists only to show the system
 * "Start recording or casting?" prompt once. The user's answer is stored
 * in ScreenshotCapture (in memory) and the activity finishes immediately.
 * No recording starts here: ScreenshotCapture takes a single frame and
 * stops the projection right after.
 */
class ScreenshotRequestActivity : Activity() {

    private val requestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreated (rotation / process death): do not prompt twice.
            finish()
            return
        }
        try {
            val manager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.probe_screenshot_failed, e.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    // startActivityForResult is the MediaProjection request path: the
    // Activity Result API contract cannot carry the projection intent
    // result back into the service layer, so the classic call stays.
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode) {
            ScreenshotCapture.storeGrant(this, resultCode, data)
            Toast.makeText(
                this,
                if (resultCode == RESULT_OK && data != null) {
                    getString(R.string.probe_screenshot_granted)
                } else {
                    getString(R.string.probe_screenshot_denied)
                },
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }
}
