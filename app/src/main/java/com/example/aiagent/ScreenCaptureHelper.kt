package com.example.aiagent

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

object ScreenCaptureHelper {
    const val REQ_CODE_CAPTURE = 4321

    fun createProjectionIntent(context: Context): Intent {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    // Call from Activity.onActivityResult to get permission data => pass to a coroutine/service that records a single frame.
    // For brevity, here is pseudo-helper: implement MediaProjection + VirtualDisplay + ImageReader
    // This function is placeholder to show flow — real implementation requires more code.
    fun saveBitmapToFile(ctx: Context, bitmap: Bitmap, path: String): String {
        val f = File(ctx.filesDir, path)
        f.parentFile?.mkdirs()
        val out = f.outputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        out.close()
        return f.absolutePath
    }

    fun bitmapToBase64(bmp: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, baos)
        val bytes = baos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}