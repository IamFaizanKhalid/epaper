package com.faizan.epaper

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class DailyEditionCheckJobService : JobService() {
    private val worker = Executors.newSingleThreadExecutor()
    private var webView: WebView? = null
    private var activeJob: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        activeJob = params
        val today = DATE_FORMAT.format(Calendar.getInstance().time)
        val dawnFetcher = NewspaperFetchers[NewspaperId.DAWN]
        val directory = dawnFetcher.editionDirectory(File(filesDir, "editions"), today)
        val completionMarker = File(directory, "complete")
        if (completionMarker.isFile) {
            try {
                notifyEditionAvailable(NewspaperEdition.readMetadata(File(directory, "edition.json")))
                jobFinished(params, false)
                return false
            } catch (error: Exception) {
                Log.e(TAG, "Today's saved Dawn edition is invalid; retrying the download.", error)
                if (!completionMarker.delete()) {
                    Log.e(TAG, "Could not remove the invalid Dawn completion marker.")
                    jobFinished(params, true)
                    return false
                }
            }
        }

        val browser = WebView(this)
        webView = browser
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (activeJob !== params || url.substringBefore('?') != "https://epaper.dawn.com/") return
                view.evaluateJavascript(editionDiscoveryScript(today)) { result ->
                    val json = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                    if (json == null) {
                        finish(params, retry = true)
                        return@evaluateJavascript
                    }
                    if (runCatching { JSONTokener(json).nextValue() as? org.json.JSONObject }
                            .getOrNull()?.optString("error") == "NOT_AVAILABLE"
                    ) {
                        finish(params, retry = false)
                        return@evaluateJavascript
                    }
                    val edition = try {
                        NewspaperEdition.fromJson(json)
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not read the discovered Dawn edition.", error)
                        finish(params, retry = true)
                        return@evaluateJavascript
                    }
                    notifyEditionAvailable(edition)
                    worker.execute {
                        try {
                            val targetDirectory = dawnFetcher.editionDirectory(
                                File(filesDir, "editions"),
                                edition.date
                            )
                            edition.downloadPageImages(targetDirectory) { _, _ -> }
                            edition.saveMetadata(targetDirectory)
                            File(targetDirectory, "complete").writeText("ok", Charsets.UTF_8)
                            finish(params, retry = false)
                        } catch (error: Exception) {
                            Log.e(TAG, "Could not download today's Dawn edition.", error)
                            finish(params, retry = true)
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame && activeJob === params) {
                    Log.e(TAG, "Could not load Dawn while checking today's edition: ${error.description}")
                    finish(params, retry = true)
                }
            }
        }
        browser.loadUrl(dawnFetcher.availabilityUrl(today))
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJob = null
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        return true
    }

    private fun finish(params: JobParameters, retry: Boolean) {
        Handler(Looper.getMainLooper()).post {
            if (activeJob !== params) return@post
            activeJob = null
            webView?.stopLoading()
            webView?.destroy()
            webView = null
            jobFinished(params, retry)
        }
    }

    private fun notifyEditionAvailable(edition: NewspaperEdition) {
        createNotificationChannel()
        val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val key = "notified_${edition.date}"
        if (preferences.getBoolean(key, false)) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Today's Dawn is available")
            .setContentText("Downloading today's paper.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            manager.notify(NOTIFICATION_ID, notification)
            preferences.edit().putBoolean(key, true).apply()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Newspaper availability",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when today's newspaper becomes available"
            enableLights(false)
            lightColor = Color.BLUE
            enableVibration(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    companion object {
        private const val JOB_ID = 2841
        private const val INTERVAL_MILLIS = 15 * 60 * 1000L
        private const val CHANNEL_ID = "newspaper_availability"
        private const val NOTIFICATION_ID = 2841
        private const val PREFERENCES = "newspaper_check"
        private const val TAG = "DailyEditionCheck"
        private val DATE_FORMAT = SimpleDateFormat("dd_MM_yyyy", Locale.US)

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, DailyEditionCheckJobService::class.java)
            val job = JobInfo.Builder(JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(INTERVAL_MILLIS)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.e(TAG, "Could not schedule the periodic Dawn availability check.")
            }
        }

        private fun editionDiscoveryScript(date: String) = """
            (function() {
                const requestedDate = '$date';
                const datePattern = /^\d{2}_\d{2}_\d{4}_001$/;
                const selector = Array.from(document.querySelectorAll('select'))
                    .find(select => Array.from(select.options).some(option => datePattern.test(option.value)));
                if (!selector || !selector.options.length) {
                    return JSON.stringify({error: 'No edition dates'});
                }
                const dates = Array.from(selector.options).map(option => option.value.substring(0, 10));
                if (!dates.includes(requestedDate)) {
                    return JSON.stringify({error: 'NOT_AVAILABLE'});
                }
                const pages = [];
                const seen = new Set();
                for (const link of document.querySelectorAll('a[href*="page="]')) {
                    const url = new URL(link.href, location.href);
                    const id = url.searchParams.get('page');
                    if (id && id.startsWith(requestedDate + '_') &&
                        /^\d{2}_\d{2}_\d{4}_\d{3}$/.test(id) && !seen.has(id)) {
                        seen.add(id);
                        pages.push({id: id, title: link.innerText.trim()});
                    }
                }
                if (!pages.length) return JSON.stringify({error: 'NOT_AVAILABLE'});
                return JSON.stringify({
                    date: requestedDate,
                    latestDate: selector.options[0].value.substring(0, 10),
                    pages: pages
                });
            })();
        """.trimIndent()
    }
}

object DailyEditionCheckScheduler {
    fun schedule(context: Context) = DailyEditionCheckJobService.schedule(context)
}

object NotificationPermission {
    fun requestIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE
            )
        }
    }

    private const val REQUEST_CODE = 2841
}
