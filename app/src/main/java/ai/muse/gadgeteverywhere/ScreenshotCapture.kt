package ai.muse.gadgeteverywhere

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One still screenshot via MediaProjection — never a recording.
 *
 * The user grants it once, in the app, by tapping "Grant Screenshot" in
 * Diagnostics & setup and then "Allow" on the system prompt. The grant
 * (result code + intent) is held only in memory; after an app restart the
 * user must grant it again, even though the Diagnostics row still says the
 * permission was granted before. That is deliberate: a screenshot must
 * never be taken silently or from a stale grant.
 *
 * Captures run on the calling (worker) thread, never on the main thread:
 * the ImageReader listener posts to the main looper, so blocking the main
 * thread while waiting for a frame would deadlock.
 *
 * The PNG is saved under the gadget workspace (screenshots/...) so Muse
 * reads it back with the existing file.read command, in 64 KiB chunks.
 * Nothing is uploaded anywhere by this class.
 */
object ScreenshotCapture {

    private const val PREFS = "screen_control"
    private const val KEY_GRANTED = "screenshot_granted"

    @Volatile private var resultCode: Int = Activity.RESULT_CANCELED
    @Volatile private var resultData: Intent? = null

    /** True if the user has allowed a capture — this session (live intent
     *  held in memory), or a previous session (remembered flag). Only the
     *  live intent can actually capture; capture() says so when it is gone. */
    fun isGranted(context: Context): Boolean {
        if (resultData != null && resultCode == Activity.RESULT_OK) return true
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_GRANTED, false)
    }

    /** Called by ScreenshotRequestActivity with the user's answer. */
    fun storeGrant(context: Context, code: Int, data: Intent?) {
        resultCode = code
        resultData = data
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_GRANTED, code == Activity.RESULT_OK && data != null)
            .apply()
    }

    fun capture(context: Context): JSONObject {
        val app = context.applicationContext
        if (resultData == null || resultCode != Activity.RESULT_OK) {
            if (isGranted(app)) {
                throw IllegalStateException(
                    "screenshot was granted before this restart; open the gadget app > " +
                        "Device > Diagnostics & setup > Grant Screenshot and tap Allow again, then retry",
                )
            }
            throw IllegalStateException(
                "screenshot is not granted; open the gadget app > Device > " +
                    "Diagnostics & setup > Grant Screenshot and tap Allow once, then retry",
            )
        }
        val manager =
            app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, resultData!!)
            ?: throw IllegalStateException("could not start screen capture; grant it again")
        val metrics = app.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        require(width > 0 && height > 0) { "no display size available for a screenshot" }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var display: android.hardware.display.VirtualDisplay? = null
        try {
            val latch = CountDownLatch(1)
            reader.setOnImageAvailableListener(
                { latch.countDown() },
                Handler(Looper.getMainLooper()),
            )
            display = projection.createVirtualDisplay(
                "muse-gadget-screenshot",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null,
            ) ?: throw IllegalStateException("could not create a capture display")
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw IllegalStateException("screenshot timed out; the screen may be off or locked")
            }
            val image = reader.acquireLatestImage()
                ?: throw IllegalStateException("screenshot produced no frame; try again")
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                // Row padding: the buffer is wider than the screen when the
                // stride does not divide evenly; copy the padded bitmap,
                // then crop to the real size.
                val paddedWidth = rowStride / pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
                if (bitmap !== padded) padded.recycle()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val relativePath = "screenshots/screen-$stamp.png"
                val file = DeviceBridge.workspaceFile(app, relativePath)
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IllegalStateException("could not encode the screenshot")
                    }
                }
                bitmap.recycle()
                return JSONObject()
                    .put("path", relativePath)
                    .put("width", width)
                    .put("height", height)
                    .put("size_bytes", file.length())
            } finally {
                try { image.close() } catch (_: Exception) { }
            }
        } finally {
            try { reader.close() } catch (_: Exception) { }
            try { display?.release() } catch (_: Exception) { }
            try { projection.stop() } catch (_: Exception) { }
        }
    }
}
