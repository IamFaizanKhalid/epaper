package com.iamfaizankhalid.epaper

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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class NewspaperEditionCheckJobService : JobService() {
    private enum class JangImagePhase {
        FETCHING,
        VERIFYING_IMAGE,
        VERIFYING_PAGE
    }

    private class JangImageRequest(
        val url: String,
        val referer: String,
        val destination: File
    ) {
        val finished = CountDownLatch(1)
        val imageBuffer = ByteArrayOutputStream()
        var phase = JangImagePhase.FETCHING
        var verificationAttempts = 0
        var redirectAttempts = 0
        var pageImageAttempts = 0
        var transferAttempts = 0
        var error: String? = null
    }

    protected abstract val paperId: NewspaperId

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var activeJob: JobParameters? = null
    private var checkDate: String? = null
    private var papersToCheck: List<Paper> = emptyList()
    private var paperIndex = 0
    private var currentPaper: Paper? = null
    private var verificationAttempts = 0
    private var shouldRetry = false
    private var backgroundEdition: NewspaperEdition? = null
    private var backgroundDirectory: File? = null
    private var backgroundPages: List<NewspaperPage> = emptyList()
    private var backgroundPageIndex = 0
    private var backgroundStories: List<String> = emptyList()
    private var backgroundStoryIndex = 0
    private var backgroundStoryAttempt = 0
    private var activeJangImageRequest: JangImageRequest? = null

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
        papersToCheck = listOf(Papers[paperId])
        paperIndex = 0
        shouldRetry = false
        val browser = WebView(this)
        webView = browser
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val imageRequest = activeJangImageRequest
                if (imageRequest != null) {
                    val uri = Uri.parse(url)
                    if (uri.host.equals("e.jang.com.pk", ignoreCase = true)) {
                        if (imageRequest.phase != JangImagePhase.VERIFYING_PAGE) {
                            imageRequest.phase = JangImagePhase.VERIFYING_IMAGE
                        }
                        inspectJangImageDocument(imageRequest)
                    }
                    return
                }
                val fetcher = currentPaper ?: return
                val date = checkDate ?: return
                if (activeJob !== params) return
                val uri = Uri.parse(url)
                val isJangHomePage = fetcher.requiresPageVerification &&
                    uri.host.equals(
                        Uri.parse(fetcher.availabilityUrl(date)).host,
                        ignoreCase = true
                    ) && uri.path == "/"
                if (fetcher.isAvailabilityPageUrl(url, date) || isJangHomePage) {
                    discoverEdition(view, params, fetcher, date)
                    return
                }
                if (fetcher.requiresPageVerification) return
                val edition = backgroundEdition ?: return
                val page = backgroundPages.getOrNull(backgroundPageIndex)
                if (page != null && fetcher.isPageUrl(url, edition.date, page.id)) {
                    collectBackgroundPageHotspots(view, params, fetcher, edition, page)
                    return
                }
                val storyId = backgroundStories.getOrNull(backgroundStoryIndex) ?: return
                if (fetcher.isArticleUrl(url, storyId)) {
                    collectBackgroundArticle(view, params, fetcher, edition, storyId)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame && activeJob === params) {
                    val imageRequest = activeJangImageRequest
                    if (imageRequest != null) {
                        finishJangImageRequest(
                            imageRequest,
                            "Could not load Jang image request: ${error.description}."
                        )
                        return
                    }
                    val fetcher = currentPaper ?: return
                    Log.e(TAG, "Could not check ${fetcher.id.displayName}: ${error.description}")
                    shouldRetry = true
                    if (backgroundEdition != null) {
                        skipBackgroundItem(params)
                    } else {
                        checkNextFetcher(params)
                    }
                }
            }
        }
        checkNextFetcher(params)
        return true
    }

    private fun checkNextFetcher(params: JobParameters) {
        if (activeJob !== params) return
        if (paperIndex >= papersToCheck.size) {
            finish(params, shouldRetry)
            return
        }
        val fetcher = papersToCheck[paperIndex++]
        currentPaper = fetcher
        backgroundEdition = null
        backgroundDirectory = null
        backgroundPages = emptyList()
        backgroundPageIndex = 0
        backgroundStories = emptyList()
        backgroundStoryIndex = 0
        verificationAttempts = 0
        val date = checkDate ?: return
        val directory = fetcher.editionDirectory(File(filesDir, "editions"), date)
        val completionMarker = File(directory, "complete")
        val metadataFile = File(directory, "edition.json")
        if (metadataFile.isFile) {
            val savedEdition = try {
                NewspaperEdition.readMetadata(metadataFile).also {
                    require(Papers.forEdition(it).id == fetcher.id) {
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
                val complete = isBackgroundEditionComplete(
                    fetcher,
                    savedEdition,
                    directory,
                    completionMarker
                )
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
        fetcher: Paper,
        date: String
    ) {
        view.evaluateJavascript(fetcher.availabilityDiscoveryScript(date)) { result ->
            if (activeJob !== params || currentPaper != fetcher) return@evaluateJavascript
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
                "NOT_AVAILABLE", "NOT_PUBLISHED" -> {
                    checkNextFetcher(params)
                    return@evaluateJavascript
                }
                "VERIFYING", "PENDING" -> {
                    if (verificationAttempts++ < MAX_VERIFICATION_ATTEMPTS) {
                        mainHandler.postDelayed({
                            if (activeJob === params && currentPaper == fetcher) {
                                if (fetcher.requiresPageVerification &&
                                    Uri.parse(view.url.orEmpty()).path == "/"
                                ) {
                                    view.loadUrl(fetcher.availabilityUrl(date))
                                } else {
                                    discoverEdition(view, params, fetcher, date)
                                }
                            }
                        }, VERIFICATION_RETRY_MILLIS)
                    } else {
                        val error = response.optString("error")
                        val message = if (error == "VERIFYING") {
                            "${fetcher.id.displayName} verification did not finish in time."
                        } else {
                            "${fetcher.id.displayName}'s edition page did not load its page list."
                        }
                        Log.e(TAG, message)
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
        fetcher: Paper,
        edition: NewspaperEdition,
        targetDirectory: File
    ) {
        backgroundEdition = edition
        backgroundDirectory = targetDirectory
        worker.execute {
            var downloadFailed = false
            try {
                val imageDownloader: JangImageDownloader? =
                    if (fetcher.requiresPageVerification) {
                        { url, referer, destination ->
                            downloadJangImageThroughWebView(url, referer, destination)
                        }
                    } else {
                        null
                    }
                fetcher.downloadEdition(
                    edition,
                    targetDirectory,
                    jangImageDownloader = imageDownloader,
                    onProgress = { _, _ -> }
                )
                edition.saveMetadata(targetDirectory)
            } catch (error: Exception) {
                Log.e(TAG, "Could not download ${fetcher.id.displayName}'s edition.", error)
                shouldRetry = true
                downloadFailed = true
            }
            mainHandler.post {
                if (activeJob === params) {
                    if (downloadFailed) checkNextFetcher(params)
                    else beginBackgroundPageScan(params, fetcher, edition, targetDirectory)
                }
            }
        }
    }

    private fun downloadJangImageThroughWebView(
        url: URL,
        referer: String,
        destination: File
    ) {
        require(url.protocol == "https" &&
            url.host.equals("e.jang.com.pk", ignoreCase = true) &&
            (url.path.startsWith("/static_pages/") ||
                url.path.startsWith("/assets/uploads/"))
        ) { "Invalid Jang image URL." }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Jang image downloads must run off the main thread."
        }

        val request = JangImageRequest(url.toString(), referer, destination)
        mainHandler.post {
            val browser = webView
            if (activeJob == null || browser == null) {
                finishJangImageRequest(request, "Jang image download was cancelled.")
            } else {
                activeJangImageRequest = request
                browser.loadUrl(
                    request.url,
                    mapOf("Referer" to request.referer, "Accept" to "*/*")
                )
            }
        }
        if (!request.finished.await(JANG_WEBVIEW_IMAGE_DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            mainHandler.post {
                if (activeJangImageRequest === request) {
                    finishJangImageRequest(request, "Jang image download timed out.")
                    webView?.stopLoading()
                }
            }
            throw IllegalStateException("Jang image download timed out for $url.")
        }
        request.error?.let { throw IllegalStateException(it) }
        request.destination.outputStream().use { it.write(request.imageBuffer.toByteArray()) }
    }

    private fun inspectJangImageDocument(request: JangImageRequest) {
        if (activeJangImageRequest !== request) return
        val expectedUrl = JSONObject.quote(request.url)
        val script = """
            (() => {
                const title = (document.title || '').toLowerCase();
                const text = (document.body?.innerText || '').trim();
                const lowerText = text.slice(0, 4000).toLowerCase();
                const challenge = title.includes('moment, please') ||
                    title.includes('just a moment') ||
                    lowerText.includes('verify you are human') ||
                    lowerText.includes('checking your browser') ||
                    lowerText.includes('security verification') ||
                    lowerText.includes('performing security verification') ||
                    lowerText.includes('request is being verified') ||
                    document.querySelector('#challenge-running, #challenge-stage');
                const image = document.images[0];
                const pageImage = document.querySelector('img[usemap]');
                return JSON.stringify({
                    url: location.href,
                    expected: location.href.split('#')[0] === $expectedUrl,
                    challenge: Boolean(challenge),
                    imageReady: Boolean(image && image.complete && image.naturalWidth > 0),
                    pageImageReady: Boolean(pageImage && pageImage.complete &&
                        pageImage.naturalWidth > 0),
                    message: text.slice(0, 1000)
                });
            })()
        """.trimIndent()
        webView?.evaluateJavascript(script) { result ->
            if (activeJangImageRequest !== request) return@evaluateJavascript
            try {
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not inspect Jang image response.")
                val state = JSONObject(json)
                if (state.optBoolean("challenge")) {
                    if (request.verificationAttempts >= MAX_JANG_IMAGE_VERIFICATION_ATTEMPTS) {
                        finishJangImageRequest(
                            request,
                            "Jang image verification timed out at ${state.optString("url")}."
                        )
                    } else {
                        request.verificationAttempts++
                        webView?.postDelayed(
                            { inspectJangImageDocument(request) },
                            JANG_IMAGE_VERIFICATION_RETRY_MILLIS
                        )
                    }
                    return@evaluateJavascript
                }
                if (request.phase == JangImagePhase.VERIFYING_PAGE) {
                    if (state.optBoolean("pageImageReady")) {
                        if (Uri.parse(request.url).path?.contains("/mainpage/page") == true) {
                            prepareJangImageTransfer(request, usePageScanImage = true)
                        } else {
                            request.phase = JangImagePhase.FETCHING
                            webView?.loadUrl(
                                request.url,
                                mapOf("Referer" to request.referer, "Accept" to "*/*")
                            )
                        }
                        return@evaluateJavascript
                    }
                    if (request.pageImageAttempts++ < MAX_JANG_PAGE_IMAGE_WAIT_ATTEMPTS) {
                        webView?.postDelayed(
                            { inspectJangImageDocument(request) },
                            JANG_VERIFIED_PAGE_WAIT_MILLIS
                        )
                    } else {
                        request.phase = JangImagePhase.FETCHING
                        webView?.loadUrl(
                            request.url,
                            mapOf("Referer" to request.referer, "Accept" to "*/*")
                        )
                    }
                    return@evaluateJavascript
                }
                if (!state.optBoolean("expected") && !state.optBoolean("challenge") &&
                    request.redirectAttempts < MAX_JANG_IMAGE_REDIRECT_RETRIES
                ) {
                    request.redirectAttempts++
                    request.phase = JangImagePhase.VERIFYING_PAGE
                    webView?.loadUrl(request.referer)
                    return@evaluateJavascript
                }
                if (!state.optBoolean("expected") || !state.optBoolean("imageReady")) {
                    finishJangImageRequest(
                        request,
                        "Jang did not return the requested image. Final URL: " +
                            "${state.optString("url")}; response: ${state.optString("message")}"
                    )
                    return@evaluateJavascript
                }
                prepareJangImageTransfer(request)
            } catch (error: Exception) {
                finishJangImageRequest(
                    request,
                    error.message ?: "Could not inspect Jang image response."
                )
            }
        }
    }

    private fun prepareJangImageTransfer(
        request: JangImageRequest,
        usePageScanImage: Boolean = false
    ) {
        val expectedUrl = JSONObject.quote(request.url)
        val imageSelector = if (usePageScanImage) {
            "document.querySelector('img[usemap]')"
        } else {
            "document.images[0]"
        }
        val script = """
            (() => {
                const image = $imageSelector;
                if (!image || !image.complete || image.naturalWidth <= 0) {
                    return JSON.stringify({error: 'The verified Jang image is not ready.'});
                }
                try {
                    const canvas = document.createElement('canvas');
                    canvas.width = image.naturalWidth;
                    canvas.height = image.naturalHeight;
                    canvas.getContext('2d').drawImage(image, 0, 0);
                    const type = ${if (usePageScanImage) "'image/jpeg'" else "$expectedUrl.toLowerCase().split('?')[0].endsWith('.png') ? 'image/png' : 'image/jpeg'"};
                    window.__epaperImageTransferData =
                        canvas.toDataURL(type, 1).split(',')[1];
                    window.__epaperImageTransferOffset = 0;
                } catch (error) {
                    return JSON.stringify({error: String(error)});
                }
                return 'started';
            })()
        """.trimIndent()
        webView?.evaluateJavascript(script) { result ->
            if (activeJangImageRequest !== request) return@evaluateJavascript
            try {
                val value = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not start Jang image transfer.")
                if (value != "started") {
                    finishJangImageRequest(
                        request,
                        JSONObject(value).optString("error", "Could not encode Jang image.")
                    )
                    return@evaluateJavascript
                }
                readJangImageTransfer(request)
            } catch (error: Exception) {
                finishJangImageRequest(
                    request,
                    error.message ?: "Could not start Jang image transfer."
                )
            }
        }
    }

    private fun readJangImageTransfer(request: JangImageRequest) {
        if (activeJangImageRequest !== request) return
        val script = """
            (() => {
                const data = window.__epaperImageTransferData;
                if (!data) return JSON.stringify({error: 'Verified image data is unavailable.'});
                const start = window.__epaperImageTransferOffset || 0;
                const end = Math.min(start + $JANG_IMAGE_TRANSFER_CHUNK_SIZE, data.length);
                window.__epaperImageTransferOffset = end;
                let chunk = data.slice(start, end);
                if (end >= data.length) chunk = chunk.padEnd(Math.ceil(chunk.length / 4) * 4, '=');
                return JSON.stringify({chunk: chunk, done: end >= data.length});
            })()
        """.trimIndent()
        webView?.evaluateJavascript(script) { result ->
            if (activeJangImageRequest !== request) return@evaluateJavascript
            try {
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read Jang image data.")
                val payload = JSONObject(json)
                payload.optString("error").takeIf { it.isNotBlank() }?.let {
                    finishJangImageRequest(request, it)
                    return@evaluateJavascript
                }
                val chunk = payload.optString("chunk")
                if (chunk.isNotEmpty()) {
                    request.imageBuffer.write(Base64.decode(chunk, Base64.DEFAULT))
                    if (request.imageBuffer.size() > MAX_JANG_WEBVIEW_IMAGE_BYTES) {
                        finishJangImageRequest(request, "Jang image is larger than expected.")
                        return@evaluateJavascript
                    }
                }
                if (payload.optBoolean("done")) {
                    finishJangImageRequest(request)
                } else if (request.transferAttempts++ < MAX_JANG_IMAGE_TRANSFER_ATTEMPTS) {
                    webView?.postDelayed(
                        { readJangImageTransfer(request) },
                        JANG_IMAGE_TRANSFER_POLL_MILLIS
                    )
                } else {
                    finishJangImageRequest(request, "Timed out reading verified Jang image data.")
                }
            } catch (error: Exception) {
                finishJangImageRequest(
                    request,
                    error.message ?: "Could not read verified Jang image data."
                )
            }
        }
    }

    private fun finishJangImageRequest(request: JangImageRequest, error: String? = null) {
        if (activeJangImageRequest != null && activeJangImageRequest !== request) return
        request.error = error
        if (activeJangImageRequest === request) activeJangImageRequest = null
        request.finished.countDown()
    }

    private fun beginBackgroundPageScan(
        params: JobParameters,
        fetcher: Paper,
        edition: NewspaperEdition,
        directory: File
    ) {
        backgroundEdition = edition
        backgroundDirectory = directory
        backgroundPages = edition.readingOrder.filterNot { it.id in edition.processedPageIds }
        backgroundPageIndex = 0
        if (backgroundPages.isEmpty()) {
            beginBackgroundStoryScan(params, fetcher, edition, directory)
        } else {
            loadNextBackgroundPage(params, fetcher, edition)
        }
    }

    private fun loadNextBackgroundPage(
        params: JobParameters,
        fetcher: Paper,
        edition: NewspaperEdition
    ) {
        if (activeJob !== params || backgroundEdition !== edition) return
        val page = backgroundPages.getOrNull(backgroundPageIndex)
        if (page == null) {
            beginBackgroundStoryScan(
                params,
                fetcher,
                backgroundEdition ?: edition,
                backgroundDirectory ?: return
            )
            return
        }
        webView?.loadUrl(
            fetcher.pageUrl(edition.date, page.id),
            mapOf("Referer" to fetcher.availabilityUrl(edition.date))
        ) ?: skipBackgroundItem(params)
    }

    private fun collectBackgroundPageHotspots(
        view: WebView,
        params: JobParameters,
        fetcher: Paper,
        edition: NewspaperEdition,
        page: NewspaperPage,
        attempt: Int = 0
    ) {
        view.evaluateJavascript(fetcher.hotspotExtractionScript(edition.date, page.id)) { result ->
            if (activeJob !== params || backgroundEdition !== edition) return@evaluateJavascript
            try {
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read story areas.")
                val response = runCatching { JSONObject(json) }.getOrNull()
                if (response?.optBoolean("verifying") == true) {
                    retryBackgroundPage(params, fetcher, edition, page, attempt)
                    return@evaluateJavascript
                }
                val hotspots = fetcher.parseHotspots(json, edition, page)
                val updated = edition.copy(
                    hotspots = (edition.hotspots.filterNot { it.pageId == page.id } + hotspots)
                        .distinctBy { it.pageId to it.targetId },
                    processedPageIds = (edition.processedPageIds + page.id).distinct(),
                    jangImageAreasVersion = if (edition.isJangEdition) 1
                    else edition.jangImageAreasVersion
                )
                updated.saveMetadata(backgroundDirectory ?: return@evaluateJavascript)
                backgroundEdition = updated
                backgroundPageIndex++
                loadNextBackgroundPage(params, fetcher, updated)
            } catch (error: Exception) {
                Log.e(TAG, "Could not read clickable areas on ${page.title}.", error)
                shouldRetry = true
                backgroundPageIndex++
                loadNextBackgroundPage(params, fetcher, edition)
            }
        }
    }

    private fun retryBackgroundPage(
        params: JobParameters,
        fetcher: Paper,
        edition: NewspaperEdition,
        page: NewspaperPage,
        attempt: Int
    ) {
        if (attempt >= MAX_VERIFICATION_ATTEMPTS) {
            Log.e(TAG, "${fetcher.id.displayName} verification did not finish on ${page.title}.")
            shouldRetry = true
            backgroundPageIndex++
            loadNextBackgroundPage(params, fetcher, edition)
            return
        }
        mainHandler.postDelayed({
            if (activeJob === params && backgroundEdition === edition) {
                collectBackgroundPageHotspots(
                    webView ?: return@postDelayed,
                    params,
                    fetcher,
                    edition,
                    page,
                    attempt + 1
                )
            }
        }, VERIFICATION_RETRY_MILLIS)
    }

    private fun beginBackgroundStoryScan(
        params: JobParameters,
        fetcher: Paper,
        edition: NewspaperEdition,
        directory: File
    ) {
        backgroundEdition = edition
        backgroundDirectory = directory
        val existingIds = edition.articles.map { it.id }.toSet()
        backgroundStories = edition.readingOrder
            .flatMap { page ->
                edition.hotspots.filter {
                    it.pageId == page.id && it.type == NewspaperHotspotType.TEXT
                }.map { it.targetId }
            }
            .distinct()
            .filterNot { it in existingIds }
        backgroundStoryIndex = 0
        loadNextBackgroundStory(params, fetcher, edition)
    }

    private fun loadNextBackgroundStory(
        params: JobParameters,
        fetcher: Paper,
        edition: NewspaperEdition
    ) {
        if (activeJob !== params || backgroundEdition !== edition) return
        val storyId = backgroundStories.getOrNull(backgroundStoryIndex)
        if (storyId == null) {
            downloadBackgroundImages(params, fetcher, edition, backgroundDirectory ?: return)
            return
        }
        backgroundStoryAttempt = 0
        webView?.loadUrl(fetcher.articleUrl(storyId)) ?: skipBackgroundItem(params)
    }

    private fun collectBackgroundArticle(
        view: WebView,
        params: JobParameters,
        fetcher: Paper,
        edition: NewspaperEdition,
        storyId: String
    ) {
        view.evaluateJavascript(fetcher.articleExtractionScript(storyId)) { result ->
            if (activeJob !== params || backgroundEdition !== edition) return@evaluateJavascript
            try {
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read story text.")
                val response = runCatching { JSONObject(json) }.getOrNull()
                if (response?.optBoolean("verifying") == true ||
                    response?.optBoolean("pending") == true
                ) {
                    if (backgroundStoryAttempt++ < MAX_VERIFICATION_ATTEMPTS) {
                        mainHandler.postDelayed({
                            if (activeJob === params && backgroundEdition === edition) {
                                collectBackgroundArticle(
                                    view,
                                    params,
                                    fetcher,
                                    edition,
                                    storyId
                                )
                            }
                        }, VERIFICATION_RETRY_MILLIS)
                    } else {
                        Log.e(TAG, "${fetcher.id.displayName} story $storyId did not finish loading.")
                        shouldRetry = true
                        advanceBackgroundStory(params, fetcher, edition)
                    }
                    return@evaluateJavascript
                }
                val article = fetcher.parseArticle(json, storyId)
                val updated = edition.copy(
                    articles = (edition.articles.filterNot { it.id == storyId } + article)
                )
                updated.saveMetadata(backgroundDirectory ?: return@evaluateJavascript)
                backgroundEdition = updated
                advanceBackgroundStory(params, fetcher, updated)
            } catch (error: Exception) {
                Log.e(TAG, "Could not save ${fetcher.id.displayName} story $storyId.", error)
                shouldRetry = true
                advanceBackgroundStory(params, fetcher, edition)
            }
        }
    }

    private fun advanceBackgroundStory(
        params: JobParameters,
        fetcher: Paper,
        edition: NewspaperEdition
    ) {
        backgroundStoryIndex++
        loadNextBackgroundStory(params, fetcher, edition)
    }

    private fun downloadBackgroundImages(
        params: JobParameters,
        fetcher: Paper,
        edition: NewspaperEdition,
        directory: File
    ) {
        val hotspotImageIds = edition.readingOrder.flatMap { page ->
            edition.hotspots.filter {
                it.pageId == page.id && it.type == NewspaperHotspotType.IMAGE
            }.map { it.targetId }
        }
        val detailImageIds = if (fetcher.supportsImageDetail) {
            edition.readingOrder.flatMap { page ->
                edition.hotspots.filter {
                    it.pageId == page.id && it.type == NewspaperHotspotType.TEXT
                }.map { it.targetId }
            }
        } else {
            emptyList()
        }
        val imageIds = (hotspotImageIds + detailImageIds)
            .distinct()
            .filterNot { edition.hasFullImage(directory, it) }
        val updated = edition.copy(fullImageIds = (edition.fullImageIds + imageIds).distinct())
        try {
            updated.saveMetadata(directory)
        } catch (error: Exception) {
            Log.e(TAG, "Could not save image prefetch metadata for ${fetcher.id.displayName}.", error)
            shouldRetry = true
            checkNextFetcher(params)
            return
        }
        backgroundEdition = updated
        val hasPendingArticleImages = updated.articles.any { article ->
            article.imageUrls.indices.any { index ->
                !updated.hasArticleImage(directory, article, index)
            }
        }
        if (imageIds.isEmpty() && !hasPendingArticleImages) {
            completeBackgroundEdition(params, fetcher, updated, directory)
            return
        }
        worker.execute {
            var downloadFailed = false
            try {
                val imageDownloader: JangImageDownloader? =
                    if (fetcher.requiresPageVerification) {
                        { url, referer, destination ->
                            downloadJangImageThroughWebView(url, referer, destination)
                        }
                    } else {
                        null
                    }
                if (imageIds.isNotEmpty()) {
                    updated.downloadFullImages(
                        directory,
                        imageIds,
                        onProgress = { _, _ -> },
                        jangImageDownloader = imageDownloader
                    )
                }
                updated.downloadArticleImages(directory, imageDownloader)
            } catch (error: Exception) {
                Log.e(TAG, "Could not download all images for ${fetcher.id.displayName}.", error)
                downloadFailed = true
            }
            mainHandler.post {
                if (activeJob !== params) return@post
                if (downloadFailed || imageIds.any { !updated.hasFullImage(directory, it) } ||
                    !updated.hasArticleImages(directory)
                ) {
                    shouldRetry = true
                    checkNextFetcher(params)
                } else {
                    completeBackgroundEdition(params, fetcher, updated, directory)
                }
            }
        }
    }

    private fun completeBackgroundEdition(
        params: JobParameters,
        fetcher: Paper,
        edition: NewspaperEdition,
        directory: File
    ) {
        try {
            edition.saveMetadata(directory)
            if (isBackgroundContentReady(fetcher, edition, directory)) {
                File(directory, "complete").writeText("ok", Charsets.UTF_8)
            } else {
                shouldRetry = true
                Log.e(TAG, "${fetcher.id.displayName}'s prefetch is incomplete; it will be resumed.")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not mark ${fetcher.id.displayName}'s edition as complete.", error)
            shouldRetry = true
        }
        checkNextFetcher(params)
    }

    private fun isBackgroundEditionComplete(
        fetcher: Paper,
        edition: NewspaperEdition,
        directory: File,
        marker: File
    ): Boolean = marker.isFile && isBackgroundContentReady(fetcher, edition, directory)

    private fun isBackgroundContentReady(
        fetcher: Paper,
        edition: NewspaperEdition,
        directory: File
    ): Boolean {
        if (edition.pages.any { !edition.hasPageImage(directory, it.id) }) return false
        if (edition.pages.any { it.id !in edition.processedPageIds }) return false
        val textIds = edition.hotspots.filter { it.type == NewspaperHotspotType.TEXT }
            .map { it.targetId }.toSet()
        if (textIds.any { id -> edition.articles.none { it.id == id } }) return false
        val imageIds = edition.hotspots.filter { it.type == NewspaperHotspotType.IMAGE }
            .map { it.targetId } + if (fetcher.supportsImageDetail) textIds else emptySet()
        return imageIds.all { edition.hasFullImage(directory, it) } &&
            edition.hasArticleImages(directory)
    }

    private fun skipBackgroundItem(params: JobParameters) {
        shouldRetry = true
        val fetcher = currentPaper ?: return
        val edition = backgroundEdition ?: run {
            checkNextFetcher(params)
            return
        }
        when {
            backgroundPageIndex < backgroundPages.size -> {
                backgroundPageIndex++
                loadNextBackgroundPage(params, fetcher, edition)
            }
            backgroundStoryIndex < backgroundStories.size -> {
                advanceBackgroundStory(params, fetcher, edition)
            }
            else -> checkNextFetcher(params)
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJob = null
        cleanupWebView()
        currentPaper = null
        papersToCheck = emptyList()
        checkDate = null
        backgroundEdition = null
        backgroundDirectory = null
        backgroundPages = emptyList()
        backgroundStories = emptyList()
        return true
    }

    private fun finish(params: JobParameters, retry: Boolean) {
        Handler(Looper.getMainLooper()).post {
            if (activeJob !== params) return@post
            activeJob = null
            cleanupWebView()
            currentPaper = null
            papersToCheck = emptyList()
            checkDate = null
            backgroundEdition = null
            backgroundDirectory = null
            backgroundPages = emptyList()
            backgroundStories = emptyList()
            jobFinished(params, retry)
        }
    }

    private fun cleanupWebView() {
        activeJangImageRequest?.let {
            finishJangImageRequest(it, "Jang image download was cancelled.")
        }
        webView?.stopLoading()
        webView?.destroy()
        webView = null
    }

    private fun notifyEditionAvailable(fetcher: Paper, edition: NewspaperEdition) {
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
            Papers.all.forEach { paper ->
                val registration = paper.backgroundJob
                if (scheduler.getPendingJob(registration.periodicJobId) != null) {
                    return@forEach
                }
                val job = JobInfo.Builder(
                    registration.nineAmJobId,
                    ComponentName(context, registration.serviceClass)
                )
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setMinimumLatency(delayMillis)
                    .setOverrideDeadline(delayMillis + 5 * 60 * 1000L)
                    .build()
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                    Log.e(
                        TAG,
                        "Could not schedule the 9 AM check for ${paper.id.displayName}."
                    )
                }
            }
        }

        fun schedule(context: Context, papers: Collection<Paper> = Papers.all) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            papers.forEach { paper ->
                val registration = paper.backgroundJob
                if (scheduler.getPendingJob(registration.periodicJobId) != null) {
                    return@forEach
                }
                val job = JobInfo.Builder(
                    registration.periodicJobId,
                    ComponentName(context, registration.serviceClass)
                )
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(INTERVAL_MILLIS)
                    .build()
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                    Log.e(TAG, "Could not schedule the periodic check for ${paper.id.displayName}.")
                }
            }
        }

        fun schedule(context: Context, paper: Paper) =
            schedule(context, listOf(paper))

        fun scheduleImmediately(context: Context, papers: Collection<Paper> = Papers.all) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            papers.forEach { paper ->
                val registration = paper.backgroundJob
                val jobId = registration.periodicJobId + 100
                val job = JobInfo.Builder(jobId, ComponentName(context, registration.serviceClass))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setMinimumLatency(0L)
                    .setOverrideDeadline(60_000L)
                    .build()
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                    Log.e(TAG, "Could not schedule the immediate check for ${paper.id.displayName}.")
                }
            }
        }
        private const val MAX_VERIFICATION_ATTEMPTS = 20
        private const val VERIFICATION_RETRY_MILLIS = 1_500L
        private const val MAX_JANG_IMAGE_VERIFICATION_ATTEMPTS = 60
        private const val MAX_JANG_IMAGE_REDIRECT_RETRIES = 3
        private const val JANG_IMAGE_VERIFICATION_RETRY_MILLIS = 1_000L
        private const val JANG_VERIFIED_PAGE_WAIT_MILLIS = 1_500L
        private const val MAX_JANG_PAGE_IMAGE_WAIT_ATTEMPTS = 15
        private const val JANG_IMAGE_TRANSFER_CHUNK_SIZE = 64 * 1024
        private const val JANG_IMAGE_TRANSFER_POLL_MILLIS = 25L
        private const val MAX_JANG_IMAGE_TRANSFER_ATTEMPTS = 1200
        private const val JANG_WEBVIEW_IMAGE_DOWNLOAD_TIMEOUT_SECONDS = 120L
        private const val MAX_JANG_WEBVIEW_IMAGE_BYTES = 20 * 1024 * 1024
    }
}

class DailyEditionCheckJobService : NewspaperEditionCheckJobService() {
    override val paperId = NewspaperId.DAWN
}

class JangEditionCheckJobService : NewspaperEditionCheckJobService() {
    override val paperId = NewspaperId.JANG_LAHORE
}

object DailyEditionCheckScheduler {
    fun schedule(context: Context, papers: Collection<Paper> = Papers.all) =
        NewspaperEditionCheckJobService.schedule(context, papers)

    fun schedule(context: Context, paper: Paper) =
        NewspaperEditionCheckJobService.schedule(context, paper)
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
