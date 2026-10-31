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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var activeJob: JobParameters? = null
    private var checkDate: String? = null
    private var fetchersToCheck: List<NewspaperFetcher> = emptyList()
    private var fetcherIndex = 0
    private var currentFetcher: NewspaperFetcher? = null
    private var verificationAttempts = 0
    private var shouldRetry = false

    override fun onStartJob(params: JobParameters): Boolean {
        if (activeJob != null) {
            jobFinished(params, true)
            return false
        }
        activeJob = params
        val today = DATE_FORMAT.format(Calendar.getInstance().time)
        if (!NewspaperAvailability.checksAreAllowed(today)) {
            scheduleNineAmCheck(this)
            jobFinished(params, false)
            activeJob = null
            return false
        }

        checkDate = today
        fetchersToCheck = NewspaperFetchers.all
        fetcherIndex = 0
        shouldRetry = false
        val browser = WebView(this)
        webView = browser
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val fetcher = currentFetcher ?: return
                val date = checkDate ?: return
                if (activeJob !== params || !fetcher.isAvailabilityPageUrl(url, date)) return
                discoverEdition(view, params, fetcher, date)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame && activeJob === params) {
                    val fetcher = currentFetcher ?: return
                    Log.e(TAG, "Could not check ${fetcher.id.displayName}: ${error.description}")
                    shouldRetry = true
                    checkNextFetcher(params)
                }
            }
        }
        checkNextFetcher(params)
        return true
    }

    private fun checkNextFetcher(params: JobParameters) {
        if (activeJob !== params) return
        if (fetcherIndex >= fetchersToCheck.size) {
            finish(params, shouldRetry)
            return
        }
        val fetcher = fetchersToCheck[fetcherIndex++]
        currentFetcher = fetcher
        verificationAttempts = 0
        val date = checkDate ?: return
        val directory = fetcher.editionDirectory(File(filesDir, "editions"), date)
        val completionMarker = File(directory, "complete")
        val metadataFile = File(directory, "edition.json")
        if (metadataFile.isFile) {
            val savedEdition = try {
                NewspaperEdition.readMetadata(metadataFile).also {
                    require(NewspaperFetchers.forEdition(it).id == fetcher.id) {
                        "Saved edition belongs to another newspaper."
                    }
                    require(it.date == date) {
                        "Saved edition has an unexpected date."
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Today's saved ${fetcher.id.displayName} edition is invalid.", error)
                if (completionMarker.exists() && !completionMarker.delete()) {
                    Log.e(TAG, "Could not remove ${fetcher.id.displayName}'s invalid completion marker.")
                    shouldRetry = true
                    checkNextFetcher(params)
                    return
                }
                null
            }
            if (savedEdition != null) {
                notifyEditionAvailable(fetcher, savedEdition)
                val complete = completionMarker.isFile &&
                    savedEdition.pages.all { savedEdition.hasPageImage(directory, it.id) }
                if (complete) {
                    checkNextFetcher(params)
                } else {
                    if (completionMarker.exists() && !completionMarker.delete()) {
                        Log.e(TAG, "Could not reset ${fetcher.id.displayName}'s incomplete marker.")
                        shouldRetry = true
                        checkNextFetcher(params)
                    } else {
                        downloadKnownEdition(params, fetcher, savedEdition, directory)
                    }
                }
                return
            }
        } else if (completionMarker.exists() && !completionMarker.delete()) {
            Log.e(TAG, "Could not remove ${fetcher.id.displayName}'s invalid completion marker.")
            shouldRetry = true
            checkNextFetcher(params)
            return
        }
        webView?.loadUrl(fetcher.availabilityUrl(date))
            ?: run {
                shouldRetry = true
                checkNextFetcher(params)
            }
    }

    private fun discoverEdition(
        view: WebView,
        params: JobParameters,
        fetcher: NewspaperFetcher,
        date: String
    ) {
        view.evaluateJavascript(fetcher.availabilityDiscoveryScript(date)) { result ->
            if (activeJob !== params || currentFetcher != fetcher) return@evaluateJavascript
            val json = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            if (json == null) {
                Log.e(TAG, "Could not read ${fetcher.id.displayName}'s availability response.")
                shouldRetry = true
                checkNextFetcher(params)
                return@evaluateJavascript
            }
            val response = runCatching {
                JSONTokener(json).nextValue() as? org.json.JSONObject
            }.getOrNull()
            when (response?.optString("error")) {
                "NOT_AVAILABLE" -> {
                    checkNextFetcher(params)
                    return@evaluateJavascript
                }
                "VERIFYING" -> {
                    if (verificationAttempts++ < MAX_VERIFICATION_ATTEMPTS) {
                        mainHandler.postDelayed({
                            if (activeJob === params && currentFetcher == fetcher) {
                                discoverEdition(view, params, fetcher, date)
                            }
                        }, VERIFICATION_RETRY_MILLIS)
                    } else {
                        Log.e(TAG, "${fetcher.id.displayName} verification did not finish in time.")
                        shouldRetry = true
                        checkNextFetcher(params)
                    }
                    return@evaluateJavascript
                }
                null -> Unit
                else -> {
                    Log.e(TAG, "${fetcher.id.displayName} availability check failed: ${response.optString("error")}")
                    shouldRetry = true
                    checkNextFetcher(params)
                    return@evaluateJavascript
                }
            }
            val edition = try {
                fetcher.parseAvailability(json)
            } catch (error: Exception) {
                Log.e(TAG, "Could not parse ${fetcher.id.displayName}'s available edition.", error)
                shouldRetry = true
                checkNextFetcher(params)
                return@evaluateJavascript
            }
            val targetDirectory = fetcher.editionDirectory(File(filesDir, "editions"), edition.date)
            try {
                if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                    throw IllegalStateException(
                        "Could not create ${fetcher.id.displayName}'s edition storage."
                    )
                }
                edition.saveMetadata(targetDirectory)
            } catch (error: Exception) {
                Log.e(TAG, "Could not save ${fetcher.id.displayName}'s availability metadata.", error)
                shouldRetry = true
                checkNextFetcher(params)
                return@evaluateJavascript
            }
            notifyEditionAvailable(fetcher, edition)
            downloadKnownEdition(params, fetcher, edition, targetDirectory)
        }
    }

    private fun downloadKnownEdition(
        params: JobParameters,
        fetcher: NewspaperFetcher,
        edition: NewspaperEdition,
        targetDirectory: File
    ) {
        worker.execute {
            try {
                fetcher.downloadEdition(edition, targetDirectory) { _, _ -> }
                edition.saveMetadata(targetDirectory)
                File(targetDirectory, "complete").writeText("ok", Charsets.UTF_8)
            } catch (error: Exception) {
                Log.e(TAG, "Could not download ${fetcher.id.displayName}'s edition.", error)
                shouldRetry = true
            }
            mainHandler.post {
                if (activeJob === params) checkNextFetcher(params)
            }
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJob = null
        cleanupWebView()
        currentFetcher = null
        fetchersToCheck = emptyList()
        checkDate = null
        return true
    }

    private fun finish(params: JobParameters, retry: Boolean) {
        Handler(Looper.getMainLooper()).post {
            if (activeJob !== params) return@post
            activeJob = null
            cleanupWebView()
            currentFetcher = null
            fetchersToCheck = emptyList()
            checkDate = null
            jobFinished(params, retry)
        }
    }

    private fun cleanupWebView() {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
    }

    private fun notifyEditionAvailable(fetcher: NewspaperFetcher, edition: NewspaperEdition) {
        createNotificationChannel()
        val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val key = "notified_${fetcher.id.storageName}_${edition.date}"
        if (preferences.getBoolean(key, false)) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID + fetcher.id.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Today's ${fetcher.id.displayName} is available")
            .setContentText("Downloading today's paper.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            manager.notify(NOTIFICATION_ID + fetcher.id.ordinal, notification)
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
        private const val NINE_AM_JOB_ID = 2842
        private const val INTERVAL_MILLIS = 15 * 60 * 1000L
        private const val CHANNEL_ID = "newspaper_availability"
        private const val NOTIFICATION_ID = 2841
        private const val PREFERENCES = "newspaper_check"
        private const val TAG = "DailyEditionCheck"
        private val DATE_FORMAT = SimpleDateFormat("dd_MM_yyyy", Locale.US)

        private fun scheduleNineAmCheck(context: Context) {
            val now = Calendar.getInstance()
            val nextCheck = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val delayMillis = (nextCheck.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(
                NINE_AM_JOB_ID,
                ComponentName(context, DailyEditionCheckJobService::class.java)
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setMinimumLatency(delayMillis)
                .setOverrideDeadline(delayMillis + 5 * 60 * 1000L)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.e(TAG, "Could not schedule the 9 AM availability check.")
            }
        }

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, DailyEditionCheckJobService::class.java)
            val job = JobInfo.Builder(JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(INTERVAL_MILLIS)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.e(TAG, "Could not schedule the periodic newspaper availability check.")
            }
        }
        private const val MAX_VERIFICATION_ATTEMPTS = 20
        private const val VERIFICATION_RETRY_MILLIS = 1_500L
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
