package com.edsolano.tracker.update

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the app current from the "latest" release that CI posts on every build.
 *
 * The first update needs one OK from Android (allow installs from this app, then one
 * "Update" tap). After that the app is its own installer of record, and Android 12+
 * lets it update itself without asking.
 */
object Updater {
    private const val BASE = "https://github.com/edsolano1/personal-tracker/releases/download/latest/"
    private const val TAG = "Updater"

    private fun installed(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    private fun get(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }

    /** Call from the activity. Does nothing when already current or offline. */
    suspend fun check(activity: Activity) {
        val ctx = activity.applicationContext
        val latest = withContext(Dispatchers.IO) {
            runCatching { get(BASE + "version.txt").inputStream.bufferedReader().readText().trim().toLong() }.getOrNull()
        } ?: return
        if (latest <= installed(ctx)) return
        Log.i(TAG, "update $latest available")

        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val p = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
            // Ask once per new version, not on every open.
            if (p.getLong("askedFor", 0) == latest) return
            p.edit().putLong("askedFor", latest).apply()
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)),
            )
            return
        }
        withContext(Dispatchers.IO) { runCatching { install(ctx) }.onFailure { Log.w(TAG, "update failed", it) } }
    }

    private fun install(context: Context) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            val conn = get(BASE + "app-debug.apk")
            try {
                conn.inputStream.use { input ->
                    session.openWrite("app.apk", 0, -1).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            } finally {
                conn.disconnect()
            }
            val pi = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pi.intentSender)
        }
    }
}

/** Android reports back here; when it wants a tap, show its "Update?" screen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("Updater", "updated")
            else -> Log.w("Updater", "install status: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
