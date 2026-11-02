package com.faizan.epaper

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.ProgressDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.util.Base64
import android.view.ScaleGestureDetector
import android.view.View
import android.view.Gravity
import android.view.Menu
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private enum class JangImagePhase {
        FETCHING,
        VERIFYING_IMAGE,
        VERIFYING_PAGE
    }

    private class JangImageRequest(
        val url: String,
        val referer: String
    ) {
        val finished = CountDownLatch(1)
        var phase = JangImagePhase.FETCHING
        var verificationAttempts = 0
        var redirectAttempts = 0
        var pageImageAttempts = 0
        var transferAttempts = 0
        val imageBuffer = ByteArrayOutputStream()
        var imageData: ByteArray? = null
        var error: String? = null
    }

    private data class PaperHomeItem(
        val paper: Paper,
        val status: TextView,
        val offlinePercentage: TextView,
        val downloadIndicator: ProgressBar,
        val downloadAction: ImageButton
    )

    private data class OfflineEditionCache(
        val lastModified: Long,
        val fileLength: Long,
        val edition: NewspaperEdition
    )

    private lateinit var webView: WebView
    private lateinit var inspectionWebView: WebView
    private lateinit var onDemandWebView: WebView
    private lateinit var jangWebView: WebView
    private lateinit var jangImageWebView: WebView
    private lateinit var detailWebView: WebView
    private lateinit var pageLabel: TextView
    private lateinit var dateButton: Button
    private lateinit var homeScreen: LinearLayout
    private lateinit var readerScreen: LinearLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var pagePickerButton: Button
    private lateinit var pageActionRow: LinearLayout
    private lateinit var paperList: LinearLayout
    private lateinit var savedStoriesList: LinearLayout
    private lateinit var papersButton: Button
    private lateinit var previousPageButton: Button
    private lateinit var nextPageButton: Button
    private lateinit var saveStoryButton: Button
    private val paperViewer: PaperViewer = WebPaperViewer()
    private val papers = Papers.all
    private val paperHomeItems = linkedMapOf<NewspaperId, PaperHomeItem>()
    private val offlineEditionCache =
        mutableMapOf<Pair<NewspaperId, String>, OfflineEditionCache>()
    private val availabilityWebViews = linkedMapOf<NewspaperId, WebView>()
    private val availabilityCheckDates = mutableMapOf<NewspaperId, String>()
    private val availabilityAttempts = mutableMapOf<NewspaperId, Int>()
    private val unavailableEditions = mutableSetOf<Pair<NewspaperId, String>>()
    private val papersToOpenWhenAvailable = mutableSetOf<NewspaperId>()
    private val downloadExecutor = Executors.newFixedThreadPool(2)
    private val pageDownloadLock = Any()
    private val editionDownloadMonitor = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var editionResumeLatch = CountDownLatch(0)
    private val pageDownloadQueue = PriorityQueue<PageDownloadRequest>(
        compareBy<PageDownloadRequest> { it.priority }.thenBy { it.sequence }
    )
    private val queuedPageIds = mutableSetOf<String>()
    private var pageDownloadWorkerRunning = false
    private var pageDownloadSequence = 0L
    private var pageDownloadInProgressPaperId: NewspaperId? = null
    private var selectedDate = Calendar.getInstance()
    private var pageNumber = 1
    private var readerMode = false
    private var showingJangVerificationPage = false
    private var jangVerificationDate: String? = null
    private var jangVerificationTargetUrl: String? = null
    private var jangVerificationReloadScheduled = false
    private var jangVerificationRedirectAttempts = 0
    private var viewingStoryContent = false
    private var viewingSavedStory = false
    private var articleTextViewerActive = false
    private var articleTextScale = 1f
    private lateinit var articleScaleGestureDetector: ScaleGestureDetector
    private var displayedPageId: String? = null
    private var displayedEditionDate: String? = null
    private var displayedArticleId: String? = null
    private var viewingArticleImage = false
    private var paperPageReady = false
    private var shuttingDown = false
    private var availableEdition: NewspaperEdition? = null
    private var darkTheme = false
    @Volatile
    private var editionInProgress: NewspaperEdition? = null
    @Volatile
    private var editionDownloadPaused = false
    private var editionScanWaitingForResume = false
    private var activeEditionDirectory: File? = null
    private var editionPageIndex = 0
    private var textStoryIds: List<String> = emptyList()
    private var fullImageIds: List<String> = emptyList()
    private var collectedHotspots: List<NewspaperHotspot> = emptyList()
    private var collectedArticles: List<NewspaperArticle> = emptyList()
    private var processedPageIds: List<String> = emptyList()
    private var storyIndex = 0
    private var archivingStories = false
    private var processingContentId: String? = null
    private var offlineEdition: NewspaperEdition? = null
    private var offlineDirectory: File? = null
    private var jangVerificationKey: String? = null
    private var jangVerifiedKey: String? = null
    private var jangVerificationGeneration = 0
    private val jangImageTransferLock = Any()
    private var activeJangImageRequest: JangImageRequest? = null
    private var pageScanInProgress = false
    private var storyScanInProgress = false
    private var onDemandStoryId: String? = null
    private var onDemandImageId: String? = null
    private var onDemandEdition: NewspaperEdition? = null
    private var onDemandDirectory: File? = null
    private var onDemandPage: Pair<NewspaperEdition, NewspaperPage>? = null
    private var storyLoadingDialog: ProgressDialog? = null
    private var activeTextStory: TextStoryRequest? = null
    private var parsingJangStoryId: String? = null
    private val pendingTextStories = ArrayDeque<TextStoryRequest>()
    private val pageContentPrefetchKeys = mutableSetOf<String>()
    private val pendingPageContentPrefetches = ArrayDeque<PageContentPrefetch>()
    private var activePageContentPrefetchKey: String? = null
    private val prefetchingImageIds = mutableSetOf<String>()
    private val pendingArticleImageIds = mutableSetOf<String>()
    private var pageDownloadsRemaining = 0
    private var storyScanComplete = false
    private var pageDownloadFailed = false
    private val dateFormat = SimpleDateFormat("dd_MM_yyyy", Locale.US)
    private val savedStories = mutableListOf<SavedStory>()
    private val refreshHomeProgress = object : Runnable {
        override fun run() {
            if (shuttingDown) return
            if (::homeScreen.isInitialized && homeScreen.visibility == View.VISIBLE) {
                refreshDownloadIndicator()
                refreshScheduledAvailabilityStatuses()
                mainHandler.postDelayed(this, HOME_PROGRESS_REFRESH_MILLIS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkTheme = resources.getBoolean(R.bool.is_dark_theme)
        selectedDate.timeInMillis = savedInstanceState?.getLong(STATE_SELECTED_DATE)
            ?: selectedDate.timeInMillis
        pageNumber = savedInstanceState?.getInt(STATE_PAGE_NUMBER) ?: pageNumber
        articleTextScale = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getFloat(ARTICLE_TEXT_SCALE_KEY, DEFAULT_ARTICLE_TEXT_SCALE)
            .coerceIn(MIN_ARTICLE_TEXT_SCALE, MAX_ARTICLE_TEXT_SCALE)
        loadSavedStories()
        createReaderView()
        installArticleScaleGestureDetector()
        createPaperAvailabilityWebViews()
        DailyEditionCheckScheduler.schedule(this)
        NotificationPermission.requestIfNeeded(this)
        val savedPaperId = savedInstanceState?.getString(STATE_PAPER_ID)
            ?.let { value -> NewspaperId.entries.firstOrNull { it.storageName == value } }
        val shouldRestoreReader = savedInstanceState?.getBoolean(STATE_READER_MODE) == true
        val restoredEdition = if (shouldRestoreReader && savedPaperId != null) {
            val paper = Papers[savedPaperId]
            val metadata = File(
                paper.editionDirectory(File(filesDir, "editions"), selectedDateKey()),
                "edition.json"
            )
            if (metadata.isFile) runCatching { NewspaperEdition.readMetadata(metadata) }
                .getOrNull()
                ?.takeIf { it.paperId == savedPaperId && it.date == selectedDateKey() }
            else null
        } else null
        if (restoredEdition != null) openEdition(restoredEdition)
        refreshScheduledAvailabilityStatuses()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(refreshHomeProgress)
        mainHandler.post(refreshHomeProgress)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshHomeProgress)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_SELECTED_DATE, selectedDate.timeInMillis)
        outState.putInt(STATE_PAGE_NUMBER, pageNumber)
        outState.putBoolean(STATE_READER_MODE, readerMode)
        outState.putString(STATE_PAPER_ID, readerEdition()?.paperId?.storageName)
        super.onSaveInstanceState(outState)
    }

    private fun showStoryLoadingDialog() {
        storyLoadingDialog?.dismiss()
        storyLoadingDialog = ProgressDialog(this).apply {
            setMessage("Opening story…")
            setCancelable(true)
            setOnCancelListener {
                storyLoadingDialog = null
                onDemandStoryId = null
                onDemandImageId = null
                onDemandEdition = null
                onDemandDirectory = null
                returnToCurrentPaperPage()
            }
            show()
        }
    }

    private fun openPaper(paper: Paper) {
        checkPaperAvailability(paper, openWhenAvailable = true)
    }

    private fun createPaperAvailabilityWebViews() {
        papers.forEach { paper ->
                    val browser = WebView(this).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        visibility = View.INVISIBLE
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                val date = availabilityCheckDates[paper.id] ?: return
                                val expectedHost = Uri.parse(paper.availabilityUrl(date)).host
                                val actualHost = Uri.parse(url).host
                                if (paper.requiresPageVerification) {
                                    if (paper.isAvailabilityPageUrl(url, date)) {
                                        readPaperAvailability(paper, view, date)
                                    } else if (actualHost.equals(expectedHost, ignoreCase = true) &&
                                        Uri.parse(url).path == "/"
                                    ) {
                                        readPaperAvailability(paper, view, date)
                                    }
                                    return
                                }
                                if (paper.isAvailabilityPageUrl(url, date) ||
                                    actualHost == expectedHost || actualHost == "www.$expectedHost") {
                                    readPaperAvailability(paper, view, date)
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError
                            ) {
                                if (!request.isForMainFrame) return
                                if (paper.requiresPageVerification &&
                                    showingJangVerificationPage
                                ) return
                                val date = availabilityCheckDates[paper.id] ?: return
                                if (availabilityCheckDates[paper.id] != date) return
                                availabilityCheckDates.remove(paper.id)
                                papersToOpenWhenAvailable.remove(paper.id)
                                setPaperStatus(paper, "Could not check availability", Color.RED)
                                showMessage(
                                    "${paper.id.displayName}: ${error.description}"
                                )
                            }
                        }
                    }
                    availabilityWebViews[paper.id] = browser
                    webViewContainer.addView(
                        browser,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
        }

    private fun checkPaperAvailability(paper: Paper, openWhenAvailable: Boolean = false) {
                val requestedDate = selectedDateKey()
                val isToday = isToday(requestedDate)
                val directory = paper.editionDirectory(File(filesDir, "editions"), requestedDate)
                val metadata = File(directory, "edition.json")
                if (metadata.isFile) {
                    try {
                        val edition = NewspaperEdition.readMetadata(metadata)
                        require(edition.paperId == paper.id) {
                            "Saved ${paper.id.displayName} edition metadata is invalid."
                        }
                        if (isToday && requestedDate == selectedDateKey()) {
                            availableEdition = edition
                            setPaperStatus(paper, "Available", Color.rgb(30, 125, 70))
                        }
                        if (openWhenAvailable) openEdition(edition)
                        if (requestedDate == dateFormat.format(Calendar.getInstance().time) &&
                            paper.supportsForegroundEditionPrefetch &&
                            !File(directory, "complete").isFile
                        ) {
                            downloadEdition(edition)
                        }
                        return
                    } catch (error: Exception) {
                        showMessage(error.message ?: "Could not read the saved edition.")
                    }
                }
                if (isToday && (paper.id to requestedDate) in unavailableEditions) {
                    setPaperStatus(paper, "Not available", Color.GRAY)
                    if (openWhenAvailable) {
                        showMessage("${paper.id.displayName} is not available today.")
                    }
                    return
                }
                if (isToday) {
                    setPaperStatus(paper, "Waiting for scheduled check…", Color.DKGRAY)
                    if (openWhenAvailable) {
                        showMessage(
                            "${paper.id.displayName} will be available after the next scheduled check."
                        )
                    }
                    return
                }
                if (availabilityCheckDates[paper.id] == requestedDate) {
                    if (openWhenAvailable) papersToOpenWhenAvailable += paper.id
                    setPaperStatus(paper, "Checking availability…", Color.DKGRAY)
                    return
                }
                if (isToday && !NewspaperAvailability.checksAreAllowed(requestedDate)) {
                    papersToOpenWhenAvailable.remove(paper.id)
                    setPaperStatus(paper, "Not available", Color.GRAY)
                    if (openWhenAvailable) {
                        showMessage("${paper.id.displayName} is not available before 9:00 AM.")
                    }
                    return
                }
                availabilityCheckDates[paper.id] = requestedDate
                availabilityAttempts[paper.id] = 0
                if (openWhenAvailable) papersToOpenWhenAvailable += paper.id
                if (isToday) setPaperStatus(paper, "Checking availability…", Color.DKGRAY)
                val browser = availabilityWebViews[paper.id]
                    ?: throw IllegalStateException("No availability browser is registered for ${paper.id}.")
                browser.loadUrl(paper.availabilityUrl(requestedDate))
    }

    private fun readPaperAvailability(
        paper: Paper,
        browser: WebView,
        date: String,
        visibleJangCheck: Boolean = false
    ) {
                if (availabilityCheckDates[paper.id] != date) return
                browser.evaluateJavascript(paper.availabilityDiscoveryScript(date)) { result ->
                    if (availabilityCheckDates[paper.id] != date) return@evaluateJavascript
                    try {
                        val json = JSONTokener(result).nextValue() as? String
                            ?: throw IllegalStateException("Could not read availability data.")
                        val response = JSONObject(json)
                        val discoveryError = response.optString("error")
                        if (discoveryError == "VERIFYING" || discoveryError == "PENDING") {
                            if (discoveryError == "VERIFYING" &&
                                paper.requiresPageVerification && !visibleJangCheck
                            ) {
                                showJangVerificationPage(paper, date)
                                return@evaluateJavascript
                            }
                            val attempt = availabilityAttempts[paper.id] ?: 0
                            if (attempt < MAX_AVAILABILITY_ATTEMPTS) {
                                availabilityAttempts[paper.id] = attempt + 1
                                browser.postDelayed({
                                    if (availabilityCheckDates[paper.id] == date &&
                                        (!visibleJangCheck ||
                                            showingJangVerificationPage && browser === webView &&
                                            paper.isAvailabilityPageUrl(
                                                browser.url.orEmpty(),
                                                date
                                            ))
                                    ) {
                                        readPaperAvailability(
                                            paper,
                                            browser,
                                            date,
                                            visibleJangCheck
                                        )
                                    }
                                }, AVAILABILITY_RETRY_MILLIS)
                            } else {
                                failPaperAvailability(
                                    paper,
                                    date,
                                    if (discoveryError == "VERIFYING") {
                                        "${paper.id.displayName}'s site did not finish its verification."
                                    } else {
                                        "${paper.id.displayName}'s edition page did not load its page list."
                                    }
                                )
                            }
                            return@evaluateJavascript
                        }
                        when (discoveryError) {
                            "NOT_AVAILABLE", "NOT_PUBLISHED" -> {
                                if (paper.requiresPageVerification &&
                                    discoveryError != "NOT_PUBLISHED"
                                ) {
                                    if (!visibleJangCheck) {
                                        if (!showingJangVerificationPage) {
                                            showJangVerificationPage(paper, date)
                                        }
                                        return@evaluateJavascript
                                    }
                                    if (!showingJangVerificationPage || browser !== webView) {
                                        return@evaluateJavascript
                                    }
                                }
                                if (isToday(date)) {
                                    unavailableEditions += paper.id to date
                                }
                                availabilityCheckDates.remove(paper.id)
                                papersToOpenWhenAvailable.remove(paper.id)
                                closeJangVerificationPage()
                                if (isToday(date)) {
                                    setPaperStatus(paper, "Not available", Color.GRAY)
                                } else {
                                    showMessage(
                                        "Could not load ${paper.id.displayName} for ${formatDate(date)}."
                                    )
                                }
                                return@evaluateJavascript
                            }
                            "" -> Unit
                            else -> throw IllegalStateException(response.getString("error"))
                        }
                        val discovered = paper.parseAvailability(json)
                        require(discovered.paperId == paper.id && discovered.date == date) {
                            "The availability response belongs to a different edition."
                        }
                        val directory = paper.editionDirectory(File(filesDir, "editions"), date)
                        if (!directory.exists() && !directory.mkdirs()) {
                            throw IllegalStateException("Could not create edition storage.")
                        }
                        val metadata = File(directory, "edition.json")
                        val previous = if (metadata.isFile) {
                            runCatching { NewspaperEdition.readMetadata(metadata) }
                                .getOrNull()
                                ?.takeIf { it.paperId == paper.id && it.date == date }
                        } else {
                            null
                        }
                        val edition = if (previous == null) discovered else discovered.copy(
                            fullImageIds = previous.fullImageIds,
                            hotspots = previous.hotspots,
                            articles = previous.articles,
                            processedPageIds = previous.processedPageIds,
                            jangImageAreasVersion = previous.jangImageAreasVersion
                        )
                        edition.saveMetadata(directory)
                        offlineEditionCache.remove(paper.id to date)
                        availabilityCheckDates.remove(paper.id)
                        availabilityAttempts.remove(paper.id)
                        val shouldOpen = papersToOpenWhenAvailable.remove(paper.id) &&
                            date == selectedDateKey()
                        closeJangVerificationPage()
                        if (isToday(date)) {
                            setPaperStatus(paper, "Available", Color.rgb(30, 125, 70))
                        }
                        if (date == selectedDateKey()) {
                            availableEdition = edition
                            updatePagePicker(edition)
                            updatePageLabel()
                        }
                        if (date == dateFormat.format(Calendar.getInstance().time) &&
                            paper.supportsForegroundEditionPrefetch
                        ) {
                            downloadEdition(edition)
                        }
                        if (shouldOpen) openEdition(edition)
                    } catch (error: Exception) {
                        failPaperAvailability(
                            paper,
                            date,
                            error.message ?: "Could not check availability."
                        )
                    }
                }
    }

    private fun failPaperAvailability(paper: Paper, date: String, message: String) {
                if (availabilityCheckDates[paper.id] != date) return
                android.util.Log.e(
                    "MainActivity",
                    "Could not check ${paper.id.displayName} availability: $message"
                )
                availabilityCheckDates.remove(paper.id)
                availabilityAttempts.remove(paper.id)
                papersToOpenWhenAvailable.remove(paper.id)
                closeJangVerificationPage()
                setPaperStatus(paper, "Could not check availability", Color.RED)
                showMessage("${paper.id.displayName}: $message")
    }

    private fun showJangVerificationPage(paper: Paper, date: String) {
        if (showingJangVerificationPage) return
        showingJangVerificationPage = true
        jangVerificationDate = date
        val targetUrl = paper.pageUrl(date, "J${date}_001")
        jangVerificationTargetUrl = targetUrl
        jangVerificationReloadScheduled = false
        jangVerificationRedirectAttempts = 0
        homeScreen.visibility = View.GONE
        readerScreen.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
        pageLabel.text = "Verifying ${paper.id.displayName}…"
        previousPageButton.visibility = View.GONE
        pagePickerButton.visibility = View.GONE
        nextPageButton.visibility = View.GONE
        saveStoryButton.visibility = View.GONE
        pageActionRow.visibility = View.GONE
        webView.loadUrl(targetUrl)
    }

    private fun closeJangVerificationPage() {
        if (!showingJangVerificationPage) return
        showingJangVerificationPage = false
        jangVerificationDate = null
        jangVerificationTargetUrl = null
        jangVerificationReloadScheduled = false
        jangVerificationRedirectAttempts = 0
        webView.stopLoading()
        webView.visibility = View.GONE
        readerScreen.visibility = View.GONE
        homeScreen.visibility = View.VISIBLE
        pageLabel.text = ""
        previousPageButton.visibility = View.VISIBLE
        pagePickerButton.visibility = View.VISIBLE
        nextPageButton.visibility = View.VISIBLE
        pageActionRow.visibility = View.VISIBLE
        refreshSaveStoryButton()
        refreshDownloadIndicator()
        mainHandler.removeCallbacks(refreshHomeProgress)
        mainHandler.post(refreshHomeProgress)
    }

    private fun setPaperStatus(paper: Paper, text: String, color: Int) {
                val displayText = text.takeIf { isToday(selectedDateKey()) }.orEmpty()
                paperHomeItems[paper.id]?.status?.apply {
                    this.text = displayText
                    setTextColor(color)
                    visibility = if (displayText.isEmpty()) View.GONE else View.VISIBLE
                }
    }

    private fun refreshOfflineProgress() {
        val date = selectedDateKey()
        paperHomeItems.values.forEach { item ->
            val directory = item.paper.editionDirectory(File(filesDir, "editions"), date)
            val metadata = File(directory, "edition.json")
            val cacheKey = item.paper.id to date
            val savedEdition = if (!metadata.isFile) {
                offlineEditionCache.remove(cacheKey)
                availableEdition?.takeIf {
                    it.paperId == item.paper.id && it.date == date
                }
            } else {
                val cached = offlineEditionCache[cacheKey]
                if (cached != null &&
                    cached.lastModified == metadata.lastModified() &&
                    cached.fileLength == metadata.length()
                ) {
                    cached.edition
                } else try {
                    NewspaperEdition.readMetadata(metadata).also { edition ->
                        require(edition.paperId == item.paper.id && edition.date == date) {
                            "Saved ${item.paper.id.displayName} edition metadata is invalid."
                        }
                        offlineEditionCache[cacheKey] = OfflineEditionCache(
                            metadata.lastModified(),
                            metadata.length(),
                            edition
                        )
                    }
                } catch (error: Exception) {
                    offlineEditionCache.remove(cacheKey)
                    android.util.Log.e(
                        "MainActivity",
                        "Could not read ${item.paper.id.displayName}'s offline progress.",
                        error
                    )
                    null
                }
            }
            val percentage = savedEdition?.let { offlineCompletionPercentage(it, directory) }
            item.offlinePercentage.apply {
                if (percentage == null) {
                    text = ""
                    visibility = View.GONE
                } else {
                    text = if (percentage == 100) "✓" else "$percentage%"
                    textSize = if (percentage == 100) 20f else 14f
                    setTextColor(
                        if (percentage == 100) Color.rgb(30, 125, 70)
                        else primaryTextColor()
                    )
                    contentDescription = if (percentage == 100) {
                        "Download complete"
                    } else {
                        "$percentage% downloaded for offline reading"
                    }
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun offlineCompletionPercentage(edition: NewspaperEdition, directory: File): Int? {
        val pages = edition.readingOrder
        if (pages.isEmpty()) return null

        val paper = Papers.forEdition(edition)
        val articlesById = edition.articles.associateBy { it.id }
        var completedPages = 0
        var hasOfflineContent = false

        val pageCompletion = pages.sumOf { page ->
            val pageHotspots = edition.hotspots.filter { it.pageId == page.id }
            val storyIds = pageHotspots.asSequence()
                .filter { it.type == NewspaperHotspotType.TEXT }
                .map { it.targetId }
                .distinct()
                .toList()
            val imageIds = pageHotspots.asSequence()
                .filter { it.type == NewspaperHotspotType.IMAGE }
                .map { it.targetId }
                .distinct()
                .toList()
            val articleImages = if (paper.supportsInlineArticleImages) {
                storyIds.mapNotNull(articlesById::get)
                    .flatMap { article ->
                        article.imageUrls.indices.map { index -> article to index }
                    }
            } else {
                emptyList()
            }
            val contentCount = storyIds.size + imageIds.size + articleImages.size
            val totalPageItems = 1 + contentCount
            val pageImageSaved = edition.hasPageImage(directory, page.id)
            val pageScanned = page.id in edition.processedPageIds
            val pageComplete = pageImageSaved && pageScanned
            if (pageImageSaved) hasOfflineContent = true
            if (pageComplete) completedPages++

            val completedContent = storyIds.count { it in articlesById } +
                imageIds.count { edition.hasFullImage(directory, it) } +
                articleImages.count { (article, index) ->
                    edition.hasArticleImage(directory, article, index)
                }
            completedContent.toDouble().plus(if (pageComplete) 1.0 else 0.0) /
                totalPageItems
        }
        if (!hasOfflineContent) return null

        val completionMarker = File(directory, "complete").isFile
        return if (completedPages == pages.size && completionMarker) {
            100
        } else {
            (pageCompletion * 100 / pages.size).toInt().coerceAtMost(99)
        }
    }

    private fun toggleEditionDownload(paperId: NewspaperId) {
        val edition = editionInProgress ?: return
        if (edition.paperId != paperId || edition.date != selectedDateKey() ||
            !isToday(edition.date)
        ) return
        if (editionDownloadPaused) {
            synchronized(editionDownloadMonitor) {
                editionDownloadPaused = false
                editionResumeLatch.countDown()
            }
            synchronized(pageDownloadLock) {
                startPageDownloadWorkerLocked()
            }
            if (editionScanWaitingForResume) {
                editionScanWaitingForResume = false
                loadNextEditionPage()
            }
            startNextTextStory()
        } else {
            synchronized(editionDownloadMonitor) {
                editionResumeLatch = CountDownLatch(1)
                editionDownloadPaused = true
            }
        }
        refreshDownloadIndicator()
    }

    private fun clearEditionDownloadPause() {
        synchronized(editionDownloadMonitor) {
            editionDownloadPaused = false
            editionResumeLatch.countDown()
        }
        editionScanWaitingForResume = false
    }

    private fun awaitEditionDownloadResume(edition: NewspaperEdition) {
        while (true) {
            val latch = synchronized(editionDownloadMonitor) {
                if (editionDownloadPaused && editionInProgress === edition) {
                    editionResumeLatch
                } else {
                    null
                }
            } ?: return
            try {
                latch.await()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Edition download was interrupted.", error)
            }
        }
    }

    private fun refreshScheduledAvailabilityStatuses() {
        val date = selectedDateKey()
        val today = isToday(date)
        availableEdition = null
        papers.forEach { paper ->
            val metadata = File(
                paper.editionDirectory(File(filesDir, "editions"), date),
                "edition.json"
            )
            if (metadata.isFile) {
                try {
                    val edition = NewspaperEdition.readMetadata(metadata)
                    require(edition.paperId == paper.id && edition.date == date) {
                        "Saved ${paper.id.displayName} edition metadata is invalid."
                    }
                    if (today) {
                        availableEdition = edition
                        setPaperStatus(paper, "Available", Color.rgb(30, 125, 70))
                    } else {
                        setPaperStatus(paper, "", secondaryTextColor())
                    }
                } catch (error: Exception) {
                    android.util.Log.e(
                        "MainActivity",
                        "Could not read ${paper.id.displayName}'s saved availability.",
                        error
                    )
                    setPaperStatus(paper, "Could not read saved edition", Color.RED)
                }
            } else if (today) {
                val status = if (NewspaperAvailability.checksAreAllowed(date)) {
                    "Waiting for scheduled check…"
                } else {
                    "Not available"
                }
                setPaperStatus(paper, status, Color.GRAY)
            } else {
                setPaperStatus(paper, "", secondaryTextColor())
            }
        }
    }

    private fun createReaderView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(homeBackgroundColor())
        }
        homeScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(homeBackgroundColor())
        }
        dateButton = button("") { chooseDate() }
        dateButton.apply {
            textSize = 16f
            isAllCaps = false
            minHeight = dp(52)
            setPadding(dp(18), 0, dp(18), 0)
            background = roundedBackground(
                if (darkTheme) Color.rgb(45, 99, 137) else Color.rgb(31, 83, 117),
                dp(16)
            )
            backgroundTintList = null
            setTextColor(Color.WHITE)
            elevation = dp(2).toFloat()
        }
        val homeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }
        val title = TextView(this).apply {
            text = "Newspapers"
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(primaryTextColor())
            setPadding(0, 0, 0, dp(20))
        }
        pageLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(secondaryTextColor())
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = roundedBackground(surfaceColor(), dp(12))
        }
        paperList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val savedStoriesTitle = TextView(this).apply {
            text = "Saved Stories"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(primaryTextColor())
            setPadding(0, dp(28), 0, dp(12))
        }
        savedStoriesList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        papers.forEach { paper ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(16), dp(16))
                background = roundedBackground(surfaceColor(), dp(16))
                elevation = dp(2).toFloat()
                minimumHeight = dp(92)
                isClickable = true
                isFocusable = true
                setOnClickListener { openPaper(paper) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val name = TextView(this).apply {
                text = paper.id.displayName
                textSize = 21f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(primaryTextColor())
            }
            val status = TextView(this).apply {
                text = if (isToday(selectedDateKey())) {
                    if (NewspaperAvailability.checksAreAllowed(selectedDateKey())) {
                        "Waiting for scheduled check…"
                    } else {
                        "Not available"
                    }
                } else {
                    ""
                }
                textSize = 13f
                gravity = Gravity.CENTER
                maxWidth = dp(160)
                setTextColor(secondaryTextColor())
                setPadding(dp(10), dp(5), dp(10), dp(5))
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                background = roundedBackground(
                    if (darkTheme) Color.rgb(54, 54, 54) else Color.rgb(241, 245, 248),
                    dp(10)
                )
            }
            val offlinePercentage = TextView(this).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = dp(48)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setTextColor(primaryTextColor())
                background = roundedBackground(
                    if (darkTheme) Color.rgb(54, 54, 54) else Color.rgb(241, 245, 248),
                    dp(10)
                )
                visibility = View.GONE
            }
            val trailingLabels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(status)
                addView(offlinePercentage, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                })
            }
            val downloadIndicator = ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleSmall
            ).apply {
                visibility = View.GONE
                isIndeterminate = true
            }
            val downloadAction = ImageButton(this).apply {
                visibility = View.GONE
                contentDescription = "Pause edition download"
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { toggleEditionDownload(paper.id) }
            }
            info.addView(name)
            row.addView(
                info,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(downloadIndicator, LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(downloadAction, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(trailingLabels, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowParams.bottomMargin = dp(12)
            paperList.addView(row, rowParams)
            paperHomeItems[paper.id] = PaperHomeItem(
                paper,
                status,
                offlinePercentage,
                downloadIndicator,
                downloadAction
            )
        }

        homeContent.addView(title)
        homeContent.addView(dateButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(20)
        })
        homeContent.addView(paperList)
        homeContent.addView(savedStoriesTitle)
        homeContent.addView(savedStoriesList)
        homeScreen.addView(
            ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                val maxContentWidth = minOf(
                    (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() - 32,
                    760
                ).coerceAtLeast(280)
                addView(
                    homeContent,
                    FrameLayout.LayoutParams(
                        dp(maxContentWidth),
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    )
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        updateSavedStoriesList()
        readerScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(homeBackgroundColor())
        }
        val pageControls = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = roundedBackground(surfaceColor(), dp(16))
        elevation = dp(2).toFloat()
        }
        pageActionRow = pageControls
        val compactReaderControls = resources.configuration.screenWidthDp < 600
        papersButton = button(
        if (compactReaderControls) "Back" else "Papers",
        android.R.drawable.ic_menu_agenda
        ) {
        onReaderBackButton()
        }
        previousPageButton = button(
        if (compactReaderControls) "Prev" else "Previous",
        android.R.drawable.ic_media_previous
        ) {
        val pages = readerEdition()?.readingOrder.orEmpty()
        val index = pages.indexOfFirst { it.id.endsWith("_${pageNumber.toString().padStart(3, '0')}") }
        if (index > 0) openReaderPage(pages[index - 1])
        }
        pagePickerButton = button(
        "Pages",
        android.R.drawable.arrow_down_float
        ) {
        showPagePicker()
        }.apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        contentDescription = "Choose a newspaper page"
        val arrow = getDrawable(android.R.drawable.arrow_down_float)?.mutate()?.apply {
            setTint(primaryTextColor())
        }
        setCompoundDrawablesWithIntrinsicBounds(null, null, arrow, null)
        }
        nextPageButton = button("Next", android.R.drawable.ic_media_next) {
        val pages = readerEdition()?.readingOrder.orEmpty()
        val index = pages.indexOfFirst { it.id.endsWith("_${pageNumber.toString().padStart(3, '0')}") }
        if (index >= 0 && index < pages.lastIndex) openReaderPage(pages[index + 1])
        }
        saveStoryButton = button(
        if (compactReaderControls) "Save" else "Save story",
        android.R.drawable.ic_menu_save
        ) {
        saveCurrentStory()
        }
        papersButton.contentDescription = "Return to newspapers"
        previousPageButton.contentDescription = "Previous page"
        nextPageButton.contentDescription = "Next page"
        saveStoryButton.contentDescription = "Save story"
        pageControls.addView(papersButton)
        pageControls.addView(previousPageButton)
        pageControls.addView(pagePickerButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        pageControls.addView(nextPageButton)
        pageControls.addView(saveStoryButton)
        pageLabel.setPadding(dp(16), dp(12), dp(16), dp(12))
        readerScreen.addView(pageLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(12), dp(12), dp(12), dp(8))
        })
        readerScreen.addView(pageControls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val uri = request.url
                    val assetPaper = Papers.all.firstOrNull {
                        Uri.parse(it.offlineOrigin).host == uri.host
                    }
                    if (assetPaper?.supportsInlineArticleImages == true &&
                        uri.pathSegments.firstOrNull() == "article-asset"
                    ) {
                        val parts = uri.pathSegments
                        val edition = offlineEdition
                        val directory = offlineDirectory
                        if (parts.size == 3 && edition?.paperId == assetPaper.id &&
                            directory != null
                        ) {
                            val article = edition.articles.firstOrNull { it.id == parts[1] }
                            val index = parts[2].toIntOrNull()
                            val image = if (article != null && index != null) {
                                edition.articleImageFile(directory, article, index)
                            } else {
                                null
                            }
                            if (image?.isFile == true) {
                                val contentType = if (image.extension.equals("png", true)) {
                                    "image/png"
                                } else {
                                    "image/jpeg"
                                }
                                return WebResourceResponse(
                                    contentType,
                                    null,
                                    FileInputStream(image)
                                )
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (showingJangVerificationPage) {
                        val date = jangVerificationDate ?: return
                        val paper = Papers[NewspaperId.JANG_LAHORE]
                        if (!Uri.parse(url).host.equals("e.jang.com.pk", ignoreCase = true)) {
                            return
                        }
                        if (paper.isAvailabilityPageUrl(url, date)) {
                            readPaperAvailability(paper, view, date, visibleJangCheck = true)
                        } else if (Uri.parse(url).path == "/" &&
                            !jangVerificationReloadScheduled
                        ) {
                            readPaperAvailability(paper, view, date, visibleJangCheck = true)
                            if (jangVerificationRedirectAttempts >= MAX_AVAILABILITY_ATTEMPTS) {
                                failPaperAvailability(
                                    paper,
                                    date,
                                    "Jang kept redirecting away from the requested edition page."
                                )
                                return
                            }
                            jangVerificationRedirectAttempts++
                            jangVerificationReloadScheduled = true
                            view.postDelayed({
                                if (showingJangVerificationPage &&
                                    jangVerificationDate == date
                                ) {
                                    jangVerificationReloadScheduled = false
                                    jangVerificationTargetUrl?.let(view::loadUrl)
                                }
                            }, AVAILABILITY_RETRY_MILLIS)
                        }
                        return
                    }
                    if (displayedPageId != null && !viewingStoryContent) {
                        paperPageReady = true
                        updateRenderedHotspots()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    if (showingJangVerificationPage &&
                        request.url.host.equals("e.jang.com.pk", ignoreCase = true)
                    ) return false
                    if (Papers.all.any { Uri.parse(it.offlineOrigin).host == request.url.host }) {
                        openOfflineTarget(request.url)
                        return true
                    }
                    return request.url.scheme !in setOf("file", "data", "about")
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (showingJangVerificationPage && request.isForMainFrame) {
                        val date = jangVerificationDate ?: return
                        failPaperAvailability(
                            Papers[NewspaperId.JANG_LAHORE],
                            date,
                            "Could not load Jang's page: ${error.description}"
                        )
                    }
                }
            }
        }
        inspectionWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        inspectionWebView.webViewClient = inspectionClient()
        onDemandWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        onDemandWebView.webViewClient = onDemandPageClient()
        jangWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        jangWebView.webViewClient = jangInspectionClient()
        jangImageWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        jangImageWebView.webViewClient = jangImageClient()
        detailWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        detailWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val request = activeTextStory ?: return
                val uri = Uri.parse(url)
                if (Papers.forEdition(request.edition).requiresPageVerification) {
                    if (uri.host != "e.jang.com.pk" ||
                        uri.path != "/detail/${request.id}" ||
                        parsingJangStoryId == request.id
                    ) return
                    parsingJangStoryId = request.id
                    parseJangStoryText(view, request.id) { article ->
                        parsingJangStoryId = null
                        activeTextStory = null
                        saveStoryArticle(request, article)
                        finishTextStoryRequest(request)
                        if (onDemandStoryId == request.id) {
                            onDemandStoryId = null
                            onDemandEdition = null
                            onDemandDirectory = null
                            storyLoadingDialog?.dismiss()
                            storyLoadingDialog = null
                            if (readerMode && selectedDateKey() == request.edition.date &&
                                viewingStoryContent
                            ) {
                                webView.loadDataWithBaseURL(
                                    Papers.forEdition(request.edition).offlineOrigin,
                                    renderArticleHtml(
                                        request.edition,
                                        article,
                                        request.directory
                                    ),
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        }
                        startNextTextStory()
                        refreshDownloadIndicator()
                    }
                    return
                }
                if (uri.host != "epaper.dawn.com" || uri.path != "/DetailImage.php") return
                if (uri.getQueryParameter("StoryImage") != request.id) return
                parseStoryText(view, request.id) { article ->
                    activeTextStory = null
                    saveStoryArticle(request, article)
                    finishTextStoryRequest(request)
                    if (onDemandStoryId == request.id) {
                        onDemandStoryId = null
                        onDemandEdition = null
                        onDemandDirectory = null
                        storyLoadingDialog?.dismiss()
                        storyLoadingDialog = null
                        if (readerMode && selectedDateKey() == request.edition.date &&
                            viewingStoryContent
                        ) {
                            webView.loadDataWithBaseURL(
                                Papers.forEdition(request.edition).offlineOrigin,
                                renderArticleHtml(
                                    request.edition,
                                    article,
                                    request.directory
                                ),
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    }
                    startNextTextStory()
                    refreshDownloadIndicator()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (!request.isForMainFrame) return
                val request = activeTextStory ?: return
                activeTextStory = null
                parsingJangStoryId = null
                finishTextStoryRequest(request)
                if (onDemandStoryId == request.id) {
                    onDemandStoryId = null
                    onDemandEdition = null
                    onDemandDirectory = null
                    storyLoadingDialog?.dismiss()
                    storyLoadingDialog = null
                    showMessage("Could not download story ${request.id}: ${error.description}")
                    if (readerMode) returnToCurrentPaperPage()
                } else {
                    showMessage("Could not prefetch story ${request.id}: ${error.description}")
                }
                startNextTextStory()
                refreshDownloadIndicator()
            }
        }

        webViewContainer = FrameLayout(this)
        webViewContainer.addView(
            inspectionWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webViewContainer.addView(
            detailWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webViewContainer.addView(
            onDemandWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webViewContainer.addView(
            jangWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webViewContainer.addView(
            jangImageWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webViewContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webView.visibility = View.GONE
        readerScreen.addView(webViewContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.addView(homeScreen, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        readerScreen.visibility = View.GONE
        root.addView(readerScreen, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        setArticleTextViewerMode(false)
        pageLabel.text = ""
        updateDateButton()
        updatePageLabel()
        refreshOfflineProgress()
    }

    private fun installArticleScaleGestureDetector() {
        articleScaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!articleTextViewerActive) return false
                    val updatedScale = (articleTextScale * detector.scaleFactor)
                        .coerceIn(MIN_ARTICLE_TEXT_SCALE, MAX_ARTICLE_TEXT_SCALE)
                    if (updatedScale == articleTextScale) return true
                    articleTextScale = updatedScale
                    getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                        .edit()
                        .putFloat(ARTICLE_TEXT_SCALE_KEY, articleTextScale)
                        .apply()
                    val scale = String.format(Locale.US, "%.3f", articleTextScale)
                    webView.evaluateJavascript(
                        "document.documentElement.style.setProperty('--article-font-scale', '$scale')",
                        null
                    )
                    return true
                }
            }
        )
        webView.setOnTouchListener { _, event ->
            if (articleTextViewerActive) articleScaleGestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun setArticleTextViewerMode(enabled: Boolean) {
        articleTextViewerActive = enabled
        webView.settings.setSupportZoom(!enabled)
        webView.settings.builtInZoomControls = !enabled
        webView.settings.displayZoomControls = false
        papersButton.text = if (enabled) "Back" else "Papers"
        setButtonIcon(
            papersButton,
            if (enabled) android.R.drawable.ic_media_previous
            else android.R.drawable.ic_menu_agenda
        )
        pageLabel.visibility = if (enabled) View.GONE else View.VISIBLE
        previousPageButton.visibility = if (enabled) View.GONE else View.VISIBLE
        pagePickerButton.visibility = if (enabled) View.INVISIBLE else View.VISIBLE
        nextPageButton.visibility = if (enabled) View.GONE else View.VISIBLE
        saveStoryButton.visibility = if (enabled) View.VISIBLE else View.GONE
        refreshSaveStoryButton()
    }

    private fun onReaderBackButton() {
        if (!articleTextViewerActive) {
            showHome()
        } else if (viewingSavedStory) {
            showHome()
        } else {
            returnToCurrentPaperPage()
        }
    }

    private fun renderArticleHtml(
        edition: NewspaperEdition,
        article: NewspaperArticle,
        directory: File?
    ): String {
        setArticleTextViewerMode(true)
        return paperViewer.renderArticle(
            edition,
            article,
            Papers.forEdition(edition),
            directory,
            articleTextScale,
            darkTheme
        )
    }

    private fun inspectionClient() = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val uri = Uri.parse(url)
            val edition = editionInProgress ?: return
            if (pageScanInProgress) {
                val page = edition.readingOrder.getOrNull(editionPageIndex) ?: return
                if (Papers.forEdition(edition).isPageUrl(url, edition.date, page.id) &&
                    processingContentId != page.id
                ) {
                    processingContentId = page.id
                    view.postDelayed({
                        if (editionInProgress === edition && pageScanInProgress) {
                            collectPageHotspots(edition, page)
                        }
                    }, 250)
                }
                return
            }
            if (storyScanInProgress && uri.host == "epaper.dawn.com") {
                val storyId = textStoryIds.getOrNull(storyIndex) ?: return
                if (uri.path == "/DetailImage.php" &&
                    uri.getQueryParameter("StoryImage") == storyId &&
                    processingContentId != storyId
                ) {
                    processingContentId = storyId
                    view.postDelayed({
                        if (editionInProgress === edition && storyScanInProgress) {
                            collectStoryText(edition, storyId)
                        }
                    }, 250)
                }
            }
        }
    }

    private fun onDemandPageClient() = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val requestedPage = onDemandPage ?: return
            val (edition, page) = requestedPage
            val paper = Papers.forEdition(edition)
            if (paper.requiresPageVerification ||
                !paper.isPageUrl(url, edition.date, page.id)
            ) return
            view.postDelayed({
                if (isSameOnDemandPage(onDemandPage, requestedPage)) {
                    collectOnDemandPageHotspots(edition, page)
                }
            }, 250)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            if (!request.isForMainFrame) return
            val requestedPage = onDemandPage ?: return
            val (edition, page) = requestedPage
            val paper = Papers.forEdition(edition)
            if (paper.requiresPageVerification ||
                !paper.isPageUrl(request.url.toString(), edition.date, page.id)
            ) return
            onDemandPage = null
            showMessage("Could not load page details for ${page.title}: ${error.description}")
        }
    }

    private fun jangInspectionClient() = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val requestedPage = onDemandPage
            if (requestedPage != null &&
                Papers.forEdition(requestedPage.first).requiresPageVerification &&
                Papers.forEdition(requestedPage.first).isPageUrl(
                    url,
                    requestedPage.first.date,
                    requestedPage.second.id
                )
            ) {
                val requestedPageUrl = Papers.forEdition(requestedPage.first)
                    .pageUrl(requestedPage.first.date, requestedPage.second.id)
                awaitJangVerification(requestedPageUrl, requestedPage) {
                    collectOnDemandPageHotspots(requestedPage.first, requestedPage.second)
                }
            }
        }

        private fun awaitJangVerification(
            expectedUrl: String,
            requestedPage: Pair<NewspaperEdition, NewspaperPage>?,
            attempt: Int = 0,
            onReady: () -> Unit
        ) {
            if (requestedPage != null && !isSameOnDemandPage(onDemandPage, requestedPage)) return
            val key = expectedUrl
            if (attempt == 0) {
                if (jangVerifiedKey == key || jangVerificationKey == key) return
                jangVerificationKey = key
                jangVerificationGeneration++
            }
            val generation = jangVerificationGeneration
            val script = """
                    (() => {
                        const title = document.title.toLowerCase();
                        const text = (document.body?.innerText || '').slice(0, 4000).toLowerCase();
                        const challenge = title.includes('moment, please') ||
                            title.includes('just a moment') ||
                            text.includes('verify you are human') ||
                            text.includes('checking your browser') ||
                            text.includes('security verification') ||
                            text.includes('performing security verification') ||
                            text.includes('request is being verified') ||
                            document.querySelector('#challenge-running, #challenge-stage');
                        return JSON.stringify({
                            challenge: Boolean(challenge),
                            url: location.href,
                            expected: location.pathname === '${Uri.parse(expectedUrl).path}'
                        });
                    })()
            """.trimIndent()
            jangWebView.evaluateJavascript(script) { result ->
                if (generation != jangVerificationGeneration) return@evaluateJavascript
                if (requestedPage != null &&
                    !isSameOnDemandPage(onDemandPage, requestedPage)
                ) return@evaluateJavascript
                try {
                    val json = JSONTokener(result).nextValue() as? String
                        ?: throw IllegalStateException("Could not check Jang's verification page.")
                    val state = JSONObject(json)
                    if (state.optBoolean("challenge") || !state.optBoolean("expected")) {
                        if (attempt < 60) {
                            jangWebView.postDelayed({
                                awaitJangVerification(
                                    expectedUrl,
                                    requestedPage,
                                    attempt + 1,
                                    onReady
                                )
                            }, 1_000)
                        } else {
                            jangVerificationKey = null
                            onDemandPage = null
                            showMessage("Jang's request verification did not finish in time.")
                        }
                        return@evaluateJavascript
                    }
                    jangVerificationKey = null
                    jangVerifiedKey = key
                    jangWebView.postDelayed(onReady, 300)
                } catch (error: Exception) {
                    jangVerificationKey = null
                    jangVerifiedKey = null
                    onDemandPage = null
                    showMessage(error.message ?: "Could not finish Jang verification.")
                }
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            if (!request.isForMainFrame) return
            if (request.url.path?.contains("/cdn-cgi/") == true) return
            val requestedPage = onDemandPage ?: return
            val (edition, page) = requestedPage
            val paper = Papers.forEdition(edition)
            if (!paper.requiresPageVerification ||
                !paper.isPageUrl(request.url.toString(), edition.date, page.id)
            ) return
            onDemandPage = null
            jangVerificationGeneration++
            jangVerificationKey = null
            showMessage("Could not load ${page.title} details: ${error.description}")
        }
    }

    private fun jangImageClient() = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val request = activeJangImageRequest ?: return
            val uri = Uri.parse(url)
            if (!uri.host.equals("e.jang.com.pk", ignoreCase = true)) return
            if (request.phase != JangImagePhase.VERIFYING_PAGE) {
                request.phase = JangImagePhase.VERIFYING_IMAGE
            }
            inspectJangImageDocument(request)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            if (!request.isForMainFrame) return
            val activeRequest = activeJangImageRequest ?: return
            finishJangImageRequest(
                activeRequest,
                error = "Could not load Jang verification request: ${error.description} (${request.url})."
            )
        }
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
        jangImageWebView.evaluateJavascript(script) { result ->
            if (activeJangImageRequest !== request) return@evaluateJavascript
            try {
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not inspect Jang image response.")
                val state = JSONObject(json)
                if (state.optBoolean("challenge")) {
                    if (request.verificationAttempts >= MAX_JANG_IMAGE_VERIFICATION_ATTEMPTS) {
                        finishJangImageRequest(
                            request,
                            error = "Jang image verification timed out at ${state.optString("url")}."
                        )
                    } else {
                        request.verificationAttempts++
                        jangImageWebView.postDelayed(
                            { inspectJangImageDocument(request) },
                            JANG_IMAGE_VERIFICATION_RETRY_MILLIS
                        )
                    }
                    return@evaluateJavascript
                }
                if (request.phase == JangImagePhase.VERIFYING_PAGE) {
                    if (state.optBoolean("pageImageReady")) {
                        if (Uri.parse(request.url).path?.contains("/mainpage/page") == true) {
                            extractJangImageFromWebView(request, usePageScanImage = true)
                        } else {
                            request.phase = JangImagePhase.FETCHING
                            jangImageWebView.loadUrl(
                                request.url,
                                mapOf("Referer" to request.referer, "Accept" to "*/*")
                            )
                        }
                        return@evaluateJavascript
                    }
                    if (request.pageImageAttempts++ < MAX_JANG_PAGE_IMAGE_WAIT_ATTEMPTS) {
                        jangImageWebView.postDelayed(
                            { inspectJangImageDocument(request) },
                            JANG_VERIFIED_PAGE_WAIT_MILLIS
                        )
                    } else {
                        request.phase = JangImagePhase.FETCHING
                        jangImageWebView.loadUrl(
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
                    jangImageWebView.loadUrl(request.referer)
                    return@evaluateJavascript
                }
                if (!state.optBoolean("expected") || !state.optBoolean("imageReady")) {
                    finishJangImageRequest(
                        request,
                        error = "Jang did not return the requested image.\n" +
                            "Requested URL: ${request.url}\n" +
                            "Final URL: ${state.optString("url")}\n" +
                            "Response page: ${state.optString("message")}"
                    )
                    return@evaluateJavascript
                }
                extractJangImageFromWebView(request)
            } catch (error: Exception) {
                finishJangImageRequest(
                    request,
                    error = error.message ?: "Could not inspect Jang image response."
                )
            }
        }
    }

    private fun extractJangImageFromWebView(
        request: JangImageRequest,
        usePageScanImage: Boolean = false
    ) {
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
                    const type = ${if (usePageScanImage) "'image/jpeg'" else "image.currentSrc.toLowerCase().split('?')[0].endsWith('.png') ? 'image/png' : 'image/jpeg'"};
                    window.__epaperImageTransferData =
                        canvas.toDataURL(type, 1).split(',')[1];
                    window.__epaperImageTransferOffset = 0;
                } catch (error) {
                    return JSON.stringify({error: String(error)});
                }
                return 'started';
            })()
        """.trimIndent()
        jangImageWebView.evaluateJavascript(script) { result ->
            if (activeJangImageRequest !== request) return@evaluateJavascript
            try {
                val value = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not start the verified Jang image transfer.")
                if (value != "started") {
                    val payload = JSONObject(value)
                    finishJangImageRequest(
                        request,
                        error = payload.optString("error", "Could not encode the verified Jang image.")
                    )
                    return@evaluateJavascript
                }
                readJangImageTransfer(request)
            } catch (error: Exception) {
                finishJangImageRequest(
                    request,
                    error = error.message ?: "Could not start the verified Jang image transfer."
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
                return JSON.stringify({
                    chunk: chunk,
                    done: end >= data.length
                });
            })()
        """.trimIndent()
        jangImageWebView.evaluateJavascript(script) { result ->
            if (activeJangImageRequest !== request) return@evaluateJavascript
            try {
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read the verified Jang image.")
                val payload = JSONObject(json)
                val error = payload.optString("error").takeIf { it.isNotBlank() && it != "null" }
                if (error != null) {
                    finishJangImageRequest(request, error = error)
                    return@evaluateJavascript
                }
                val chunk = payload.optString("chunk")
                if (chunk.isNotEmpty()) {
                    request.imageBuffer.write(Base64.decode(chunk, Base64.DEFAULT))
                    if (request.imageBuffer.size() > MAX_JANG_WEBVIEW_IMAGE_BYTES) {
                        finishJangImageRequest(request, error = "Jang image is larger than expected.")
                        return@evaluateJavascript
                    }
                }
                if (payload.optBoolean("done")) {
                    request.imageData = request.imageBuffer.toByteArray()
                    finishJangImageRequest(request)
                } else if (request.transferAttempts++ < MAX_JANG_IMAGE_TRANSFER_ATTEMPTS) {
                    jangImageWebView.postDelayed(
                        { readJangImageTransfer(request) },
                        JANG_IMAGE_TRANSFER_POLL_MILLIS
                    )
                } else {
                    finishJangImageRequest(
                        request,
                        error = "Timed out reading the verified Jang image data."
                    )
                }
            } catch (error: Exception) {
                finishJangImageRequest(
                    request,
                    error = error.message ?: "Could not read the verified Jang image."
                )
            }
        }
    }

    private fun finishJangImageRequest(
        request: JangImageRequest,
        error: String? = null
    ) {
        if (activeJangImageRequest !== request) return
        request.error = error
        activeJangImageRequest = null
        request.finished.countDown()
    }

    private fun downloadJangImageThroughWebView(
        url: java.net.URL,
        referer: String,
        destination: File
    ) {
        require(url.protocol == "https" &&
            url.host.equals("e.jang.com.pk", ignoreCase = true) &&
            (url.path.startsWith("/static_pages/") ||
                url.path.startsWith("/assets/uploads/"))
        ) { "Invalid Jang static image URL." }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Jang image downloads must run off the main thread."
        }
        synchronized(jangImageTransferLock) {
            val request = JangImageRequest(url.toString(), referer)
            runOnUiThread {
                if (shuttingDown || isDestroyed) {
                    finishJangImageRequest(request, error = "Jang image download was cancelled.")
                } else {
                    activeJangImageRequest = request
                    jangImageWebView.loadUrl(
                        request.url,
                        mapOf(
                            "Referer" to request.referer,
                            "Accept" to "*/*"
                        )
                    )
                }
            }
            if (!request.finished.await(JANG_IMAGE_DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                runOnUiThread {
                    finishJangImageRequest(request, error = "Jang image download timed out.")
                    jangImageWebView.stopLoading()
                }
                throw IllegalStateException("Jang image download timed out for $url.")
            }
            request.error?.let { throw IllegalStateException(it) }
            val imageBytes = request.imageData
                ?: throw IllegalStateException("Jang returned no image data for $url.")
            destination.outputStream().use { it.write(imageBytes) }
        }
    }

    private fun downloadEditionPageImage(
        edition: NewspaperEdition,
        directory: File,
        pageId: String
    ) {
        edition.downloadPageImage(directory, pageId) { url, referer, destination ->
            downloadJangImageThroughWebView(url, referer, destination)
        }
    }

    private fun downloadEditionFullImages(
        edition: NewspaperEdition,
        directory: File,
        ids: List<String>
    ) {
        edition.downloadFullImages(directory, ids, { _, _ -> }) { url, referer, destination ->
            downloadJangImageThroughWebView(url, referer, destination)
        }
    }
    private fun loadPage() {
        updatePageLabel()
        val edition = readerEdition() ?: return
        val page = edition.readingOrder.firstOrNull {
            it.id.takeLast(3).toIntOrNull() == pageNumber
        } ?: edition.readingOrder.firstOrNull() ?: return
        openReaderPage(page)
    }

    private fun chooseDate() {
        val today = Calendar.getInstance()
        val earliestDate = (today.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -(MAX_DATE_WINDOW_DAYS - 1))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val previousDate = selectedDateKey()
                selectedDate.set(year, month, day)
                val requestedDate = selectedDateKey()
                pageNumber = 1
                showHome()
                updateDateButton()
                refreshDownloadIndicator()
                if (requestedDate != previousDate) {
                    availabilityWebViews.values.forEach(WebView::stopLoading)
                    availabilityCheckDates.clear()
                    availabilityAttempts.clear()
                    papersToOpenWhenAvailable.clear()
                }
                refreshScheduledAvailabilityStatuses()
            },
            selectedDate.get(Calendar.YEAR),
            selectedDate.get(Calendar.MONTH),
            selectedDate.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = earliestDate.timeInMillis
            datePicker.maxDate = today.timeInMillis
        }.show()
    }

    private fun isToday(date: String): Boolean =
        date == dateFormat.format(Calendar.getInstance().time)

    private fun formatDate(date: String): String =
        SimpleDateFormat("dd_MM_yyyy", Locale.US).parse(date)?.let {
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(it)
        } ?: date

    private fun updateDateButton() {
        dateButton.text = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
            .format(selectedDate.time)
    }

    private fun showHome() {
        cancelOnDemandPageRequest()
        if (showingJangVerificationPage) {
            availabilityCheckDates.remove(NewspaperId.JANG_LAHORE)
            availabilityAttempts.remove(NewspaperId.JANG_LAHORE)
            papersToOpenWhenAvailable.remove(NewspaperId.JANG_LAHORE)
            closeJangVerificationPage()
        }
        viewingSavedStory = false
        viewingStoryContent = false
        setArticleTextViewerMode(false)
        readerMode = false
        homeScreen.visibility = View.VISIBLE
        readerScreen.visibility = View.GONE
        webView.visibility = View.GONE
        refreshDownloadIndicator()
        mainHandler.removeCallbacks(refreshHomeProgress)
        mainHandler.post(refreshHomeProgress)
    }

    private fun openEdition(edition: NewspaperEdition) {
        viewingSavedStory = false
        openVerifiedEdition(edition)
    }

    private fun openVerifiedEdition(edition: NewspaperEdition) {
        closeJangVerificationPage()
        availableEdition = edition
        readerMode = true
        homeScreen.visibility = View.GONE
        readerScreen.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
        val directory = editionDirectory(edition)
        offlineEdition = edition
        offlineDirectory = directory
        val paper = Papers.forEdition(edition)
        val isToday = edition.date == dateFormat.format(Calendar.getInstance().time)
        if (paper.supportsForegroundEditionPrefetch && isToday &&
            editionInProgress?.paperId != edition.paperId &&
            !File(directory, "complete").isFile
        ) {
            downloadEdition(edition)
        }
        pageNumber = edition.readingOrder.firstOrNull()?.id?.takeLast(3)?.toIntOrNull() ?: 1
        updatePagePicker(edition)
        loadPage()
    }

    private fun editionDirectory(edition: NewspaperEdition): File =
        Papers.forEdition(edition)
            .editionDirectory(File(filesDir, "editions"), edition.date)

    private fun updatePagePicker(edition: NewspaperEdition) {
        val selectedPage = edition.readingOrder.firstOrNull {
            it.id.takeLast(3).toIntOrNull() == pageNumber
        }
        val pageTitle = selectedPage?.title
            ?.takeIf { it.isNotBlank() }
            ?: selectedPage?.let { "Page ${it.id.takeLast(3)}" }
        pagePickerButton.text = pageTitle ?: "Pages"
        pagePickerButton.contentDescription = pageTitle?.let {
            "Current page: $it. Tap to choose another page."
        } ?: "Choose a newspaper page"
    }

    private fun showPagePicker() {
        val edition = readerEdition() ?: return
        val selectedIndex = edition.readingOrder.indexOfFirst {
            it.id.takeLast(3).toIntOrNull() == pageNumber
        }
        PopupMenu(this, pagePickerButton).apply {
            menu.setGroupCheckable(Menu.NONE, true, true)
            edition.readingOrder.forEachIndexed { index, page ->
                val title = page.title.ifBlank { "Page ${page.id.takeLast(3)}" }
                menu.add(Menu.NONE, index, index, title)
                    .setCheckable(true)
                    .isChecked = index == selectedIndex
            }
            setOnMenuItemClickListener { item ->
                edition.readingOrder.getOrNull(item.itemId)?.let(::openReaderPage)
                true
            }
        }.show()
    }

    private fun openReaderPage(page: NewspaperPage) {
        val edition = readerEdition() ?: return
        val directory = editionDirectory(edition)
        if (displayedPageId != page.id || displayedEditionDate != edition.date) {
            displayedPageId = null
            displayedEditionDate = null
            paperPageReady = false
        }
        viewingStoryContent = false
        pageNumber = page.id.takeLast(3).toIntOrNull() ?: 1
        updatePagePicker(edition)
        if (edition.hasPageImage(directory, page.id)) {
            openOfflinePage(edition, page, directory)
        } else {
            webView.loadData(
                paperViewer.renderLoadingPage(page),
                "text/html",
                "UTF-8"
            )
            requestPageDownload(edition, directory, page, priority = -100)
        }
        ensurePageHotspots(edition, page)
        prefetchPageStories(edition, page)
    }

    private fun isRequestedPageVisible(request: PageDownloadRequest): Boolean =
        readerMode && !viewingStoryContent &&
            selectedDateKey() == request.edition.date &&
            readerEdition()?.paperId == request.edition.paperId &&
            pageNumber == request.page.id.takeLast(3).toIntOrNull()

    private fun showPageLoadFailure(page: NewspaperPage, message: String?) {
        val edition = readerEdition() ?: return
        val paper = Papers.forEdition(edition)
        val details = message?.takeIf { it.isNotBlank() } ?: "The page image could not be downloaded."
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font:18px sans-serif;padding:24px;
            color:${pageErrorColors().first};background:${pageErrorColors().second}}
            a{display:inline-block;margin-top:16px;padding:10px 16px;background:#244d70;
            color:#fff;text-decoration:none;border-radius:4px}</style></head>
            <body><h2>Could not load ${htmlEscape(page.title)}</h2>
            <p>${htmlEscape(details)}</p>
            <a href="${paper.offlineOrigin}retry/${page.id}">Try again</a></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(paper.offlineOrigin, html, "text/html", "UTF-8", null)
    }

    private fun downloadEdition(edition: NewspaperEdition) {
        if (editionInProgress?.let {
                it.date == edition.date && it.paperId == edition.paperId
            } == true
        ) return

        val directory = editionDirectory(edition)
        synchronized(pageDownloadLock) {
            pageDownloadQueue.clear()
            queuedPageIds.clear()
        }
        editionInProgress = edition
        clearEditionDownloadPause()
        activeEditionDirectory = directory
        val completionMarker = File(directory, "complete")
        if (completionMarker.exists() && !completionMarker.delete()) {
            failEditionDownload("Could not resume this edition's download.")
            return
        }
        editionPageIndex = 0
        textStoryIds = emptyList()
        fullImageIds = emptyList()
        collectedHotspots = emptyList()
        collectedArticles = emptyList()
        processedPageIds = emptyList()
        storyIndex = 0
        archivingStories = false
        processingContentId = null
        pageScanInProgress = true
        storyScanInProgress = false
        storyScanComplete = false
        pageDownloadFailed = false
        val pagesToDownload = edition.readingOrder.filterNot {
            edition.hasPageImage(directory, it.id)
        }
        pageDownloadsRemaining = pagesToDownload.size
        refreshDownloadIndicator()
        enqueuePageDownloads(edition, directory, pagesToDownload)
        loadNextEditionPage()
    }

    private fun enqueuePageDownloads(
        edition: NewspaperEdition,
        directory: File,
        pages: List<NewspaperPage>
    ) {
        synchronized(pageDownloadLock) {
            pages.forEachIndexed { index, page ->
                if (!edition.hasPageImage(directory, page.id) &&
                    queuedPageIds.add(page.id)
                ) {
                    pageDownloadQueue.add(
                        PageDownloadRequest(page, directory, edition, 10 + index, pageDownloadSequence++)
                    )
                }
            }
            startPageDownloadWorkerLocked()
        }
        refreshDownloadIndicator()
    }

    private fun requestPageDownload(
        edition: NewspaperEdition,
        directory: File,
        page: NewspaperPage,
        priority: Int
    ) {
        synchronized(pageDownloadLock) {
            if (edition.hasPageImage(directory, page.id)) return
            val existing = pageDownloadQueue.firstOrNull { it.page.id == page.id }
            if (existing != null) {
                if (priority < existing.priority) {
                    pageDownloadQueue.remove(existing)
                    pageDownloadQueue.add(existing.copy(priority = priority))
                }
            } else if (pageDownloadInProgressId != page.id) {
                pageDownloadQueue.add(
                    PageDownloadRequest(page, directory, edition, priority, pageDownloadSequence++)
                )
                queuedPageIds.add(page.id)
                pageDownloadsRemaining++
                pageDownloadFailed = false
            }
            startPageDownloadWorkerLocked()
        }
    }

    private var pageDownloadInProgressId: String? = null

    private fun startPageDownloadWorkerLocked() {
        if (pageDownloadWorkerRunning || pageDownloadQueue.isEmpty()) return
        pageDownloadWorkerRunning = true
        downloadExecutor.execute {
            while (true) {
                val request = synchronized(pageDownloadLock) {
                    val nextRequest = pageDownloadQueue.peek()
                    if (nextRequest != null &&
                        editionDownloadPaused &&
                        editionInProgress?.let {
                            it.date == nextRequest.edition.date &&
                                it.paperId == nextRequest.edition.paperId
                        } == true
                    ) {
                        pageDownloadWorkerRunning = false
                        null
                    } else {
                        pageDownloadQueue.poll()?.also {
                            queuedPageIds.remove(it.page.id)
                            pageDownloadInProgressId = it.page.id
                            pageDownloadInProgressPaperId = it.edition.paperId
                        } ?: run {
                            pageDownloadWorkerRunning = false
                            pageDownloadInProgressId = null
                            pageDownloadInProgressPaperId = null
                            null
                        }
                    }
                } ?: break
                try {
                    downloadEditionPageImage(
                        request.edition,
                        request.directory,
                        request.page.id
                    )
                    runOnUiThread {
                        val isCurrentEdition = editionInProgress === request.edition
                        if (isCurrentEdition) {
                            val updatedEdition = request.edition.copy(
                                fullImageIds = fullImageIds,
                                hotspots = collectedHotspots,
                                articles = collectedArticles
                            )
                            if (!readerMode ||
                                offlineEdition?.paperId == request.edition.paperId
                            ) {
                                availableEdition = updatedEdition
                            }
                            pageDownloadsRemaining = (pageDownloadsRemaining - 1).coerceAtLeast(0)
                        }
                        if (readerMode && !viewingStoryContent &&
                            offlineEdition?.paperId == request.edition.paperId &&
                            selectedDateKey() == request.edition.date &&
                            pageNumber == request.page.id.takeLast(3).toIntOrNull()
                        ) {
                            openOfflinePage(
                                if (isCurrentEdition) availableEdition ?: request.edition else request.edition,
                                request.page,
                                request.directory
                            )
                        }
                        refreshDownloadIndicator()
                        if (isCurrentEdition) {
                            maybeCompleteEdition(request.edition)
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (editionInProgress === request.edition) {
                            pageDownloadFailed = true
                            pageDownloadsRemaining = (pageDownloadsRemaining - 1).coerceAtLeast(0)
                            if (isRequestedPageVisible(request)) {
                                showPageLoadFailure(request.page, error.message)
                                showMessage(error.message ?: "Could not download ${request.page.title}.")
                            }
                            maybeCompleteEdition(request.edition)
                        } else if (isRequestedPageVisible(request)) {
                            showPageLoadFailure(request.page, error.message)
                            showMessage(error.message ?: "Could not download ${request.page.title}.")
                        }
                        refreshDownloadIndicator()
                    }
                } finally {
                    synchronized(pageDownloadLock) {
                        pageDownloadInProgressId = null
                        pageDownloadInProgressPaperId = null
                    }
                }
            }
            runOnUiThread {
                editionInProgress?.let(::maybeCompleteEdition)
                refreshDownloadIndicator()
            }
        }
    }

    private fun loadNextEditionPage() {
        val edition = editionInProgress ?: return
        if (editionDownloadPaused) {
            editionScanWaitingForResume = true
            refreshDownloadIndicator()
            return
        }
        editionScanWaitingForResume = false
        if (archivingStories) {
            val storyId = textStoryIds.getOrNull(storyIndex)
            if (storyId == null) {
                storyScanInProgress = false
                downloadLinkedImages(edition)
                return
            }
            processingContentId = null
            inspectionWebView.loadUrl("https://epaper.dawn.com/DetailImage.php?StoryImage=$storyId")
            return
        }

        val page = edition.readingOrder.getOrNull(editionPageIndex)
        if (page == null) {
            pageScanInProgress = false
            downloadLinkedContent(edition)
            return
        }
        processingContentId = null
        inspectionWebView.loadUrl(
            Papers.forEdition(edition).pageUrl(edition.date, page.id),
            mapOf("Referer" to "https://epaper.dawn.com/")
        )
    }

    private fun collectPageHotspots(edition: NewspaperEdition, page: NewspaperPage) {
        val fetcher = Papers.forEdition(edition)
        inspectionWebView.evaluateJavascript(
            fetcher.hotspotExtractionScript(edition.date, page.id)
        ) { result ->
            try {
                if (editionInProgress !== edition) return@evaluateJavascript
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read story areas from page ${page.id}.")
                val hotspots = fetcher.parseHotspots(json, edition, page)
                for (hotspot in hotspots) {
                    if (collectedHotspots.none {
                            it.pageId == hotspot.pageId && it.targetId == hotspot.targetId
                        }
                    ) {
                        collectedHotspots = collectedHotspots + hotspot
                        when (hotspot.type) {
                            NewspaperHotspotType.TEXT ->
                                if (hotspot.targetId !in textStoryIds) {
                                    textStoryIds = textStoryIds + hotspot.targetId
                                }
                            NewspaperHotspotType.IMAGE ->
                                if (hotspot.targetId !in fullImageIds) {
                                    fullImageIds = fullImageIds + hotspot.targetId
                                }
                        }
                    }
                }
                editionPageIndex++
                processedPageIds = (processedPageIds + page.id).distinct()
                persistProgress(edition)
                loadNextEditionPage()
            } catch (error: Exception) {
                if (editionInProgress !== edition) return@evaluateJavascript
                failEditionDownload(error.message ?: "Could not read clickable areas on ${page.id}.")
            }
        }
    }

    private fun ensurePageHotspots(edition: NewspaperEdition, page: NewspaperPage) {
        val requestedPage = edition to page
        if (onDemandPage != null && !isSameOnDemandPage(onDemandPage, requestedPage)) {
            cancelOnDemandPageRequest()
        }
        if (page.id in edition.processedPageIds) return
        if (isSameOnDemandPage(onDemandPage, requestedPage)) return
        onDemandPage = requestedPage
        if (Papers.forEdition(edition).requiresPageVerification) {
            jangVerifiedKey = null
            jangWebView.loadUrl(
                Papers.forEdition(edition).pageUrl(edition.date, page.id)
            )
        } else {
            onDemandWebView.loadUrl(
                Papers.forEdition(edition).pageUrl(edition.date, page.id),
                mapOf("Referer" to "https://epaper.dawn.com/")
            )
        }
    }

    private fun isSameOnDemandPage(
        active: Pair<NewspaperEdition, NewspaperPage>?,
        requested: Pair<NewspaperEdition, NewspaperPage>
    ): Boolean {
        val current = active ?: return false
        return current.first.paperId == requested.first.paperId &&
            current.first.date == requested.first.date &&
            current.second.id == requested.second.id
    }

    private fun cancelOnDemandPageRequest() {
        val request = onDemandPage ?: return
        onDemandPage = null
        if (Papers.forEdition(request.first).requiresPageVerification) {
            jangVerificationGeneration++
            jangVerificationKey = null
            jangVerifiedKey = null
            jangWebView.stopLoading()
        } else {
            onDemandWebView.stopLoading()
        }
    }

    private fun prefetchPageStories(edition: NewspaperEdition, page: NewspaperPage) {
        val directory = editionDirectory(edition)
        if (!canPrefetchPageContent(edition, directory)) return
        val pageHotspots = edition.hotspots.filter { it.pageId == page.id }
        if (pageHotspots.isEmpty()) return
        val key = "${edition.paperId.storageName}:${edition.date}:${page.id}"
        if (!pageContentPrefetchKeys.add(key)) return
        val textStoryIds = pageHotspots.filter { it.type == NewspaperHotspotType.TEXT }
            .map { it.targetId }
            .distinct()
        val imageIds = pageHotspots.asSequence()
            .filter { it.type == NewspaperHotspotType.IMAGE }
            .map { it.targetId }
            .distinct()
            .toList()
        pendingPageContentPrefetches.addLast(
            PageContentPrefetch(edition, page, directory, textStoryIds, imageIds, key)
        )
        startNextPageContentPrefetch()
    }

    private fun startNextPageContentPrefetch() {
        if (activePageContentPrefetchKey != null) return
        if (pendingPageContentPrefetches.isEmpty()) return
        val request = pendingPageContentPrefetches.removeFirst()
        activePageContentPrefetchKey = request.key
        if (!canPrefetchPageContent(request.edition, request.directory)) {
            finishPageContentPrefetch(request.key)
            return
        }
        prefetchPageText(
            request.edition,
            request.page,
            request.directory,
            request.textStoryIds,
            request.imageIds,
            0
        )
    }

    private fun finishPageContentPrefetch(key: String) {
        if (activePageContentPrefetchKey != key) return
        activePageContentPrefetchKey = null
        pageContentPrefetchKeys.remove(key)
        startNextPageContentPrefetch()
    }

    private fun prefetchPageText(
        edition: NewspaperEdition,
        page: NewspaperPage,
        directory: File,
        textStoryIds: List<String>,
        imageIds: List<String>,
        index: Int
    ) {
        if (!canPrefetchPageContent(edition, directory)) {
            finishPageContentPrefetch("${edition.paperId.storageName}:${edition.date}:${page.id}")
            return
        }
        if (index >= textStoryIds.size) {
            prefetchPageImages(edition, page, directory, imageIds)
            return
        }
        val id = textStoryIds[index]
        val metadata = File(directory, "edition.json")
        val savedEdition = if (metadata.isFile) {
            runCatching { NewspaperEdition.readMetadata(metadata) }.getOrElse { error ->
                android.util.Log.e("MainActivity", "Could not read edition before prefetching text.", error)
                edition
            }
        } else edition
        val next = {
            prefetchPageText(edition, page, directory, textStoryIds, imageIds, index + 1)
        }
        if (savedEdition.articles.any {
                it.id == id &&
                    !(Papers.forEdition(edition).refreshArticleWhenBylineMissing && it.byline.isBlank())
            }
        ) {
            next()
            return
        }
        enqueueTextStory(TextStoryRequest(edition, directory, id), next)
    }

    private fun prefetchPageImages(
        edition: NewspaperEdition,
        page: NewspaperPage,
        directory: File,
        pageImageIds: List<String>
    ) {
        if (!canPrefetchPageContent(edition, directory)) {
            finishPageContentPrefetch("${edition.paperId.storageName}:${edition.date}:${page.id}")
            return
        }
        val metadata = File(directory, "edition.json")
        val savedEdition = if (metadata.isFile) {
            runCatching { NewspaperEdition.readMetadata(metadata) }.getOrElse { error ->
                android.util.Log.e("MainActivity", "Could not read edition before prefetching images.", error)
                edition
            }
        } else edition
        val imageIds = pageImageIds.distinct().filterNot {
            savedEdition.hasFullImage(directory, it) || it in prefetchingImageIds
        }
        val pageStoryIds = savedEdition.hotspots.asSequence()
            .filter { it.pageId == page.id && it.type == NewspaperHotspotType.TEXT }
            .map { it.targetId }
            .toSet()
        val articleImages = if (Papers.forEdition(edition).supportsInlineArticleImages) {
            savedEdition.articles.filter { article ->
                article.id in pageStoryIds &&
                    article.imageUrls.indices.any {
                        !savedEdition.hasArticleImage(directory, article, it)
                    }
            }
        } else {
            emptyList()
        }
        if (imageIds.isEmpty() && articleImages.isEmpty()) {
            finishPageContentPrefetch("${edition.paperId.storageName}:${edition.date}:${page.id}")
            return
        }
        prefetchingImageIds.addAll(imageIds)
        refreshDownloadIndicator()
        downloadExecutor.execute {
            try {
                for (id in imageIds) {
                    if (!canPrefetchPageContent(edition, directory)) break
                    downloadEditionFullImages(savedEdition, directory, listOf(id))
                    val latest = readEditionMetadata(directory, edition)
                    val updated = latest.copy(
                        fullImageIds = (latest.fullImageIds + id).distinct()
                    )
                    persistEdition(updated, directory)
                    runOnUiThread {
                        updateEditionReferences(updated)
                        if (pendingArticleImageIds.remove(id) &&
                            readerMode && viewingArticleImage &&
                            selectedDateKey() == edition.date
                        ) showArticleImage(updated, directory, id)
                        if (onDemandImageId == id) {
                            onDemandImageId = null
                            storyLoadingDialog?.dismiss()
                            storyLoadingDialog = null
                            if (readerMode && viewingStoryContent &&
                                selectedDateKey() == edition.date
                            ) showOfflineImage(updated, directory, id)
                        }
                    }
                }
                if (!canPrefetchPageContent(edition, directory)) return@execute
                if (articleImages.isNotEmpty()) {
                    val latest = readEditionMetadata(directory, edition)
                    latest.downloadArticleImages(
                        directory,
                        articleImages.mapNotNull { imageArticle ->
                            latest.articles.firstOrNull { it.id == imageArticle.id }
                        }
                    ) { url, referer, destination ->
                        downloadJangImageThroughWebView(url, referer, destination)
                    }
                }
            } catch (error: Exception) {
                android.util.Log.e(
                    "MainActivity",
                    "Could not preload content for ${edition.paperId.displayName} page ${page.id}.",
                    error
                )
            } finally {
                runOnUiThread {
                    prefetchingImageIds.removeAll(imageIds.toSet())
                    finishPageContentPrefetch(
                        "${edition.paperId.storageName}:${edition.date}:${page.id}"
                    )
                    refreshDownloadIndicator()
                }
            }
        }
    }

    private fun readEditionMetadata(directory: File, fallback: NewspaperEdition): NewspaperEdition {
        val metadata = File(directory, "edition.json")
        return if (metadata.isFile) NewspaperEdition.readMetadata(metadata) else fallback
    }

    private fun canPrefetchPageContent(edition: NewspaperEdition, directory: File): Boolean =
        editionInProgress?.let {
            it.date == edition.date && it.paperId == edition.paperId
        } != true && !File(directory, "complete").isFile

    private fun startNextTextStory() {
        if (activeTextStory != null || pendingTextStories.isEmpty()) return
        val nextRequest = pendingTextStories.first()
        if (editionDownloadPaused && editionInProgress?.let {
                it.date == nextRequest.edition.date &&
                    it.paperId == nextRequest.edition.paperId
            } == true
        ) return
        val request = pendingTextStories.removeFirst()
        activeTextStory = request
        refreshDownloadIndicator()
        val url = Papers.forEdition(request.edition).articleUrl(request.id)
        detailWebView.loadUrl(url)
    }

    private fun enqueueTextStory(request: TextStoryRequest, onComplete: () -> Unit) {
        val existing = activeTextStory?.takeIf { it.matches(request) }
            ?: pendingTextStories.firstOrNull { it.matches(request) }
        if (existing != null) {
            existing.completionCallbacks += onComplete
            return
        }
        request.completionCallbacks += onComplete
        pendingTextStories.addLast(request)
        startNextTextStory()
    }

    private fun finishTextStoryRequest(request: TextStoryRequest) {
        val callbacks = request.completionCallbacks.toList()
        request.completionCallbacks.clear()
        callbacks.forEach { it() }
    }

    private fun saveStoryArticle(request: TextStoryRequest, article: NewspaperArticle) {
        val directory = request.directory
        try {
            if (editionInProgress?.date == request.edition.date &&
                editionInProgress?.paperId == request.edition.paperId
            ) {
                collectedArticles = collectedArticles.filterNot { it.id == article.id } + article
                persistProgress(editionInProgress ?: request.edition)
                return
            }
            val metadata = File(directory, "edition.json")
            val latest = if (metadata.isFile) NewspaperEdition.readMetadata(metadata) else request.edition
            val updated = latest.copy(
                articles = latest.articles.filterNot { it.id == article.id } + article
            )
            persistEdition(updated, directory)
            updateEditionReferences(updated)
        } catch (error: Exception) {
            showMessage(error.message ?: "Could not save story ${article.id}.")
        }
    }

    private fun updateEditionReferences(edition: NewspaperEdition) {
        if (offlineEdition?.date == edition.date &&
            offlineEdition?.paperId == edition.paperId
        ) offlineEdition = edition
        if (availableEdition?.date == edition.date &&
            availableEdition?.paperId == edition.paperId
        ) availableEdition = edition
    }

    private fun loadSavedStories() {
        val savedJson = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(SAVED_STORIES_KEY, null) ?: return
        try {
            val parsedStories = JSONArray(savedJson)
            val loaded = (0 until parsedStories.length()).map { index ->
                val value = parsedStories.getJSONObject(index)
                val articleValue = value.getJSONObject("article")
                val paragraphs = articleValue.getJSONArray("paragraphs")
                val imageUrls = articleValue.getJSONArray("imageUrls")
                val article = NewspaperArticle(
                    id = articleValue.getString("id"),
                    title = articleValue.getString("title"),
                    byline = articleValue.optString("byline"),
                    paragraphs = (0 until paragraphs.length()).map(paragraphs::getString),
                    imageUrls = (0 until imageUrls.length()).map(imageUrls::getString)
                )
                SavedStory(
                    paperId = NewspaperId.entries.firstOrNull {
                        it.storageName == value.getString("paperId")
                    } ?: throw IllegalArgumentException("Saved story has an unknown newspaper."),
                    date = value.getString("date"),
                    article = article
                )
            }
            savedStories.clear()
            savedStories.addAll(loaded.distinctBy(SavedStory::key))
        } catch (error: Exception) {
            showMessage(error.message ?: "Could not load saved stories.")
        }
    }

    private fun persistSavedStories() {
        val payload = JSONArray().apply {
            savedStories.forEach { saved ->
                put(JSONObject().apply {
                    put("paperId", saved.paperId.storageName)
                    put("date", saved.date)
                    put("article", JSONObject().apply {
                        put("id", saved.article.id)
                        put("title", saved.article.title)
                        put("byline", saved.article.byline)
                        put("paragraphs", JSONArray(saved.article.paragraphs))
                        put("imageUrls", JSONArray(saved.article.imageUrls))
                    })
                })
            }
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putString(SAVED_STORIES_KEY, payload.toString())
            .apply()
        updateSavedStoriesList()
        refreshSaveStoryButton()
    }

    private fun updateSavedStoriesList() {
        if (!::savedStoriesList.isInitialized) return
        savedStoriesList.removeAllViews()
        if (savedStories.isEmpty()) {
            savedStoriesList.addView(TextView(this).apply {
                text = "No saved stories yet."
                textSize = 15f
                setTextColor(secondaryTextColor())
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = roundedBackground(surfaceColor(), dp(14))
            })
            return
        }
        savedStories.forEach { saved ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(14), dp(10), dp(14))
                background = roundedBackground(surfaceColor(), dp(14))
                elevation = dp(1).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { openSavedStory(saved) }
            }
            val storyInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setOnClickListener { openSavedStory(saved) }
            }
            val title = TextView(this).apply {
                text = saved.article.title
                textSize = 16f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(primaryTextColor())
            }
            val metadata = TextView(this).apply {
                text = "${saved.paperId.displayName} · ${formatDate(saved.date)}"
                textSize = 12f
                setTextColor(secondaryTextColor())
                setPadding(0, dp(5), 0, 0)
            }
            storyInfo.addView(title)
            storyInfo.addView(metadata)
            row.addView(
                storyInfo,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            val remove = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(
                    if (darkTheme) Color.rgb(255, 150, 150) else Color.rgb(170, 55, 55)
                )
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Remove saved story"
                setOnClickListener { confirmRemoveSavedStory(saved) }
            }
            row.addView(remove, LinearLayout.LayoutParams(dp(48), dp(48)))
            savedStoriesList.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun confirmRemoveSavedStory(saved: SavedStory) {
        AlertDialog.Builder(this)
            .setTitle("Remove saved story?")
            .setMessage("Remove this story from your saved stories?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                savedStories.removeAll { it.key == saved.key }
                persistSavedStories()
            }
            .show()
    }

    private fun saveCurrentStory() {
        val edition = offlineEdition ?: return
        val articleId = displayedArticleId ?: return
        val article = edition.articles.firstOrNull { it.id == articleId } ?: run {
            showMessage("Story details are not ready to save.")
            return
        }
        val saved = SavedStory(edition.paperId, edition.date, article)
        val existingIndex = savedStories.indexOfFirst { it.key == saved.key }
        if (existingIndex >= 0) {
            savedStories.removeAt(existingIndex)
            showMessage("Story removed from saved stories.")
        } else {
            savedStories.add(0, saved)
            showMessage("Story saved.")
        }
        persistSavedStories()
    }

    private fun refreshSaveStoryButton() {
        if (!::saveStoryButton.isInitialized) return
        val edition = offlineEdition
        val articleId = displayedArticleId
        val isSaved = edition != null && articleId != null &&
            savedStories.any {
                it.paperId == edition.paperId &&
                    it.date == edition.date &&
                    it.article.id == articleId
            }
        val compactLabel = resources.configuration.screenWidthDp < 600
        saveStoryButton.text = when {
            isSaved && compactLabel -> "Saved"
            isSaved -> "Story saved"
            compactLabel -> "Save"
            else -> "Save story"
        }
        saveStoryButton.contentDescription =
            if (isSaved) "Story is saved" else "Save story"
        setButtonIcon(saveStoryButton, android.R.drawable.ic_menu_save)
    }

    private fun openSavedStory(saved: SavedStory) {
        val paper = Papers[saved.paperId]
        val directory = paper.editionDirectory(File(filesDir, "editions"), saved.date)
        val metadata = File(directory, "edition.json")
        val storedEdition = if (metadata.isFile) {
            runCatching { NewspaperEdition.readMetadata(metadata) }
                .getOrElse { error ->
                    showMessage(error.message ?: "Could not open the saved story.")
                    return
                }
        } else {
            NewspaperEdition(
                date = saved.date,
                latestDate = saved.date,
                pages = emptyList(),
                isJangEdition = saved.paperId == NewspaperId.JANG_LAHORE,
                paperId = saved.paperId
            )
        }
        if (storedEdition.paperId != saved.paperId || storedEdition.date != saved.date) {
            showMessage("Saved story edition details do not match.")
            return
        }
        val edition = storedEdition.copy(
            articles = storedEdition.articles.filterNot { it.id == saved.article.id } +
                saved.article
        )
        selectedDate.time = dateFormat.parse(saved.date) ?: selectedDate.time
        updateDateButton()
        availableEdition = edition
        offlineEdition = edition
        offlineDirectory = directory
        readerMode = true
        viewingStoryContent = true
        viewingSavedStory = true
        viewingArticleImage = false
        displayedArticleId = saved.article.id
        homeScreen.visibility = View.GONE
        readerScreen.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
        setArticleTextViewerMode(true)
        webView.loadDataWithBaseURL(
            paper.offlineOrigin,
            renderArticleHtml(edition, saved.article, directory),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun collectOnDemandPageHotspots(edition: NewspaperEdition, page: NewspaperPage) {
        val fetcher = Papers.forEdition(edition)
        val source = if (fetcher.requiresPageVerification) jangWebView else onDemandWebView
        source.evaluateJavascript(fetcher.hotspotExtractionScript(edition.date, page.id)) { result ->
            try {
                if (!isSameOnDemandPage(onDemandPage, edition to page)) {
                    return@evaluateJavascript
                }
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read story areas from page ${page.id}.")
                val hotspots = fetcher.parseHotspots(json, edition, page)
                val directory = editionDirectory(edition)
                if (!directory.exists() && !directory.mkdirs()) {
                    onDemandPage = null
                    showMessage("Could not save this page's clickable areas.")
                    return@evaluateJavascript
                }
                val savedMetadata = File(directory, "edition.json")
                val latest = if (savedMetadata.isFile) {
                    NewspaperEdition.readMetadata(savedMetadata)
                } else edition
                val updated = latest.copy(
                    hotspots = latest.hotspots.filterNot { it.pageId == page.id } + hotspots,
                    processedPageIds = (latest.processedPageIds + page.id).distinct(),
                    jangImageAreasVersion = if (fetcher.requiresPageVerification) 1
                    else latest.jangImageAreasVersion
                )
                updated.saveMetadata(directory)
                availableEdition = updated
                if (offlineEdition?.date == updated.date &&
                    offlineEdition?.paperId == updated.paperId
                ) offlineEdition = updated
                if (readerMode && selectedDateKey() == updated.date &&
                    pageNumber == page.id.takeLast(3).toIntOrNull()
                ) prefetchPageStories(updated, page)
                if (!viewingStoryContent) updateRenderedHotspots()
                onDemandPage = null
            } catch (error: Exception) {
                onDemandPage = null
                showMessage(error.message ?: "Could not read clickable areas on ${page.id}.")
            }
        }
    }

    private fun downloadLinkedContent(edition: NewspaperEdition) {
        persistProgress(edition)
        archivingStories = textStoryIds.isNotEmpty()
        storyScanInProgress = archivingStories
        storyIndex = 0
        if (archivingStories) {
            loadNextEditionPage()
        } else {
            downloadLinkedImages(edition)
        }
    }

    private fun downloadLinkedImages(edition: NewspaperEdition) {
        val directory = activeEditionDirectory ?: run {
            failEditionDownload("Edition storage is unavailable.")
            return
        }
        val detailImageIds = if (Papers.forEdition(edition).supportsImageDetail) {
            textStoryIds
        } else {
            emptyList()
        }
        val imageIds = (fullImageIds + detailImageIds)
            .distinct()
            .filterNot { edition.hasFullImage(directory, it) }
        fullImageIds = (fullImageIds + detailImageIds).distinct()
        val downloadEdition = edition.copy(
            fullImageIds = fullImageIds,
            hotspots = collectedHotspots,
            articles = collectedArticles
        )
        downloadExecutor.execute {
            try {
                imageIds.forEach { id ->
                    awaitEditionDownloadResume(edition)
                    downloadEditionFullImages(downloadEdition, directory, listOf(id))
                    runOnUiThread { refreshDownloadIndicator() }
                }
                awaitEditionDownloadResume(edition)
                downloadEdition.downloadArticleImages(directory) { url, referer, destination ->
                    downloadJangImageThroughWebView(url, referer, destination)
                }
                runOnUiThread {
                    if (editionInProgress !== edition) return@runOnUiThread
                    storyScanComplete = true
                    persistProgress(edition)
                    maybeCompleteEdition(edition)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (editionInProgress === edition) {
                        failEditionDownload(error.message ?: "Could not download full-size images.")
                    }
                }
            }
        }
    }

    private fun collectStoryText(edition: NewspaperEdition, id: String) {
        parseStoryText(inspectionWebView, id) { article ->
            if (editionInProgress !== edition) return@parseStoryText
            collectedArticles = collectedArticles.filterNot { it.id == id } + article
            storyIndex++
            persistProgress(edition)
            loadNextEditionPage()
        }
    }

    private fun parseStoryText(source: WebView, id: String, onArticle: (NewspaperArticle) -> Unit) {
        val script = """
            (() => {
                const article = Array.from(document.querySelectorAll('.px-4.font-merriweather > div'))
                    .find(element => element.querySelector('h2') &&
                        element.querySelector('.overflow-hidden'));
                if (!article) return JSON.stringify({error: 'Could not find the text for story $id.'});
                const title = article.querySelector('h2')?.innerText.trim() || '';
                const byline = article.querySelector(':scope > .my-3')?.innerText.trim() || '';
                const body = article.querySelector(':scope > .overflow-hidden');
                const paragraphs = Array.from(body.querySelectorAll('p'))
                    .map(paragraph => paragraph.innerText.trim()).filter(Boolean);
                if (!paragraphs.length && body.innerText.trim()) paragraphs.push(body.innerText.trim());
                return JSON.stringify({id: '$id', title: title, byline: byline, paragraphs: paragraphs});
            })()
        """.trimIndent()
        source.evaluateJavascript(script) { result ->
            try {
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read Dawn's text for story $id.")
                val data = JSONObject(json)
                if (data.has("error")) throw IllegalStateException(data.getString("error"))
                val paragraphsJson = data.getJSONArray("paragraphs")
                val article = NewspaperArticle(
                    id,
                    data.getString("title"),
                    data.optString("byline"),
                    (0 until paragraphsJson.length()).map { paragraphsJson.getString(it) }
                )
                if (article.title.isBlank() || article.paragraphs.isEmpty()) {
                    throw IllegalStateException("Dawn returned empty text for story $id.")
                }
                onArticle(article)
            } catch (error: Exception) {
                if (source === inspectionWebView) {
                    failEditionDownload(error.message ?: "Could not save Dawn story text.")
                } else if (source === detailWebView) {
                    val request = activeTextStory
                    activeTextStory = null
                    if (request != null && onDemandStoryId == request.id) {
                        onDemandStoryId = null
                        onDemandEdition = null
                        onDemandDirectory = null
                        storyLoadingDialog?.dismiss()
                        storyLoadingDialog = null
                        showMessage(error.message ?: "Could not download this story's text.")
                        if (readerMode) returnToCurrentPaperPage()
                    } else {
                        showMessage(error.message ?: "Could not prefetch story $id.")
                    }
                    request?.let(::finishTextStoryRequest)
                    startNextTextStory()
                    refreshDownloadIndicator()
                } else {
                    onDemandStoryId = null
                    onDemandEdition = null
                    onDemandDirectory = null
                    storyLoadingDialog?.dismiss()
                    storyLoadingDialog = null
                    showMessage(error.message ?: "Could not download this story's text.")
                }
            }
        }
    }

    private fun parseJangStoryText(
        source: WebView,
        id: String,
        attempt: Int = 0,
        onArticle: (NewspaperArticle) -> Unit
    ) {
        val script = """
            (() => {
                const content = document.querySelector('.detail-content');
                const body = content?.querySelector('.description-area .detail_view_content') ||
                    content?.querySelector('.description-area');
                const title = document.querySelector('h1')?.innerText.trim() || '';
                const author = document.querySelector('.detail-w-t .authorFullName')
                    ?.innerText.trim() || '';
                const date = Array.from(document.querySelectorAll('.detail-w-t .detail-time'))
                    .map(element => element.innerText.trim())
                    .find(text => text && text !== author) || '';
                if (!body || !title || !body.innerText.trim()) {
                    return JSON.stringify({pending: true});
                }
                let paragraphs = Array.from(body.querySelectorAll('p'))
                    .map(p => p.innerText.trim())
                    .filter(text => text && !text.startsWith('فوٹو:'));
                if (!paragraphs.length) {
                    paragraphs = body.innerText.split(/\n+/)
                        .map(text => text.trim())
                        .filter(text => text && !text.startsWith('فوٹو:'));
                }
                const imageUrls = Array.from(content.querySelectorAll('img[src*="/assets/uploads/"]'))
                    .map(img => img.currentSrc || img.src).filter(Boolean);
                return JSON.stringify({id: '$id', title: title, author: author, date: date,
                    paragraphs: paragraphs, imageUrls: imageUrls});
            })()
        """.trimIndent()
        source.evaluateJavascript(script) { result ->
            try {
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read Jang story $id.")
                val data = JSONObject(json)
                if (data.optBoolean("pending")) {
                    if (attempt < 15) {
                        source.postDelayed({
                            if (activeTextStory?.id == id) {
                                parseJangStoryText(source, id, attempt + 1, onArticle)
                            }
                        }, 1_000)
                        return@evaluateJavascript
                    }
                    throw IllegalStateException("Jang's story page did not finish loading.")
                }
                if (data.has("error")) throw IllegalStateException(data.getString("error"))
                val paragraphsJson = data.getJSONArray("paragraphs")
                val byline = listOf(data.optString("author"), data.optString("date"))
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                val article = NewspaperArticle(
                    id,
                    data.getString("title"),
                    byline,
                    (0 until paragraphsJson.length()).map { paragraphsJson.getString(it) },
                    data.getJSONArray("imageUrls").let { images ->
                        (0 until images.length()).map { images.getString(it) }
                    }
                )
                if (article.title.isBlank() || article.paragraphs.isEmpty()) {
                    throw IllegalStateException("Jang returned empty text for story $id.")
                }
                onArticle(article)
            } catch (error: Exception) {
                val request = activeTextStory
                activeTextStory = null
                parsingJangStoryId = null
                request?.let(::finishTextStoryRequest)
                if (request != null && onDemandStoryId == request.id) {
                    onDemandStoryId = null
                    onDemandEdition = null
                    onDemandDirectory = null
                    storyLoadingDialog?.dismiss()
                    storyLoadingDialog = null
                    showMessage(error.message ?: "Could not download Jang story $id.")
                    if (readerMode) returnToCurrentPaperPage()
                } else {
                    showMessage(error.message ?: "Could not prefetch Jang story $id.")
                }
                startNextTextStory()
                refreshDownloadIndicator()
            }
        }
    }

    private fun completeEditionDownload(edition: NewspaperEdition) {
        try {
            val directory = activeEditionDirectory
                ?: throw IllegalStateException("Edition storage is unavailable.")
            val savedEdition = edition.copy(
                fullImageIds = fullImageIds,
                hotspots = collectedHotspots,
                articles = collectedArticles,
                processedPageIds = processedPageIds
            )
            savedEdition.removeLegacyArchives(directory)
            savedEdition.saveMetadata(directory)
            File(directory, "complete").writeText("ok", Charsets.UTF_8)
            if (!readerMode || offlineEdition?.paperId == savedEdition.paperId) {
                availableEdition = savedEdition
                offlineEdition = savedEdition
            }
            clearEditionDownloadPause()
            editionInProgress = null
            activeEditionDirectory = null
            archivingStories = false
            pageScanInProgress = false
            storyScanInProgress = false
            setPaperStatus(Papers.forEdition(savedEdition), "Available", Color.rgb(30, 125, 70))
            refreshDownloadIndicator()
            if (!readerMode || offlineEdition?.paperId == savedEdition.paperId) {
                updatePagePicker(savedEdition)
                updatePageLabel()
            }
        } catch (error: Exception) {
            failEditionDownload(error.message ?: "Could not save edition details.")
        }
    }

    private fun failEditionDownload(message: String) {
        val failedEdition = editionInProgress
        clearEditionDownloadPause()
        editionInProgress = null
        activeEditionDirectory = null
        archivingStories = false
        pageScanInProgress = false
        storyScanInProgress = false
        failedEdition?.let {
            setPaperStatus(Papers.forEdition(it), "Available", Color.rgb(180, 90, 20))
        }
        refreshDownloadIndicator()
        showMessage(message)
        updatePageLabel()
    }

    private fun persistProgress(edition: NewspaperEdition) {
        val directory = activeEditionDirectory ?: return
        val progress = edition.copy(
            fullImageIds = fullImageIds,
            hotspots = collectedHotspots,
            articles = collectedArticles,
            processedPageIds = processedPageIds
        )
        progress.saveMetadata(directory)
        offlineEditionCache[progress.paperId to progress.date] = OfflineEditionCache(
            File(directory, "edition.json").lastModified(),
            File(directory, "edition.json").length(),
            progress
        )
        refreshOfflineProgress()
        val anotherPaperIsActive = readerMode && offlineEdition?.paperId != progress.paperId
        if (!anotherPaperIsActive) availableEdition = progress
        if (offlineEdition?.let {
                it.date == progress.date && it.paperId == progress.paperId
            } == true
        ) {
            offlineEdition = progress
        }
        if (!anotherPaperIsActive) updatePagePicker(progress)
        if (readerMode && !anotherPaperIsActive && !viewingStoryContent &&
            selectedDateKey() == progress.date
        ) {
            val page = progress.readingOrder.firstOrNull {
                it.id.takeLast(3).toIntOrNull() == pageNumber
            }
            if (page != null && progress.hasPageImage(directory, page.id)) {
                if (displayedPageId == page.id && displayedEditionDate == progress.date) {
                    offlineEdition = progress
                    updateRenderedHotspots()
                } else if (displayedPageId == null) {
                    openOfflinePage(progress, page, directory)
                }
                prefetchPageStories(progress, page)
            }
        }
    }

    private fun maybeCompleteEdition(edition: NewspaperEdition) {
        if (editionInProgress !== edition || !storyScanComplete || pageDownloadsRemaining > 0) return
        val queueEmpty = synchronized(pageDownloadLock) {
            !pageDownloadWorkerRunning && pageDownloadQueue.isEmpty()
        }
        if (!queueEmpty) return
        if (pageDownloadFailed) {
            persistProgress(edition)
            return
        }
        completeEditionDownload(edition)
    }

    private fun selectedDateKey(): String = dateFormat.format(selectedDate.time)

    private fun readerEdition(): NewspaperEdition? =
        if (readerMode) offlineEdition ?: availableEdition else availableEdition

    private fun openOfflinePage(edition: NewspaperEdition, page: NewspaperPage, directory: File) {
        if (!edition.hasPageImage(directory, page.id)) {
            showMessage("This page image is missing from the offline edition.")
            return
        }
        setArticleTextViewerMode(false)
        val samePaperPage = displayedEditionDate == edition.date &&
            displayedPageId == page.id && !viewingStoryContent
        offlineEdition = edition
        offlineDirectory = directory
        availableEdition = edition
        readerMode = true
        homeScreen.visibility = View.GONE
        readerScreen.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
        selectedDate.time = dateFormat.parse(edition.date) ?: selectedDate.time
        pageNumber = page.id.takeLast(3).toInt()
        updateDateButton()
        updatePagePicker(edition)
        updatePageLabel()
        if (samePaperPage) {
            ensurePageHotspots(edition, page)
            updateRenderedHotspots()
            prefetchPageStories(edition, page)
            return
        }
        viewingStoryContent = false
        viewingSavedStory = false
        val pageHotspots = edition.hotspots.filter { it.pageId == page.id }
        val html = paperViewer.renderPage(
            page,
            pageHotspots,
            Papers.forEdition(edition),
            directory
        )
        displayedPageId = page.id
        displayedEditionDate = edition.date
        paperPageReady = false
        webView.loadDataWithBaseURL(
            Uri.fromFile(directory).toString().trimEnd('/') + "/",
            html,
            "text/html",
            "UTF-8",
            null
        )
        ensurePageHotspots(edition, page)
    }

    private fun updateRenderedHotspots() {
        val edition = offlineEdition ?: return
        val pageId = displayedPageId ?: return
        if (!paperPageReady || viewingStoryContent) return
        val hotspots = edition.hotspots.filter { it.pageId == pageId }
        val payload = org.json.JSONArray().apply {
            hotspots.forEach { hotspot ->
                put(JSONObject().apply {
                    put("shape", hotspot.shape)
                    put("coordinates", org.json.JSONArray(hotspot.coordinates))
                    put("route", if (hotspot.type == NewspaperHotspotType.TEXT) "text" else "image")
                    put("targetId", hotspot.targetId)
                    put("label", hotspot.label)
                })
            }
        }
        webView.evaluateJavascript("window.updatePageHotspots($payload)", null)
    }

    private fun openOfflineTarget(uri: Uri) {
        val edition = offlineEdition ?: return
        val directory = offlineDirectory ?: return
        val paper = Papers.forEdition(edition)
        val parts = uri.pathSegments
        if (parts.size == 2 && parts[0] == "retry") {
            val page = edition.pages.firstOrNull { it.id == parts[1] } ?: return
            viewingStoryContent = false
            openReaderPage(page)
            return
        }
        if (parts.firstOrNull() == "article-image") {
            if (paper.supportsInlineArticleImages) {
                if (parts.size != 3) return
                val article = edition.articles.firstOrNull { it.id == parts[1] } ?: return
                val imageIndex = parts[2].toIntOrNull() ?: return
                val imageUrl = article.imageUrls.getOrNull(imageIndex) ?: return
                showInlineArticleImage(paper, article, imageUrl, imageIndex, directory)
                return
            }
        }
        if (parts.size != 2) return
        val id = parts[1]
        when (parts[0]) {
            "page" -> {
                val page = edition.pages.firstOrNull { it.id == id } ?: return
                openOfflinePage(edition, page, directory)
            }
            "text" -> {
                setArticleTextViewerMode(true)
                viewingSavedStory = false
                val article = edition.articles.firstOrNull { it.id == id }
                if (article == null ||
                    (paper.refreshArticleWhenBylineMissing && article.byline.isBlank())
                ) {
                    viewingStoryContent = true
                    viewingArticleImage = false
                    displayedArticleId = id
                    requestStoryText(edition, directory, id)
                    return
                }
                viewingStoryContent = true
                viewingArticleImage = false
                displayedArticleId = article.id
                webView.loadDataWithBaseURL(
                    Papers.forEdition(edition).offlineOrigin,
                    renderArticleHtml(edition, article, directory),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
            "article" -> {
                setArticleTextViewerMode(true)
                viewingSavedStory = false
                val article = edition.articles.firstOrNull { it.id == id } ?: return
                if (paper.refreshArticleWhenBylineMissing && article.byline.isBlank()) {
                    viewingStoryContent = true
                    viewingArticleImage = false
                    displayedArticleId = id
                    requestStoryText(edition, directory, id)
                    return
                }
                viewingStoryContent = true
                viewingArticleImage = false
                displayedArticleId = article.id
                webView.loadDataWithBaseURL(
                    Papers.forEdition(edition).offlineOrigin,
                    renderArticleHtml(edition, article, directory),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
            "article-image" -> {
                if (Papers.forEdition(edition).articleImageDetailTarget(id) == null ||
                    edition.hotspots.none {
                        it.targetId == id && it.type == NewspaperHotspotType.TEXT
                    }
                ) return
                setArticleTextViewerMode(false)
                viewingStoryContent = true
                viewingArticleImage = true
                displayedArticleId = id
                if (File(File(directory, "images"), "$id.jpg").isFile) {
                    showArticleImage(edition, directory, id)
                } else {
                    requestArticleImage(edition, directory, id)
                }
            }

            "image" -> {
                setArticleTextViewerMode(false)
                viewingStoryContent = true
                viewingArticleImage = false
                if (!edition.hasFullImage(directory, id)) {
                    requestStoryImage(edition, directory, id)
                    return
                }
                showOfflineImage(edition, directory, id)
            }
        }
    }

    private fun showInlineArticleImage(
        paper: Paper,
        article: NewspaperArticle,
        imageUrl: String,
        imageIndex: Int,
        directory: File
    ) {
        val imageTarget = paper.inlineArticleImageTarget(article.id, imageIndex) ?: return
        setArticleTextViewerMode(false)
        viewingStoryContent = true
        viewingArticleImage = true
        displayedArticleId = article.id
        val localImage = offlineEdition?.articleImageFile(directory, article, imageIndex)
            ?.takeIf { it.isFile }
            ?.let { paper.localArticleImageUrl(article.id, imageIndex) }
        val displayedImageUrl = localImage ?: imageUrl
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;background:#111;color:#fff;font:16px sans-serif}
            header{height:48px;display:flex;align-items:center;padding:0 12px}
            a{color:#fff}main{height:calc(100% - 48px);display:flex;align-items:center;
            justify-content:center;overflow:hidden;background:#111}
            img{display:block;width:auto;height:auto;max-width:100%;max-height:100%;
            object-fit:contain;background:#fff}
            #error{padding:24px;text-align:center}</style></head>
            <body><header><a href="${paper.offlineOrigin}article/${article.id}">Back to story text</a></header>
            <main><img src="${htmlEscape(displayedImageUrl)}" alt="${htmlEscape(article.title)}"
                onerror="this.hidden=true;document.getElementById('error').hidden=false">
            <div id="error" hidden>Image could not be displayed.</div></main></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(
            imageTarget,
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun showOfflineImage(edition: NewspaperEdition, directory: File, id: String) {
        val sourcePage = edition.hotspots.firstOrNull { it.targetId == id }?.pageId
            ?: edition.pages.firstOrNull()?.id
            ?: return
        val baseUrl = Uri.fromFile(directory).toString().trimEnd('/') + "/"
        val imageFile = edition.fullImageFile(directory, id)
        val paper = Papers.forEdition(edition)
        setArticleTextViewerMode(false)
        viewingStoryContent = true
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;background:#111;color:#fff;font:16px sans-serif}
            header{height:48px;display:flex;align-items:center;padding:0 12px}
            a{color:#fff}main{height:calc(100% - 48px);display:flex;align-items:center;
            justify-content:center;overflow:hidden;background:#111}
            img{display:block;width:auto;height:auto;max-width:100%;max-height:100%;
            object-fit:contain;background:#fff}
            #error{padding:24px;text-align:center}</style></head>
            <body><header><a href="${paper.offlineOrigin}page/$sourcePage">Back to paper</a></header>
            <main><img src="images/${htmlEscape(imageFile.name)}" alt="Full-size newspaper image"
                onerror="this.hidden=true;document.getElementById('error').hidden=false">
            <div id="error" hidden>Image could not be displayed.</div></main></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    private fun showArticleImage(edition: NewspaperEdition, directory: File, id: String) {
        val baseUrl = Uri.fromFile(directory).toString().trimEnd('/') + "/"
        setArticleTextViewerMode(false)
        viewingStoryContent = true
        viewingArticleImage = true
        displayedArticleId = id
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;background:#111;color:#fff;font:16px sans-serif}
            header{height:48px;display:flex;align-items:center;padding:0 12px}
            a{color:#fff}main{height:calc(100% - 48px);display:flex;align-items:center;
            justify-content:center;overflow:hidden;background:#111}
            img{display:block;width:auto;height:auto;max-width:100%;max-height:100%;
            object-fit:contain;background:#fff}
            #error{padding:24px;text-align:center}</style></head>
            <body><header><a href="${Papers.forEdition(edition).offlineOrigin}article/$id">Back to story text</a></header>
            <main><img src="images/$id.jpg" alt="${htmlEscape(edition.articles.firstOrNull { it.id == id }?.title ?: "Story image")}"
                onerror="this.hidden=true;document.getElementById('error').hidden=false">
            <div id="error" hidden>Image could not be displayed.</div></main></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    private fun requestArticleImage(edition: NewspaperEdition, directory: File, id: String) {
        if (id in prefetchingImageIds) {
            pendingArticleImageIds.add(id)
            refreshDownloadIndicator()
            return
        }
        pendingArticleImageIds.add(id)
        prefetchingImageIds.add(id)
        refreshDownloadIndicator()
        downloadExecutor.execute {
            try {
                downloadEditionFullImages(edition, directory, listOf(id))
                runOnUiThread {
                    try {
                        val metadata = File(directory, "edition.json")
                        val latest = if (metadata.isFile) NewspaperEdition.readMetadata(metadata) else edition
                        val updated = latest.copy(
                            fullImageIds = (latest.fullImageIds + id).distinct()
                        )
                        persistEdition(updated, directory)
                        updateEditionReferences(updated)
                        pendingArticleImageIds.remove(id)
                        if (readerMode && viewingArticleImage &&
                            displayedArticleId == id && selectedDateKey() == edition.date
                        ) showArticleImage(updated, directory, id)
                    } catch (error: Exception) {
                        pendingArticleImageIds.remove(id)
                        showMessage(error.message ?: "Could not save this story image.")
                    } finally {
                        prefetchingImageIds.remove(id)
                        refreshDownloadIndicator()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    pendingArticleImageIds.remove(id)
                    prefetchingImageIds.remove(id)
                    refreshDownloadIndicator()
                    showMessage(error.message ?: "Could not download this story image.")
                }
            }
        }
    }

    private fun returnToCurrentPaperPage() {
        storyLoadingDialog?.dismiss()
        storyLoadingDialog = null
        onDemandStoryId = null
        onDemandImageId = null
        onDemandEdition = null
        onDemandDirectory = null
        viewingArticleImage = false
        displayedArticleId = null
        val edition = offlineEdition ?: availableEdition ?: return
        val page = edition.readingOrder.firstOrNull {
            it.id.takeLast(3).toIntOrNull() == pageNumber
        } ?: edition.readingOrder.firstOrNull() ?: return
        openOfflinePage(
            edition,
            page,
            editionDirectory(edition)
        )
    }

    private fun requestStoryText(edition: NewspaperEdition, directory: File, id: String) {
        if (edition.hotspots.none {
                it.targetId == id && it.type == NewspaperHotspotType.TEXT
            }
        ) {
            showMessage("This text story is not listed in this edition.")
            return
        }
        if (onDemandStoryId != null || onDemandImageId != null) {
            showMessage("Another story is still opening.")
            return
        }
        onDemandStoryId = id
        onDemandEdition = edition
        onDemandDirectory = directory
        showStoryLoadingDialog()
        val request = TextStoryRequest(edition, directory, id)
        if (activeTextStory?.matches(request) != true) {
            val queuedRequest = pendingTextStories.firstOrNull { it.matches(request) }
            if (queuedRequest != null) {
                pendingTextStories.remove(queuedRequest)
                pendingTextStories.addFirst(queuedRequest)
            } else {
                pendingTextStories.addFirst(request)
            }
            startNextTextStory()
        }
    }

    private fun requestStoryImage(edition: NewspaperEdition, directory: File, id: String) {
        if (edition.hotspots.none {
                it.targetId == id && it.type == NewspaperHotspotType.IMAGE
            }
        ) {
            showMessage("This image is not listed in this edition.")
            return
        }
        if (onDemandStoryId != null || onDemandImageId != null) {
            showMessage("Another story is still opening.")
            return
        }
        onDemandImageId = id
        showStoryLoadingDialog()
        if (id in prefetchingImageIds) return
        prefetchingImageIds.add(id)
        refreshDownloadIndicator()
        downloadExecutor.execute {
            try {
                downloadEditionFullImages(edition, directory, listOf(id))
                runOnUiThread {
                    try {
                        val metadata = File(directory, "edition.json")
                        val latest = if (metadata.isFile) NewspaperEdition.readMetadata(metadata) else edition
                        val updated = latest.copy(
                            fullImageIds = (latest.fullImageIds + id).distinct()
                        )
                        persistEdition(updated, directory)
                        updateEditionReferences(updated)
                        onDemandImageId = null
                        storyLoadingDialog?.dismiss()
                        storyLoadingDialog = null
                        if (readerMode && selectedDateKey() == edition.date && viewingStoryContent) {
                            showOfflineImage(updated, directory, id)
                        }
                    } catch (error: Exception) {
                        onDemandImageId = null
                        storyLoadingDialog?.dismiss()
                        storyLoadingDialog = null
                        showMessage(error.message ?: "Could not save this image to the edition.")
                        returnToCurrentPaperPage()
                    } finally {
                        prefetchingImageIds.remove(id)
                        refreshDownloadIndicator()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    prefetchingImageIds.remove(id)
                    onDemandImageId = null
                    storyLoadingDialog?.dismiss()
                    storyLoadingDialog = null
                    showMessage(error.message ?: "Could not download this image.")
                    returnToCurrentPaperPage()
                }
            }
        }
    }

    private fun persistEdition(edition: NewspaperEdition, directory: File?) {
        val targetDirectory = directory
            ?: throw IllegalStateException("Edition storage is unavailable.")
        val marker = File(targetDirectory, "complete")
        val complete = marker.isFile
        edition.saveMetadata(targetDirectory)
        offlineEditionCache.remove(edition.paperId to edition.date)
        if (!complete && marker.exists() && !marker.delete()) {
            throw IllegalStateException("Could not update the edition details.")
        }
    }

    private fun htmlEscape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private fun updatePageLabel() {
        val date = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(selectedDate.time)
        val title = readerEdition()?.readingOrder?.firstOrNull {
            it.id.takeLast(3).toIntOrNull() == pageNumber
        }?.title
        pageLabel.text = listOfNotNull(date, title?.takeIf { it.isNotBlank() })
            .joinToString("  ·  ")
    }

    private fun button(
        label: String,
        iconResource: Int = 0,
        onClick: () -> Unit
    ): Button =
        Button(this).apply {
            text = label
            textSize = if (resources.configuration.screenWidthDp < 600) 12f else 15f
            isAllCaps = false
            minHeight = dp(48)
            minWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            if (iconResource != 0) {
                val icon = getDrawable(iconResource)?.mutate()?.apply {
                    setTint(primaryTextColor())
                }
                setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
                compoundDrawablePadding = dp(6)
            }
            backgroundTintList = ColorStateList.valueOf(
                if (darkTheme) Color.rgb(54, 74, 88) else Color.rgb(230, 239, 245)
            )
            setTextColor(primaryTextColor())
            setOnClickListener { onClick() }
        }

    private fun setButtonIcon(button: Button, iconResource: Int) {
        val icon = getDrawable(iconResource)?.mutate()?.apply {
            setTint(primaryTextColor())
        }
        button.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        button.compoundDrawablePadding = dp(6)
    }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (!darkTheme && color == surfaceColor()) {
                setStroke(dp(1), Color.rgb(230, 234, 237))
            }
        }

    private fun surfaceColor(): Int = if (darkTheme) Color.rgb(30, 30, 30) else Color.WHITE

    private fun homeBackgroundColor(): Int =
        if (darkTheme) Color.rgb(18, 18, 18) else Color.rgb(245, 245, 245)

    private fun primaryTextColor(): Int =
        if (darkTheme) Color.rgb(238, 238, 238) else Color.rgb(34, 50, 70)

    private fun secondaryTextColor(): Int =
        if (darkTheme) Color.rgb(189, 189, 189) else Color.DKGRAY

    private fun pageErrorColors(): Pair<String, String> =
        if (darkTheme) "#eee" to "#222" else "#333" to "#fff"

    private fun showMessage(message: String) {
        if (shuttingDown || isFinishing || isDestroyed || Thread.currentThread().isInterrupted) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { showMessage(message) }
            return
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun refreshDownloadIndicator() {
        refreshOfflineProgress()
        val date = selectedDateKey()
        if (!isToday(date)) {
            paperHomeItems.values.forEach {
                it.downloadIndicator.visibility = View.GONE
                it.downloadAction.visibility = View.GONE
            }
            return
        }
        val inProgressPagePaperId = synchronized(pageDownloadLock) {
            pageDownloadInProgressPaperId
        }
        paperHomeItems.values.forEach { item ->
            val paperId = item.paper.id
            val pageDownloadActive = synchronized(pageDownloadLock) {
                pageDownloadQueue.any {
                    it.edition.paperId == paperId && it.edition.date == date
                } || (inProgressPagePaperId == paperId &&
                    pageDownloadInProgressId?.let { it.startsWith(date) } == true)
            }
            val storyDownloadActive = activeTextStory?.let {
                it.edition.paperId == paperId && it.edition.date == date
            } == true || pendingTextStories.any {
                it.edition.paperId == paperId && it.edition.date == date
            }
            val activeEdition = editionInProgress
            val editionDownloadActive = activeEdition?.let {
                it.paperId == paperId && it.date == date
            } == true
            item.downloadIndicator.visibility = if (
                pageDownloadActive || storyDownloadActive || editionDownloadActive
            ) View.VISIBLE else View.GONE
            val canControlEditionDownload = editionDownloadActive &&
                activeEdition?.paperId == paperId
            item.downloadAction.apply {
                visibility = if (canControlEditionDownload) View.VISIBLE else View.GONE
                setImageResource(
                    if (editionDownloadPaused) {
                        android.R.drawable.ic_media_play
                    } else {
                        android.R.drawable.ic_media_pause
                    }
                )
                setColorFilter(primaryTextColor())
                contentDescription = if (editionDownloadPaused) {
                    "Resume ${item.paper.id.displayName} download"
                } else {
                    "Pause ${item.paper.id.displayName} download"
                }
            }
        }
    }

    private companion object {
        const val PREFERENCES = "epaper_reader"
        const val SAVED_STORIES_KEY = "saved_stories"
        const val ARTICLE_TEXT_SCALE_KEY = "article_text_scale"
        const val STATE_SELECTED_DATE = "selected_date"
        const val STATE_PAGE_NUMBER = "page_number"
        const val STATE_READER_MODE = "reader_mode"
        const val STATE_PAPER_ID = "paper_id"
        const val DEFAULT_ARTICLE_TEXT_SCALE = 1f
        const val MIN_ARTICLE_TEXT_SCALE = 0.75f
        const val MAX_ARTICLE_TEXT_SCALE = 2.25f
        const val MAX_DATE_WINDOW_DAYS = 30
        const val HOME_PROGRESS_REFRESH_MILLIS = 1_500L
        const val MAX_AVAILABILITY_ATTEMPTS = 60
        const val AVAILABILITY_RETRY_MILLIS = 1_500L
        const val MAX_JANG_IMAGE_VERIFICATION_ATTEMPTS = 60
        const val MAX_JANG_IMAGE_REDIRECT_RETRIES = 3
        const val JANG_IMAGE_VERIFICATION_RETRY_MILLIS = 1_000L
        const val JANG_VERIFIED_PAGE_WAIT_MILLIS = 1_500L
        const val MAX_JANG_PAGE_IMAGE_WAIT_ATTEMPTS = 15
        const val MAX_JANG_IMAGE_TRANSFER_ATTEMPTS = 2_000
        const val JANG_IMAGE_TRANSFER_POLL_MILLIS = 25L
        const val JANG_IMAGE_TRANSFER_CHUNK_SIZE = 256 * 1024
        const val JANG_IMAGE_DOWNLOAD_TIMEOUT_SECONDS = 120L
        const val MAX_JANG_WEBVIEW_IMAGE_BYTES = 20 * 1024 * 1024
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        shuttingDown = true
        mainHandler.removeCallbacks(refreshHomeProgress)
        downloadExecutor.shutdownNow()
        webView.destroy()
        inspectionWebView.destroy()
        jangWebView.destroy()
        jangImageWebView.destroy()
        detailWebView.destroy()
        availabilityWebViews.values.forEach(WebView::destroy)
        super.onDestroy()
    }

    @Deprecated("Use the system back dispatcher in newer Android versions.")
    override fun onBackPressed() {
        if (readerMode) {
            if (viewingSavedStory) {
                showHome()
            } else if (viewingStoryContent && viewingArticleImage) {
                val edition = offlineEdition
                val directory = offlineDirectory
                val article = edition?.articles?.firstOrNull { it.id == displayedArticleId }
                if (edition != null && directory != null && article != null) {
                    viewingArticleImage = false
                    val newspaper = Papers.forEdition(edition)
                    webView.loadDataWithBaseURL(
                        newspaper.offlineOrigin,
                        renderArticleHtml(edition, article, directory),
                        "text/html",
                        "UTF-8",
                        null
                    )
                } else {
                    returnToCurrentPaperPage()
                }
            } else if (viewingStoryContent) {
                returnToCurrentPaperPage()
            } else {
                showHome()
            }
        } else {
            super.onBackPressed()
        }
    }
}

private data class PageDownloadRequest(
    val page: NewspaperPage,
    val directory: File,
    val edition: NewspaperEdition,
    val priority: Int,
    val sequence: Long
)

private data class TextStoryRequest(
    val edition: NewspaperEdition,
    val directory: File,
    val id: String,
    val completionCallbacks: MutableList<() -> Unit> = mutableListOf()
) {
    fun matches(other: TextStoryRequest): Boolean =
        edition.paperId == other.edition.paperId &&
            edition.date == other.edition.date &&
            id == other.id
}

private data class PageContentPrefetch(
    val edition: NewspaperEdition,
    val page: NewspaperPage,
    val directory: File,
    val textStoryIds: List<String>,
    val imageIds: List<String>,
    val key: String
)

private data class SavedStory(
    val paperId: NewspaperId,
    val date: String,
    val article: NewspaperArticle
) {
    val key: String
        get() = "${paperId.storageName}:$date:${article.id}"
}
