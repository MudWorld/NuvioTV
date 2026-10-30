package com.nuvio.tv.watchparty

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import java.io.File
import java.lang.ref.WeakReference

/**
 * Screenshot of WatchParty's own window. The Sony TV blocks `screencap`, so this copies the app
 * window with PixelCopy into `Android/data/<package>/files/screenshot.png`.
 *
 * Triggered only over adb (the receiver requires android.permission.DUMP, which apps don't get):
 * `adb shell am broadcast -a space.mudworld.watchparty.SCREENSHOT -n <package>/com.nuvio.tv.watchparty.WatchPartyScreenshotReceiver`
 */
object WatchPartyScreenshot {
    private var current: WeakReference<Activity>? = null

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { current = WeakReference(activity) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun capture(context: Context, done: () -> Unit) {
        val activity = current?.get()
        val view = activity?.window?.decorView
        if (activity == null || view == null || view.width == 0 || view.height == 0) {
            Log.w("WatchPartyScreenshot", "no window")
            done()
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                val target = File(context.getExternalFilesDir(null), "screenshot.png")
                runCatching { target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                Log.i("WatchPartyScreenshot", "saved ${target.absolutePath}")
            } else {
                Log.w("WatchPartyScreenshot", "PixelCopy failed: $result")
            }
            bitmap.recycle()
            done()
        }, Handler(Looper.getMainLooper()))
    }
}

class WatchPartyScreenshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Handler(Looper.getMainLooper()).post {
            WatchPartyScreenshot.capture(context.applicationContext) { pending.finish() }
        }
    }
}
