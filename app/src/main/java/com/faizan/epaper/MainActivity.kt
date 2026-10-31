package com.faizan.epaper

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.ProgressDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.widget.AdapterView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private data class PaperHomeItem(
        val paper: Paper,
        val status: TextView,
        val downloadIndicator: ProgressBar
    )

    private lateinit var webView: WebView
    private lateinit var inspectionWebView: WebView
    private lateinit var jangWebView: WebView
    private lateinit var detailWebView: WebView
    private lateinit var pageLabel: TextView
    private lateinit var dateButton: Button
    private lateinit var homeScreen: LinearLayout
    private lateinit var readerScreen: LinearLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var pageSpinner: Spinner
    private lateinit var paperList: LinearLayout
    private val paperViewer: PaperViewer = WebPaperViewer()
    private val papers = Papers.all
    private val paperHomeItems = linkedMapOf<NewspaperId, PaperHomeItem>()
    private val availabilityWebViews = linkedMapOf<NewspaperId, WebView>()
    private val availabilityCheckDates = mutableMapOf<NewspaperId, String>()
    private val availabilityAttempts = mutableMapOf<NewspaperId, Int>()
    private val papersToOpenWhenAvailable = mutableSetOf<NewspaperId>()
    private val downloadExecutor = Executors.newFixedThreadPool(2)
    private val pageDownloadLock = Any()
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
    private var viewingStoryContent = false
    private var displayedPageId: String? = null
    private var displayedEditionDate: String? = null
    private var displayedArticleId: String? = null
    private var viewingArticleImage = false
    private var paperPageReady = false
    private var shuttingDown = false
    private var availableEdition: NewspaperEdition? = null
    private var pageAdapter: ArrayAdapter<String>? = null
    private var updatingPagePicker = false
    private var editionInProgress: NewspaperEdition? = null
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
    private var jangVerificationGeneration = 0
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
    private val prefetchingImageIds = mutableSetOf<String>()
    private val pendingArticleImageIds = mutableSetOf<String>()
    private var pageDownloadsRemaining = 0
    private var storyScanComplete = false
    private var pageDownloadFailed = false
    private val dateFormat = SimpleDateFormat("dd_MM_yyyy", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createReaderView()
        createPaperAvailabilityWebViews()
        DailyEditionCheckScheduler.schedule(this)
        NotificationPermission.requestIfNeeded(this)
        checkPaperAvailabilities()
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
                        alpha = 0f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                val date = availabilityCheckDates[paper.id] ?: return
                                val expectedHost = Uri.parse(paper.availabilityUrl(date)).host
                                val actualHost = Uri.parse(url).host
                                if (paper.isAvailabilityPageUrl(url, date) ||
                                    actualHost == expectedHost || actualHost == "www.$expectedHost"
                                ) {
                                    readPaperAvailability(paper, view, date)
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError
                            ) {
                                if (!request.isForMainFrame) return
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
                val directory = paper.editionDirectory(File(filesDir, "editions"), requestedDate)
                val metadata = File(directory, "edition.json")
                if (metadata.isFile) {
                    try {
                        val edition = NewspaperEdition.readMetadata(metadata)
                        require(edition.paperId == paper.id) {
                            "Saved ${paper.id.displayName} edition metadata is invalid."
                        }
                        if (requestedDate == selectedDateKey()) {
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
                if (availabilityCheckDates[paper.id] == requestedDate) {
                    if (openWhenAvailable) papersToOpenWhenAvailable += paper.id
                    setPaperStatus(paper, "Checking availability…", Color.DKGRAY)
                    return
                }
                if (!NewspaperAvailability.checksAreAllowed(requestedDate)) {
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
                setPaperStatus(paper, "Checking availability…", Color.DKGRAY)
                val browser = availabilityWebViews[paper.id]
                    ?: throw IllegalStateException("No availability browser is registered for ${paper.id}.")
                browser.loadUrl(paper.availabilityUrl(requestedDate))
    }

    private fun readPaperAvailability(paper: Paper, browser: WebView, date: String) {
                if (availabilityCheckDates[paper.id] != date) return
                browser.evaluateJavascript(paper.availabilityDiscoveryScript(date)) { result ->
                    if (availabilityCheckDates[paper.id] != date) return@evaluateJavascript
                    try {
                        val json = JSONTokener(result).nextValue() as? String
                            ?: throw IllegalStateException("Could not read availability data.")
                        val response = JSONObject(json)
                        when (response.optString("error")) {
                            "VERIFYING" -> {
                                val attempt = availabilityAttempts[paper.id] ?: 0
                                if (attempt < MAX_AVAILABILITY_ATTEMPTS) {
                                    availabilityAttempts[paper.id] = attempt + 1
                                    browser.postDelayed({
                                        readPaperAvailability(paper, browser, date)
                                    }, AVAILABILITY_RETRY_MILLIS)
                                } else {
                                    failPaperAvailability(paper, date, "Request verification timed out.")
                                }
                                return@evaluateJavascript
                            }
                            "NOT_AVAILABLE" -> {
                                availabilityCheckDates.remove(paper.id)
                                papersToOpenWhenAvailable.remove(paper.id)
                                setPaperStatus(paper, "Not available", Color.GRAY)
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
                        availabilityCheckDates.remove(paper.id)
                        availabilityAttempts.remove(paper.id)
                        setPaperStatus(paper, "Available", Color.rgb(30, 125, 70))
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
                        if (papersToOpenWhenAvailable.remove(paper.id) && date == selectedDateKey()) {
                            openEdition(edition)
                        }
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
                availabilityCheckDates.remove(paper.id)
                availabilityAttempts.remove(paper.id)
                papersToOpenWhenAvailable.remove(paper.id)
                setPaperStatus(paper, "Could not check availability", Color.RED)
                showMessage("${paper.id.displayName}: $message")
    }

    private fun setPaperStatus(paper: Paper, text: String, color: Int) {
                paperHomeItems[paper.id]?.status?.apply {
                    this.text = text
                    setTextColor(color)
                }
    }

    private fun checkPaperAvailabilities() {
        papers.forEach { paper -> checkPaperAvailability(paper) }
    }

    private fun createReaderView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        homeScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(245, 245, 245))
        }
        dateButton = button("") { chooseDate() }
        val title = TextView(this).apply {
            text = "Newspapers"
            textSize = 20f
            setTextColor(Color.rgb(34, 50, 70))
            setPadding(0, dp(10), 0, dp(4))
        }
        pageLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(6))
        }
        paperList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        papers.forEach { paper ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setOnClickListener { openPaper(paper) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val name = TextView(this).apply {
                text = paper.id.displayName
                textSize = 18f
                setTextColor(Color.rgb(34, 50, 70))
            }
            val status = TextView(this).apply {
                text = "Checking availability…"
                textSize = 14f
                setTextColor(Color.DKGRAY)
            }
            val downloadIndicator = ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleSmall
            ).apply {
                visibility = View.GONE
                isIndeterminate = true
            }
            info.addView(name)
            info.addView(status)
            row.addView(
                info,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(downloadIndicator, LinearLayout.LayoutParams(dp(32), dp(32)))
            paperList.addView(row)
            paperHomeItems[paper.id] = PaperHomeItem(paper, status, downloadIndicator)
        }

        homeScreen.addView(dateButton)
        homeScreen.addView(title)
        homeScreen.addView(paperList)

        readerScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val pageControls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        pageControls.addView(button("Papers") { showHome() })
        pageControls.addView(button("Prev") {
            val pages = readerEdition()?.readingOrder.orEmpty()
            val index = pages.indexOfFirst { it.id.endsWith("_${pageNumber.toString().padStart(3, '0')}") }
            if (index > 0) openReaderPage(pages[index - 1])
        })
        pageSpinner = Spinner(this)
        pageAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        pageAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        pageSpinner.adapter = pageAdapter
        pageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (updatingPagePicker) return
                val page = readerEdition()?.readingOrder?.getOrNull(position) ?: return
                if (readerMode && page.id.takeLast(3).toIntOrNull() != pageNumber) {
                    openReaderPage(page)
                }
            }
        }
        pageControls.addView(pageSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        pageControls.addView(button("Next") {
            val pages = readerEdition()?.readingOrder.orEmpty()
            val index = pages.indexOfFirst { it.id.endsWith("_${pageNumber.toString().padStart(3, '0')}") }
            if (index >= 0 && index < pages.lastIndex) openReaderPage(pages[index + 1])
        })
        readerScreen.addView(pageLabel)
        readerScreen.addView(pageControls)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
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
                    if (displayedPageId != null && !viewingStoryContent) {
                        paperPageReady = true
                        updateRenderedHotspots()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    if (Papers.all.any { Uri.parse(it.offlineOrigin).host == request.url.host }) {
                        openOfflineTarget(request.url)
                        return true
                    }
                    return request.url.scheme !in setOf("file", "data", "about")
                }
            }
        }
        inspectionWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        inspectionWebView.webViewClient = inspectionClient()
        jangWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        jangWebView.webViewClient = jangInspectionClient()
        detailWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            alpha = 0f
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
                                    paperViewer.renderArticle(
                                        request.edition,
                                        article,
                                        Papers[request.edition.paperId],
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
                                paperViewer.renderArticle(
                                    request.edition,
                                    article,
                                    Papers[request.edition.paperId],
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
            jangWebView,
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
        pageLabel.text = ""
        updateDateButton()
        updatePageLabel()
    }

    private fun inspectionClient() = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val uri = Uri.parse(url)
            val requestedPage = onDemandPage
            if (requestedPage != null &&
                !Papers.forEdition(requestedPage.first).requiresPageVerification &&
                Papers.forEdition(requestedPage.first).isPageUrl(
                    url,
                    requestedPage.first.date,
                    requestedPage.second.id
                )
            ) {
                view.postDelayed({
                    if (onDemandPage == requestedPage) {
                        collectOnDemandPageHotspots(requestedPage.first, requestedPage.second)
                    }
                }, 250)
                return
            }
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
            if (requestedPage != null && onDemandPage != requestedPage) return
            val key = expectedUrl
            if (attempt == 0 && jangVerificationKey != key) {
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
                if (requestedPage != null && onDemandPage != requestedPage) return@evaluateJavascript
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
                    jangWebView.postDelayed(onReady, 300)
                } catch (error: Exception) {
                    jangVerificationKey = null
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
            if (!request.isForMainFrame || request.url.host != "e.jang.com.pk") return
            if (request.url.path?.contains("/cdn-cgi/") == true) return
            if (onDemandPage?.first?.paperId == NewspaperId.JANG_LAHORE) {
                onDemandPage = null
                showMessage("Could not load Jang page details: ${error.description}")
            }
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
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedDate.set(year, month, day)
                pageNumber = 1
                showHome()
                availableEdition = null
                papers.forEach { setPaperStatus(it, "Checking availability…", Color.DKGRAY) }
                updateDateButton()
                pageAdapter?.clear()
                availabilityWebViews.values.forEach(WebView::stopLoading)
                availabilityCheckDates.clear()
                availabilityAttempts.clear()
                papersToOpenWhenAvailable.clear()
                checkPaperAvailabilities()
            },
            selectedDate.get(Calendar.YEAR),
            selectedDate.get(Calendar.MONTH),
            selectedDate.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDateButton() {
        dateButton.text = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
            .format(selectedDate.time)
    }

    private fun showHome() {
        readerMode = false
        homeScreen.visibility = View.VISIBLE
        readerScreen.visibility = View.GONE
        webView.visibility = View.GONE
    }

    private fun openEdition(edition: NewspaperEdition) {
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
        val adapter = pageAdapter ?: return
        updatingPagePicker = true
        adapter.clear()
        adapter.addAll(edition.readingOrder.map { it.title.ifBlank { "Page ${it.id.takeLast(3)}" } })
        adapter.notifyDataSetChanged()
        val index = edition.readingOrder.indexOfFirst {
            it.id.takeLast(3).toIntOrNull() == pageNumber
        }
        if (index >= 0) pageSpinner.setSelection(index, false)
        updatingPagePicker = false
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
        val position = edition.readingOrder.indexOfFirst { it.id == page.id }
        if (position >= 0 && pageSpinner.selectedItemPosition != position) {
            pageSpinner.setSelection(position, false)
        }
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
            <style>body{font:18px sans-serif;padding:24px;color:#333}
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
                } ?: break
                try {
                    request.edition.downloadPageImage(request.directory, request.page.id)
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
                            }
                            showMessage(error.message ?: "Could not download ${request.page.title}.")
                            maybeCompleteEdition(request.edition)
                        } else if (isRequestedPageVisible(request)) {
                            showPageLoadFailure(request.page, error.message)
                            showMessage(error.message ?: "Could not download ${request.page.title}.")
                        }
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
        if (page.id in edition.processedPageIds || pageScanInProgress ||
            onDemandPage != null
        ) return
        onDemandPage = edition to page
        if (Papers.forEdition(edition).requiresPageVerification) {
            jangWebView.loadUrl(
                Papers.forEdition(edition).pageUrl(edition.date, page.id)
            )
        } else {
            inspectionWebView.loadUrl(
                Papers.forEdition(edition).pageUrl(edition.date, page.id),
                mapOf("Referer" to "https://epaper.dawn.com/")
            )
        }
    }

    private fun prefetchPageStories(edition: NewspaperEdition, page: NewspaperPage) {
        if (edition.date != dateFormat.format(Calendar.getInstance().time)) return
        val directory = editionDirectory(edition)
        val pageHotspots = edition.hotspots.filter { it.pageId == page.id }
        if (pageHotspots.isEmpty()) return

        pageHotspots.filter { it.type == NewspaperHotspotType.TEXT }
            .map { it.targetId }
            .distinct()
            .filterNot { id ->
                edition.articles.any { it.id == id } ||
                    activeTextStory?.id == id ||
                    pendingTextStories.any { it.id == id }
            }
            .forEach { id ->
                pendingTextStories.addLast(TextStoryRequest(edition, directory, id))
            }
        startNextTextStory()

        val missingImages = pageHotspots.asSequence()
            .filter { it.type == NewspaperHotspotType.IMAGE }
            .map { it.targetId }
            .distinct()
            .filter { id ->
                !edition.hasFullImage(directory, id) &&
                    id !in prefetchingImageIds
            }
            .toList()
        if (missingImages.isEmpty()) return
        prefetchingImageIds.addAll(missingImages)
        refreshDownloadIndicator()
        downloadExecutor.execute {
            missingImages.forEach { id ->
                try {
                    edition.downloadFullImages(directory, listOf(id)) { _, _ -> }
                    runOnUiThread {
                        try {
                            val metadata = File(directory, "edition.json")
                            val latest = if (metadata.isFile) NewspaperEdition.readMetadata(metadata) else edition
                            val updated = latest.copy(
                                fullImageIds = (latest.fullImageIds + id).distinct()
                            )
                            persistEdition(updated, directory)
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
                                ) {
                                    showOfflineImage(updated, directory, id)
                                }
                            }
                        } catch (error: Exception) {
                            if (pendingArticleImageIds.remove(id)) {
                                showMessage(error.message ?: "Could not save this story image.")
                            }
                            if (onDemandImageId == id) {
                                onDemandImageId = null
                                storyLoadingDialog?.dismiss()
                                storyLoadingDialog = null
                                showMessage(error.message ?: "Could not save this image.")
                                returnToCurrentPaperPage()
                            } else {
                                showMessage(error.message ?: "Could not save prefetched image $id.")
                            }
                        } finally {
                            prefetchingImageIds.remove(id)
                            refreshDownloadIndicator()
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        prefetchingImageIds.remove(id)
                        refreshDownloadIndicator()
                        if (pendingArticleImageIds.remove(id)) {
                            showMessage(error.message ?: "Could not prefetch this story image.")
                        }
                        if (onDemandImageId == id) {
                            onDemandImageId = null
                            storyLoadingDialog?.dismiss()
                            storyLoadingDialog = null
                            showMessage(error.message ?: "Could not download this image.")
                            returnToCurrentPaperPage()
                        } else {
                            showMessage(error.message ?: "Could not prefetch image $id.")
                        }
                    }
                }
            }
        }
    }

    private fun startNextTextStory() {
        if (activeTextStory != null || pendingTextStories.isEmpty()) return
        val request = pendingTextStories.removeFirst()
        activeTextStory = request
        refreshDownloadIndicator()
        val url = Papers.forEdition(request.edition).articleUrl(request.id)
        detailWebView.loadUrl(url)
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

    private fun collectOnDemandPageHotspots(edition: NewspaperEdition, page: NewspaperPage) {
        val fetcher = Papers.forEdition(edition)
        val source = if (fetcher.requiresPageVerification) jangWebView else inspectionWebView
        source.evaluateJavascript(fetcher.hotspotExtractionScript(edition.date, page.id)) { result ->
            try {
                if (onDemandPage != (edition to page)) return@evaluateJavascript
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
                downloadEdition.downloadFullImages(directory, imageIds) { _, _ -> }
                downloadEdition.downloadArticleImages(directory)
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
            updateRenderedHotspots()
            prefetchPageStories(edition, page)
            return
        }
        viewingStoryContent = false
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
                    paperViewer.renderArticle(
                        edition,
                        article,
                        Papers.forEdition(edition),
                        directory
                    ),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
            "article" -> {
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
                    paperViewer.renderArticle(
                        edition,
                        article,
                        Papers.forEdition(edition),
                        directory
                    ),
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
            a{color:#fff}img{display:block;width:100%;height:calc(100% - 48px);margin:0 auto;object-fit:contain}
            #error{padding:24px;text-align:center}</style></head>
            <body><header><a href="${paper.offlineOrigin}article/${article.id}">Back to story text</a></header>
            <img src="${htmlEscape(displayedImageUrl)}" alt="${htmlEscape(article.title)}"
                onerror="this.hidden=true;document.getElementById('error').hidden=false">
            <div id="error" hidden>Image could not be displayed.</div></body></html>
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
        viewingStoryContent = true
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;background:#111;color:#fff;font:16px sans-serif}
            header{height:48px;display:flex;align-items:center;padding:0 12px}
            a{color:#fff}img{display:block;width:100%;height:calc(100% - 48px);margin:0 auto;object-fit:contain}
            #error{padding:24px;text-align:center}</style></head>
            <body><header><a href="${paper.offlineOrigin}page/$sourcePage">Back to paper</a></header>
            <img src="images/${htmlEscape(imageFile.name)}" alt="Full-size newspaper image"
                onerror="this.hidden=true;document.getElementById('error').hidden=false">
            <div id="error" hidden>Image could not be displayed.</div></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    private fun showArticleImage(edition: NewspaperEdition, directory: File, id: String) {
        val baseUrl = Uri.fromFile(directory).toString().trimEnd('/') + "/"
        viewingStoryContent = true
        viewingArticleImage = true
        displayedArticleId = id
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;background:#111;color:#fff;font:16px sans-serif}
            header{height:48px;display:flex;align-items:center;padding:0 12px}
            a{color:#fff}img{display:block;width:100%;height:calc(100% - 48px);margin:0 auto;object-fit:contain}
            #error{padding:24px;text-align:center}</style></head>
            <body><header><a href="${Papers.forEdition(edition).offlineOrigin}article/$id">Back to story text</a></header>
            <img src="images/$id.jpg" alt="${htmlEscape(edition.articles.firstOrNull { it.id == id }?.title ?: "Story image")}"
                onerror="this.hidden=true;document.getElementById('error').hidden=false">
            <div id="error" hidden>Image could not be displayed.</div></body></html>
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
                edition.downloadFullImages(directory, listOf(id)) { _, _ -> }
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
        if (activeTextStory?.id != id) {
            pendingTextStories.removeAll { it.id == id }
            pendingTextStories.addFirst(request)
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
                edition.downloadFullImages(directory, listOf(id)) { _, _ -> }
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
        if (!complete && marker.exists() && !marker.delete()) {
            throw IllegalStateException("Could not update the edition details.")
        }
    }

    private fun htmlEscape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private fun updatePageLabel() {
        val date = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(selectedDate.time)
        val page = "$date  ·  Page ${pageNumber.toString().padStart(3, '0')}"
        val title = readerEdition()?.readingOrder?.firstOrNull {
            it.id.takeLast(3).toIntOrNull() == pageNumber
        }?.title
        pageLabel.text = listOfNotNull(page, title?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            minHeight = dp(40)
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { onClick() }
        }

    private fun showMessage(message: String) {
        if (shuttingDown || isFinishing || isDestroyed || Thread.currentThread().isInterrupted) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun refreshDownloadIndicator() {
        val date = selectedDateKey()
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
            val editionDownloadActive = editionInProgress?.let {
                it.paperId == paperId && it.date == date
            } == true
            item.downloadIndicator.visibility = if (
                pageDownloadActive || storyDownloadActive || editionDownloadActive
            ) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val MAX_AVAILABILITY_ATTEMPTS = 20
        const val AVAILABILITY_RETRY_MILLIS = 1_500L
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        shuttingDown = true
        downloadExecutor.shutdownNow()
        webView.destroy()
        inspectionWebView.destroy()
        jangWebView.destroy()
        detailWebView.destroy()
        availabilityWebViews.values.forEach(WebView::destroy)
        super.onDestroy()
    }

    @Deprecated("Use the system back dispatcher in newer Android versions.")
    override fun onBackPressed() {
        if (readerMode) {
            if (viewingStoryContent && viewingArticleImage) {
                val edition = offlineEdition
                val directory = offlineDirectory
                val article = edition?.articles?.firstOrNull { it.id == displayedArticleId }
                if (edition != null && directory != null && article != null) {
                    viewingArticleImage = false
                    val newspaper = Papers.forEdition(edition)
                    webView.loadDataWithBaseURL(
                        newspaper.offlineOrigin,
                        paperViewer.renderArticle(
                            edition,
                            article,
                            newspaper,
                            directory
                        ),
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
    val id: String
)
