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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var inspectionWebView: WebView
    private lateinit var detailWebView: WebView
    private lateinit var pageLabel: TextView
    private lateinit var dateButton: Button
    private lateinit var dawnStatusLabel: TextView
    private lateinit var dawnDownloadIndicator: ProgressBar
    private lateinit var homeScreen: LinearLayout
    private lateinit var readerScreen: LinearLayout
    private lateinit var pageSpinner: Spinner
    private val downloadExecutor = Executors.newFixedThreadPool(2)
    private val pageDownloadLock = Any()
    private val pageDownloadQueue = PriorityQueue<PageDownloadRequest>(
        compareBy<PageDownloadRequest> { it.priority }.thenBy { it.sequence }
    )
    private val queuedPageIds = mutableSetOf<String>()
    private var pageDownloadWorkerRunning = false
    private var pageDownloadSequence = 0L
    private var selectedDate = Calendar.getInstance()
    private var pageNumber = 1
    private var readerMode = false
    private var viewingStoryContent = false
    private var displayedPageId: String? = null
    private var displayedEditionDate: String? = null
    private var paperPageReady = false
    private var availableEdition: DawnEdition? = null
    private var pageAdapter: ArrayAdapter<String>? = null
    private var updatingPagePicker = false
    private var editionInProgress: DawnEdition? = null
    private var activeEditionDirectory: File? = null
    private var editionPageIndex = 0
    private var textStoryIds: List<String> = emptyList()
    private var fullImageIds: List<String> = emptyList()
    private var collectedHotspots: List<DawnHotspot> = emptyList()
    private var collectedArticles: List<DawnArticle> = emptyList()
    private var processedPageIds: List<String> = emptyList()
    private var storyIndex = 0
    private var archivingStories = false
    private var processingContentId: String? = null
    private var offlineEdition: DawnEdition? = null
    private var offlineDirectory: File? = null
    private var latestEditionStatus: String? = null
    private var checkedEditionDate: String? = null
    private var availabilityCheckInProgress = false
    private var pageScanInProgress = false
    private var storyScanInProgress = false
    private var onDemandStoryId: String? = null
    private var onDemandImageId: String? = null
    private var onDemandEdition: DawnEdition? = null
    private var onDemandDirectory: File? = null
    private var onDemandPage: Pair<DawnEdition, DawnPage>? = null
    private var storyLoadingDialog: ProgressDialog? = null
    private var activeTextStory: TextStoryRequest? = null
    private val pendingTextStories = ArrayDeque<TextStoryRequest>()
    private val prefetchingImageIds = mutableSetOf<String>()
    private var pageDownloadsRemaining = 0
    private var storyScanComplete = false
    private var pageDownloadFailed = false
    private val dateFormat = SimpleDateFormat("dd_MM_yyyy", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createReaderView()
        DailyEditionCheckScheduler.schedule(this)
        NotificationPermission.requestIfNeeded(this)
        checkAvailability()
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
        val paperRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setOnClickListener { openDawnEdition() }
        }
        val dawnTitle = TextView(this).apply {
            text = "Dawn"
            textSize = 18f
            setTextColor(Color.rgb(34, 50, 70))
        }
        dawnStatusLabel = TextView(this).apply {
            text = "Checking availability…"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        val dawnInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        dawnInfo.addView(dawnTitle)
        dawnInfo.addView(dawnStatusLabel)
        dawnDownloadIndicator = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        paperRow.addView(
            dawnInfo,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        paperRow.addView(dawnDownloadIndicator, LinearLayout.LayoutParams(dp(32), dp(32)))
        val jangRow = TextView(this).apply {
            text = "Jang\nNot available yet"
            textSize = 16f
            setTextColor(Color.GRAY)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        homeScreen.addView(dateButton)
        homeScreen.addView(title)
        homeScreen.addView(paperRow)
        homeScreen.addView(jangRow)

        readerScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val pageControls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        pageControls.addView(button("Papers") { showHome() })
        pageControls.addView(button("Prev") {
            val pages = availableEdition?.readingOrder.orEmpty()
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
                val page = availableEdition?.readingOrder?.getOrNull(position) ?: return
                if (readerMode && page.id.takeLast(3).toIntOrNull() != pageNumber) {
                    openReaderPage(page)
                }
            }
        }
        pageControls.addView(pageSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        pageControls.addView(button("Next") {
            val pages = availableEdition?.readingOrder.orEmpty()
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
                    if (request.url.host == "offline.dawn") {
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
                                "https://offline.dawn/",
                                renderArticle(article),
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

        val contentFrame = FrameLayout(this)
        contentFrame.addView(
            inspectionWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        contentFrame.addView(
            detailWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        contentFrame.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webView.visibility = View.GONE
        readerScreen.addView(contentFrame, LinearLayout.LayoutParams(
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
            if (availabilityCheckInProgress && uri.host == "epaper.dawn.com") {
                val requestedDate = checkedEditionDate ?: return
                if (uri.getQueryParameter("page")?.startsWith("${requestedDate}_") != true) return
                discoverEdition()
                return
            }
            val requestedPage = onDemandPage
            if (requestedPage != null && uri.host == "e.dawn.com" &&
                uri.lastPathSegment?.removeSuffix(".html") == requestedPage.second.id
            ) {
                view.postDelayed({
                    if (onDemandPage == requestedPage) collectOnDemandPageHotspots(requestedPage.first, requestedPage.second)
                }, 250)
                return
            }
            val edition = editionInProgress ?: return
            if (pageScanInProgress && uri.host == "e.dawn.com") {
                val page = edition.readingOrder.getOrNull(editionPageIndex) ?: return
                if (uri.lastPathSegment?.removeSuffix(".html") == page.id &&
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

    private fun loadPage() {
        updatePageLabel()
        val edition = availableEdition ?: return
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
                updateDateButton()
                pageAdapter?.clear()
                checkAvailability()
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

    private fun openDawnEdition() {
        val edition = availableEdition
        if (edition == null) {
            showMessage(if (availabilityCheckInProgress) "Checking Dawn availability…" else "Dawn is not available for this date.")
            return
        }
        readerMode = true
        homeScreen.visibility = View.GONE
        readerScreen.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
        val directory = File(File(filesDir, "editions"), edition.date)
        val isToday = edition.date == dateFormat.format(Calendar.getInstance().time)
        if (isToday && editionInProgress?.date != edition.date &&
            !File(directory, "complete").isFile
        ) {
            downloadEdition(edition)
        }
        pageNumber = edition.readingOrder.firstOrNull()?.id?.takeLast(3)?.toIntOrNull() ?: 1
        updatePagePicker(edition)
        loadPage()
    }

    private fun updatePagePicker(edition: DawnEdition) {
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

    private fun openReaderPage(page: DawnPage) {
        val edition = availableEdition ?: return
        val directory = File(File(filesDir, "editions"), edition.date)
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
                "<html><body style='font:18px sans-serif;padding:24px'>Loading ${htmlEscape(page.title)}…</body></html>",
                "text/html",
                "UTF-8"
            )
            requestPageDownload(edition, directory, page, priority = -100)
        }
        ensurePageHotspots(edition, page)
        prefetchPageStories(edition, page)
    }

    private fun checkAvailability() {
        val requestedDate = dateFormat.format(selectedDate.time)
        checkedEditionDate = requestedDate
        refreshDownloadIndicator()
        availableEdition = null
        val directory = File(File(filesDir, "editions"), requestedDate)
        val metadata = File(directory, "edition.json")
        if (metadata.isFile) {
            try {
                val savedEdition = DawnEdition.readMetadata(metadata)
                availableEdition = savedEdition
                updatePagePicker(savedEdition)
                dawnStatusLabel.text = "Available"
                dawnStatusLabel.setTextColor(Color.rgb(30, 125, 70))
                refreshDownloadIndicator()
                availabilityCheckInProgress = false
                updatePageLabel()
                if (requestedDate == dateFormat.format(Calendar.getInstance().time) &&
                    !File(directory, "complete").isFile
                ) downloadEdition(savedEdition)
                return
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not read the saved edition.")
            }
        }
        availabilityCheckInProgress = true
        latestEditionStatus = null
        dawnStatusLabel.text = "Checking availability…"
        dawnStatusLabel.setTextColor(Color.DKGRAY)
        pageLabel.text = "Checking Dawn for ${requestedDate.replace('_', '-')}"
        inspectionWebView.loadUrl("https://epaper.dawn.com/?page=${requestedDate}_001")
    }

    private fun discoverEdition() {
        val requestedDate = checkedEditionDate ?: return
        val script = """
            (function() {
                const requestedDate = '$requestedDate';
                const datePattern = /^\d{2}_\d{2}_\d{4}_001$/;
                const datePicker = Array.from(document.querySelectorAll('select'))
                    .find(select => Array.from(select.options).some(option => datePattern.test(option.value)));
                if (!datePicker || !datePicker.options.length) {
                    return JSON.stringify({error: 'Could not find Dawn edition dates on this page.'});
                }
                const availableDates = Array.from(datePicker.options)
                    .map(option => option.value.substring(0, 10));
                const latestDate = datePicker.options[0].value.substring(0, 10);
                if (!availableDates.includes(requestedDate)) {
                    return JSON.stringify({error: 'NOT_AVAILABLE', latestDate: latestDate});
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
                if (!pages.length) {
                    return JSON.stringify({error: 'NOT_AVAILABLE', latestDate: latestDate});
                }
                return JSON.stringify({
                    date: requestedDate,
                    latestDate: latestDate,
                    pages: pages
                });
            })();
        """.trimIndent()
        inspectionWebView.evaluateJavascript(script) { result ->
            try {
                if (checkedEditionDate != requestedDate) return@evaluateJavascript
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read the Dawn edition page list.")
                val response = JSONObject(json)
                availabilityCheckInProgress = false
                if (response.optString("error") == "NOT_AVAILABLE") {
                    latestEditionStatus = "Not available"
                    dawnStatusLabel.text = "Not available"
                    dawnStatusLabel.setTextColor(Color.GRAY)
                    pageLabel.text = "Dawn is not available for ${requestedDate.replace('_', '-')}"
                    return@evaluateJavascript
                } else if (response.has("error")) {
                    val message = response.getString("error")
                    latestEditionStatus = message
                    dawnStatusLabel.text = "Could not check availability"
                    dawnStatusLabel.setTextColor(Color.RED)
                    pageLabel.text = message
                } else {
                    val edition = DawnEdition.fromJson(json)
                    availableEdition = edition
                    updatePagePicker(edition)
                    val directory = File(File(filesDir, "editions"), edition.date)
                    val metadata = File(directory, "edition.json")
                    val savedEdition = if (metadata.isFile) {
                        try {
                            DawnEdition.readMetadata(metadata)
                        } catch (error: Exception) {
                            showMessage(error.message ?: "Saved edition metadata is invalid and will be rebuilt.")
                            null
                        }
                    } else null
                    val pagesSaved = savedEdition != null &&
                        File(directory, "complete").isFile &&
                        savedEdition.pages.all { savedEdition.hasPageImage(directory, it.id) }
                    if (pagesSaved) {
                        availableEdition = savedEdition
                        updatePagePicker(savedEdition!!)
                        dawnStatusLabel.text = "Available"
                        dawnStatusLabel.setTextColor(Color.rgb(30, 125, 70))
                    } else {
                        dawnStatusLabel.text = "Available"
                        dawnStatusLabel.setTextColor(Color.rgb(30, 125, 70))
                        if (requestedDate == dateFormat.format(Calendar.getInstance().time)) {
                            downloadEdition(edition)
                        }
                    }
                }
                updatePageLabel()
            } catch (error: Exception) {
                availabilityCheckInProgress = false
                latestEditionStatus = error.message ?: "Could not check Dawn's edition."
                dawnStatusLabel.text = "Could not check availability"
                dawnStatusLabel.setTextColor(Color.RED)
                updatePageLabel()
                showMessage(error.message ?: "Could not read Dawn's edition page list.")
            }
        }
    }

    private fun downloadEdition(edition: DawnEdition) {
        if (editionInProgress?.date == edition.date) return

        val directory = File(File(filesDir, "editions"), edition.date)
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
        edition: DawnEdition,
        directory: File,
        pages: List<DawnPage>
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
        edition: DawnEdition,
        directory: File,
        page: DawnPage,
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
                    } ?: run {
                        pageDownloadWorkerRunning = false
                        pageDownloadInProgressId = null
                        null
                    }
                } ?: break
                try {
                    request.edition.downloadPageImage(request.directory, request.page.id)
                    runOnUiThread {
                        val isCurrentEdition = editionInProgress === request.edition
                        if (isCurrentEdition) {
                            availableEdition = request.edition.copy(
                                fullImageIds = fullImageIds,
                                hotspots = collectedHotspots,
                                articles = collectedArticles
                            )
                            pageDownloadsRemaining = (pageDownloadsRemaining - 1).coerceAtLeast(0)
                        }
                        if (readerMode && !viewingStoryContent &&
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
                            showMessage(error.message ?: "Could not download ${request.page.title}.")
                            maybeCompleteEdition(request.edition)
                        } else if (selectedDateKey() == request.edition.date) {
                            showMessage(error.message ?: "Could not download ${request.page.title}.")
                        }
                    }
                } finally {
                    synchronized(pageDownloadLock) { pageDownloadInProgressId = null }
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
                storyScanComplete = true
                maybeCompleteEdition(edition)
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
        val parts = edition.date.split('_')
        inspectionWebView.loadUrl(
            "https://e.dawn.com/${parts[2]}/${parts[1]}/${parts[0]}/pages/${page.id}.html",
            mapOf("Referer" to "https://epaper.dawn.com/")
        )
    }

    private fun collectPageHotspots(edition: DawnEdition, page: DawnPage) {
        val script = """
            JSON.stringify(Array.from(document.querySelectorAll('area[href]')).flatMap(area => {
                const href = area.getAttribute('href') || '';
                const text = href.match(/StoryText=([0-9_]+)/i);
                const image = href.match(/StoryImage=([0-9_]+)/i);
                const match = text || image;
                if (!match) return [];
                const coordinates = (area.getAttribute('coords') || '')
                    .split(/[;,]/).map(value => value.trim()).filter(Boolean)
                    .map(Number).filter(Number.isFinite);
                return [{targetId: match[1], type: text ? 'TEXT' : 'IMAGE',
                    label: area.alt || area.title || '', shape: area.shape || 'rect',
                    coordinates: coordinates}];
            }))
        """.trimIndent()
        inspectionWebView.evaluateJavascript(script) { result ->
            try {
                if (editionInProgress !== edition) return@evaluateJavascript
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read story areas from page ${page.id}.")
                val entries = org.json.JSONArray(json)
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    val targetId = entry.getString("targetId")
                    if (!targetId.startsWith("${edition.date}_")) continue
                    val type = DawnHotspotType.valueOf(entry.getString("type"))
                    val coordinates = entry.getJSONArray("coordinates")
                    if (coordinates.length() < 4) continue
                    val hotspot = DawnHotspot(
                        page.id,
                        targetId,
                        type,
                        entry.optString("label"),
                        entry.optString("shape", "rect"),
                        (0 until coordinates.length()).map { coordinates.getInt(it) }
                    )
                    if (collectedHotspots.none {
                            it.pageId == hotspot.pageId && it.targetId == hotspot.targetId
                        }
                    ) {
                        collectedHotspots = collectedHotspots + hotspot
                        when (type) {
                            DawnHotspotType.TEXT ->
                                if (targetId !in textStoryIds) textStoryIds = textStoryIds + targetId
                            DawnHotspotType.IMAGE ->
                                if (targetId !in fullImageIds) fullImageIds = fullImageIds + targetId
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

    private fun ensurePageHotspots(edition: DawnEdition, page: DawnPage) {
        if (page.id in edition.processedPageIds || pageScanInProgress ||
            onDemandPage != null
        ) return
        onDemandPage = edition to page
        val parts = edition.date.split('_')
        inspectionWebView.loadUrl(
            "https://e.dawn.com/${parts[2]}/${parts[1]}/${parts[0]}/pages/${page.id}.html",
            mapOf("Referer" to "https://epaper.dawn.com/")
        )
    }

    private fun prefetchPageStories(edition: DawnEdition, page: DawnPage) {
        if (edition.date != dateFormat.format(Calendar.getInstance().time)) return
        val directory = File(File(filesDir, "editions"), edition.date)
        val pageHotspots = edition.hotspots.filter { it.pageId == page.id }
        if (pageHotspots.isEmpty()) return

        pageHotspots.filter { it.type == DawnHotspotType.TEXT }
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
            .filter { it.type == DawnHotspotType.IMAGE }
            .map { it.targetId }
            .distinct()
            .filter { id ->
                !File(File(directory, "images"), "$id.jpg").isFile &&
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
                            val latest = if (metadata.isFile) DawnEdition.readMetadata(metadata) else edition
                            val updated = latest.copy(
                                fullImageIds = (latest.fullImageIds + id).distinct()
                            )
                            persistEdition(updated, directory)
                            updateEditionReferences(updated)
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
        detailWebView.loadUrl(
            "https://epaper.dawn.com/DetailImage.php?StoryImage=${request.id}"
        )
    }

    private fun saveStoryArticle(request: TextStoryRequest, article: DawnArticle) {
        val directory = request.directory
        try {
            if (editionInProgress?.date == request.edition.date) {
                collectedArticles = collectedArticles.filterNot { it.id == article.id } + article
                persistProgress(editionInProgress ?: request.edition)
                return
            }
            val metadata = File(directory, "edition.json")
            val latest = if (metadata.isFile) DawnEdition.readMetadata(metadata) else request.edition
            val updated = latest.copy(
                articles = latest.articles.filterNot { it.id == article.id } + article
            )
            persistEdition(updated, directory)
            updateEditionReferences(updated)
        } catch (error: Exception) {
            showMessage(error.message ?: "Could not save story ${article.id}.")
        }
    }

    private fun updateEditionReferences(edition: DawnEdition) {
        if (offlineEdition?.date == edition.date) offlineEdition = edition
        if (availableEdition?.date == edition.date) availableEdition = edition
    }

    private fun collectOnDemandPageHotspots(edition: DawnEdition, page: DawnPage) {
        val script = """
                JSON.stringify(Array.from(document.querySelectorAll('area[href]')).flatMap(area => {
                    const href = area.getAttribute('href') || '';
                    const text = href.match(/StoryText=([0-9_]+)/i);
                    const image = href.match(/StoryImage=([0-9_]+)/i);
                    const match = text || image;
                    if (!match) return [];
                    const coordinates = (area.getAttribute('coords') || '')
                        .split(/[;,]/).map(value => value.trim()).filter(Boolean)
                        .map(Number).filter(Number.isFinite);
                    return [{targetId: match[1], type: text ? 'TEXT' : 'IMAGE',
                        label: area.alt || area.title || '', shape: area.shape || 'rect',
                        coordinates: coordinates}];
                }))
        """.trimIndent()
        inspectionWebView.evaluateJavascript(script) { result ->
            try {
                if (onDemandPage != (edition to page)) return@evaluateJavascript
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read story areas from page ${page.id}.")
                val entries = org.json.JSONArray(json)
                val hotspots = (0 until entries.length()).mapNotNull { index ->
                    val entry = entries.getJSONObject(index)
                    val targetId = entry.getString("targetId")
                    if (!targetId.startsWith("${edition.date}_")) return@mapNotNull null
                    val coordinates = entry.getJSONArray("coordinates")
                    if (coordinates.length() < 4) return@mapNotNull null
                    DawnHotspot(
                        page.id,
                        targetId,
                        DawnHotspotType.valueOf(entry.getString("type")),
                        entry.optString("label"),
                        entry.optString("shape", "rect"),
                        (0 until coordinates.length()).map { coordinates.getInt(it) }
                    )
                }
                val directory = File(File(filesDir, "editions"), edition.date)
                if (!directory.exists() && !directory.mkdirs()) {
                    onDemandPage = null
                    showMessage("Could not save this page's clickable areas.")
                    return@evaluateJavascript
                }
                val savedMetadata = File(directory, "edition.json")
                val latest = if (savedMetadata.isFile) {
                    DawnEdition.readMetadata(savedMetadata)
                } else edition
                val updated = latest.copy(
                    hotspots = latest.hotspots.filterNot { it.pageId == page.id } + hotspots,
                    processedPageIds = (latest.processedPageIds + page.id).distinct()
                )
                updated.saveMetadata(directory)
                availableEdition = updated
                if (offlineEdition?.date == updated.date) offlineEdition = updated
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

    private fun downloadLinkedContent(edition: DawnEdition) {
        val directory = activeEditionDirectory ?: run {
            failEditionDownload("Edition storage is unavailable.")
            return
        }
        val imageIds = fullImageIds.filterNot { it in prefetchingImageIds }
        downloadExecutor.execute {
            try {
                edition.downloadFullImages(directory, imageIds) { _, _ -> }
                runOnUiThread {
                    if (editionInProgress !== edition) return@runOnUiThread
                    persistProgress(edition)
                    archivingStories = textStoryIds.isNotEmpty()
                    storyScanInProgress = archivingStories
                    storyIndex = 0
                    if (archivingStories) {
                        loadNextEditionPage()
                    } else {
                        storyScanComplete = true
                        maybeCompleteEdition(edition)
                    }
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

    private fun collectStoryText(edition: DawnEdition, id: String) {
        parseStoryText(inspectionWebView, id) { article ->
            if (editionInProgress !== edition) return@parseStoryText
            collectedArticles = collectedArticles.filterNot { it.id == id } + article
            storyIndex++
            persistProgress(edition)
            loadNextEditionPage()
        }
    }

    private fun parseStoryText(source: WebView, id: String, onArticle: (DawnArticle) -> Unit) {
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
                val article = DawnArticle(
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

    private fun completeEditionDownload(edition: DawnEdition) {
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
            availableEdition = savedEdition
            offlineEdition = savedEdition
            editionInProgress = null
            activeEditionDirectory = null
            archivingStories = false
            pageScanInProgress = false
            storyScanInProgress = false
            dawnStatusLabel.text = "Available"
            dawnStatusLabel.setTextColor(Color.rgb(30, 125, 70))
            refreshDownloadIndicator()
            updatePagePicker(savedEdition)
            updatePageLabel()
        } catch (error: Exception) {
            failEditionDownload(error.message ?: "Could not save edition details.")
        }
    }

    private fun failEditionDownload(message: String) {
        editionInProgress = null
        activeEditionDirectory = null
        archivingStories = false
        pageScanInProgress = false
        storyScanInProgress = false
        dawnStatusLabel.text = "Available"
        dawnStatusLabel.setTextColor(Color.rgb(180, 90, 20))
        refreshDownloadIndicator()
        showMessage(message)
        updatePageLabel()
    }

    private fun persistProgress(edition: DawnEdition) {
        val directory = activeEditionDirectory ?: return
        val progress = edition.copy(
            fullImageIds = fullImageIds,
            hotspots = collectedHotspots,
            articles = collectedArticles,
            processedPageIds = processedPageIds
        )
        progress.saveMetadata(directory)
        availableEdition = progress
        if (offlineEdition?.date == progress.date) offlineEdition = progress
        updatePagePicker(progress)
        if (readerMode && !viewingStoryContent && selectedDateKey() == progress.date) {
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

    private fun maybeCompleteEdition(edition: DawnEdition) {
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

    private fun openOfflinePage(edition: DawnEdition, page: DawnPage, directory: File) {
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
        val html = renderOfflinePage(page, pageHotspots)
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

    private fun renderOfflinePage(page: DawnPage, hotspots: List<DawnHotspot>): String {
        val areas = hotspots.joinToString("") { hotspot ->
            val route = if (hotspot.type == DawnHotspotType.TEXT) "text" else "image"
            """<area shape="${htmlEscape(hotspot.shape)}" coords="${hotspot.coordinates.joinToString(",")}" href="https://offline.dawn/$route/${hotspot.targetId}" alt="${htmlEscape(hotspot.label)}" title="${htmlEscape(hotspot.label)}">"""
        }
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{margin:0;background:#222;text-align:center}
            img{display:block;width:100%;height:auto;margin:auto}</style>
            </head><body><img id="page" src="${page.imageFileName}" usemap="#page-map"><map id="page-map" name="page-map">$areas</map>
            <script>
            (function(){const img=document.getElementById('page');
            function scale(){if(!img.naturalWidth)return;const sx=img.clientWidth/img.naturalWidth,sy=img.clientHeight/img.naturalHeight;
            document.querySelectorAll('area').forEach(a=>{if(!a.dataset.original)a.dataset.original=a.coords;const c=a.dataset.original.split(',').map(Number);
            a.coords=c.map((n,i)=>Math.round(n*(i%2?sy:sx))).join(',')})}
            window.updatePageHotspots=function(items){const map=document.getElementById('page-map');map.replaceChildren();
            items.forEach(item=>{const area=document.createElement('area');area.shape=item.shape;
            area.coords=item.coordinates.join(',');area.href='https://offline.dawn/'+item.route+'/'+item.targetId;
            area.alt=item.label;area.title=item.label;map.appendChild(area)});scale()};
            img.addEventListener('load',scale);new ResizeObserver(scale).observe(img);scale();})();
            </script></body></html>
        """.trimIndent()
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
                    put("route", if (hotspot.type == DawnHotspotType.TEXT) "text" else "image")
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
        val parts = uri.pathSegments
        if (parts.size != 2) return
        val id = parts[1]
        when (parts[0]) {
            "page" -> {
                val page = edition.pages.firstOrNull { it.id == id } ?: return
                openOfflinePage(edition, page, directory)
            }
            "text" -> {
                val article = edition.articles.firstOrNull { it.id == id } ?: run {
                    viewingStoryContent = true
                    requestStoryText(edition, directory, id)
                    return
                }
                viewingStoryContent = true
                webView.loadDataWithBaseURL(
                    "https://offline.dawn/",
                    renderArticle(article),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
            "image" -> {
                viewingStoryContent = true
                if (!File(File(directory, "images"), "$id.jpg").isFile) {
                    requestStoryImage(edition, directory, id)
                    return
                }
                showOfflineImage(edition, directory, id)
            }
        }
    }

    private fun showOfflineImage(edition: DawnEdition, directory: File, id: String) {
        val sourcePage = edition.hotspots.firstOrNull { it.targetId == id }?.pageId
            ?: edition.pages.firstOrNull()?.id
            ?: return
        val baseUrl = Uri.fromFile(directory).toString().trimEnd('/') + "/"
        viewingStoryContent = true
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;background:#111;color:#fff;font:16px sans-serif}
            header{height:48px;display:flex;align-items:center;padding:0 12px}
            a{color:#fff}img{display:block;width:100%;height:calc(100% - 48px);margin:0 auto;object-fit:contain}
            #error{padding:24px;text-align:center}</style></head>
            <body><header><a href="https://offline.dawn/page/$sourcePage">Back to paper</a></header>
            <img src="images/$id.jpg" alt="Full-size newspaper image"
                onerror="this.hidden=true;document.getElementById('error').hidden=false">
            <div id="error" hidden>Image could not be displayed.</div></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    private fun returnToCurrentPaperPage() {
        storyLoadingDialog?.dismiss()
        storyLoadingDialog = null
        onDemandStoryId = null
        onDemandImageId = null
        onDemandEdition = null
        onDemandDirectory = null
        val edition = availableEdition ?: offlineEdition ?: return
        val page = edition.readingOrder.firstOrNull {
            it.id.takeLast(3).toIntOrNull() == pageNumber
        } ?: edition.readingOrder.firstOrNull() ?: return
        openOfflinePage(
            edition,
            page,
            File(File(filesDir, "editions"), edition.date)
        )
    }

    private fun requestStoryText(edition: DawnEdition, directory: File, id: String) {
        if (edition.hotspots.none {
                it.targetId == id && it.type == DawnHotspotType.TEXT
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

    private fun requestStoryImage(edition: DawnEdition, directory: File, id: String) {
        if (edition.hotspots.none {
                it.targetId == id && it.type == DawnHotspotType.IMAGE
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
                        val latest = if (metadata.isFile) DawnEdition.readMetadata(metadata) else edition
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

    private fun persistEdition(edition: DawnEdition, directory: File?) {
        val targetDirectory = directory
            ?: throw IllegalStateException("Edition storage is unavailable.")
        val marker = File(targetDirectory, "complete")
        val complete = marker.isFile
        edition.saveMetadata(targetDirectory)
        if (!complete && marker.exists() && !marker.delete()) {
            throw IllegalStateException("Could not update the edition details.")
        }
    }

    private fun renderArticle(article: DawnArticle): String {
        val paragraphs = article.paragraphs.joinToString("") {
            "<p>${htmlEscape(it)}</p>"
        }
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{max-width:760px;margin:24px auto;padding:0 20px;font:18px/1.65 Georgia,serif;color:#222}
            h1{font-size:30px;line-height:1.2}.byline{color:#666;font:14px Arial,sans-serif;margin:12px 0 24px}
            p{margin:0 0 1em}</style></head><body><h1>${htmlEscape(article.title)}</h1>
            <div class="byline">${htmlEscape(article.byline)}</div>$paragraphs</body></html>
        """.trimIndent()
    }

    private fun htmlEscape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private fun updatePageLabel() {
        val date = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(selectedDate.time)
        val page = "$date  ·  Page ${pageNumber.toString().padStart(3, '0')}"
        val title = availableEdition?.readingOrder?.firstOrNull {
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
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setDownloadingIndicator(downloading: Boolean) {
        dawnDownloadIndicator.visibility = if (downloading) View.VISIBLE else View.GONE
    }

    private fun refreshDownloadIndicator() {
        val date = selectedDateKey()
        val pageDownloadActive = synchronized(pageDownloadLock) {
            pageDownloadQueue.any { it.edition.date == date } ||
                pageDownloadInProgressId?.startsWith("${date}_") == true
        }
        val storyDownloadActive = activeTextStory?.edition?.date == date ||
            pendingTextStories.any { it.edition.date == date } ||
            prefetchingImageIds.any { it.startsWith("${date}_") }
        setDownloadingIndicator(
            editionInProgress?.date == date || pageDownloadActive || storyDownloadActive
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        downloadExecutor.shutdownNow()
        webView.destroy()
        inspectionWebView.destroy()
        detailWebView.destroy()
        super.onDestroy()
    }

    @Deprecated("Use the system back dispatcher in newer Android versions.")
    override fun onBackPressed() {
        if (readerMode) {
            if (viewingStoryContent) {
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
    val page: DawnPage,
    val directory: File,
    val edition: DawnEdition,
    val priority: Int,
    val sequence: Long
)

private data class TextStoryRequest(
    val edition: DawnEdition,
    val directory: File,
    val id: String
)
