package com.bubbletranslate.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Captures screen content using [MediaProjection] + [ImageReader].
 *
 * Key design decisions after debugging 4 different approaches on a device
 * where VirtualDisplay produced zero frames:
 *
 * 1. **VirtualDisplay is created on the main thread** in [setMediaProjection],
 *    not lazily on an IO thread.  `createVirtualDisplay()` has thread
 *    affinity to the main Looper on many OEM implementations.
 *
 * 2. **A new VirtualDisplay is created for every capture** rather than
 *    reusing a persistent one.  On devices that enter composition bypass
 *    (no new frames for static content), a fresh VirtualDisplay forces
 *    SurfaceFlinger to produce an initial frame during setup.
 *
 * 3. **A delay gives the display pipeline time to produce the first
 *    frame** before polling begins.
 */
class ScreenshotManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null

    fun setMediaProjection(projection: MediaProjection?) {
        Log.d("BT", "ScreenshotManager.setMediaProjection: ${if (projection != null) "present" else "NULL"}")
        mediaProjection = projection
    }

    fun hasMediaProjection(): Boolean = mediaProjection != null

    /**
     * Capture one frame of the current screen content.
     *
     * Creates a short-lived VirtualDisplay + ImageReader for each capture.
     * This ensures SurfaceFlinger produces at least one initial frame.
     */
    suspend fun captureScreen(): Bitmap? {
        val projection = mediaProjection
        Log.d("BT", "📸 captureScreen: projection=${if (projection != null) "present" else "NULL — no MediaProjection!"}")
        if (projection == null) {
            Log.w("BT", "captureScreen: No MediaProjection available — returning null immediately")
            return null
        }

        // ── Step 1: Create VirtualDisplay on the MAIN thread ───────
        // createVirtualDisplay() must run on the main Looper for the
        // display pipeline to properly initialize the mirror surface.
        val setupResult = withContext(Dispatchers.Main) {
            try {
                val metrics = displayMetrics()
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi

                Log.d("BT", "📸 captureScreen: display=${width}x${height} dpi=$density")

                val reader = ImageReader.newInstance(
                    width, height, PixelFormat.RGBA_8888, 2
                )
                Log.d("BT", "📸 ImageReader created: maxImages=2 format=RGBA_8888")

                val display = projection.createVirtualDisplay(
                    "ScreenshotCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    Handler(Looper.getMainLooper())
                )

                Log.d("BT", "📸 VirtualDisplay created on main thread: ${display.display}")
                CaptureSetup(reader, display, width, height)
            } catch (e: SecurityException) {
                Log.e("BT", "📸 ❌ SecurityException creating VirtualDisplay — MediaProjection likely expired", e)
                null
            } catch (e: Exception) {
                Log.e("BT", "📸 ❌ Failed to create VirtualDisplay: ${e.javaClass.simpleName}: ${e.message}", e)
                null
            }
        }

        if (setupResult == null) {
            Log.w("BT", "📸 captureScreen: VirtualDisplay setup FAILED — returning null")
            return null
        }

        val ir = setupResult.reader
        val vd = setupResult.display
        val w = setupResult.width
        val h = setupResult.height

        // ── Step 2: Poll for the first frame on IO thread ──────────
        return withContext(Dispatchers.IO) {
            try {
                var bitmap: Bitmap? = null
                var pollCount = 0
                val deadlineMs = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadlineMs) {
                    val image = ir.acquireLatestImage()
                    pollCount++
                    if (image != null) {
                        Log.d("BT", "📸 ✅ image acquired after ${pollCount} polls (${pollCount * 50}ms)")
                        bitmap = imageToBitmap(image, w, h)
                        image.close()
                        Log.d("BT", "📸 bitmap: ${bitmap.width}x${bitmap.height}")
                        break
                    }
                    delay(50)
                }
                if (bitmap == null) {
                    Log.w("BT", "📸 ❌ timed out after ${pollCount} polls (3s) — acquireLatestImage() always returned null")
                }
                bitmap
            } catch (e: Exception) {
                Log.e("BT", "📸 ❌ poll error: ${e.javaClass.simpleName}: ${e.message}", e)
                null
            } finally {
                // Clean up this capture's VirtualDisplay
                try { vd.release(); Log.d("BT", "📸 VirtualDisplay released") } catch (_: Exception) {}
                try { ir.close(); Log.d("BT", "📸 ImageReader closed") } catch (_: Exception) {}
            }
        }
    }

    private fun displayMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            DisplayMetrics().also {
                it.widthPixels = bounds.width()
                it.heightPixels = bounds.height()
                it.densityDpi = context.resources.displayMetrics.densityDpi
            }
        } else {
            DisplayMetrics().also {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(it)
            }
        }
    }

    private fun imageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()

        if (rowPadding == 0) {
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        val paddingInts = rowPadding / pixelStride
        val intBuf = buffer.asIntBuffer()
        val pixels = IntArray(width * height)
        var dest = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[dest++] = intBuf.get()
            }
            if (y < height - 1 && paddingInts > 0) {
                intBuf.position(intBuf.position() + paddingInts)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun cleanup() {
        // Nothing to clean up — VirtualDisplay is ephemeral per capture
    }
}

/** Groups the objects produced during the main-thread setup phase. */
private data class CaptureSetup(
    val reader: ImageReader,
    val display: VirtualDisplay,
    val width: Int,
    val height: Int
)
