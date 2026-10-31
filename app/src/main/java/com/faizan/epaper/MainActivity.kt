package com.faizan.epaper

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var pageLabel: TextView
    private lateinit var saveButton: Button
    private lateinit var downloadButton: Button
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val readerButtons = mutableListOf<Button>()
    private var selectedDate = Calendar.getInstance()
    private var pageNumber = 1
    private var isOfflineFile = false
    private var editionInProgress: DawnEdition? = null
    private var editionArchiveDirectory: File? = null
    private var editionArchiveIndex = 0
    private var editionStoryIds: List<String> = emptyList()
    private var storyArchiveIndex = 0
    private var archivingStories = false
    private var archivedPageId: String? = null
    private var latestEditionStatus: String? = null
    private var checkedEditionDate: String? = null
    private val dateFormat = SimpleDateFormat("dd_MM_yyyy", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createReaderView()
        loadPage()
    }

    private fun createReaderView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(245, 245, 245))
        }
        val title = TextView(this).apply {
            text = "Dawn E-Paper"
            textSize = 20f
            setTextColor(Color.rgb(34, 50, 70))
        }
        pageLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(6))
        }
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(button("Date") { chooseDate() })
        downloadButton = button("Download edition") { discoverEdition(startDownload = true) }
        downloadButton.isEnabled = false
        controls.addView(downloadButton)
        controls.addView(button("Offline editions") { showOfflineEditions() })
        controls.addView(button("Prev") {
            if (pageNumber > 1) {
                pageNumber--
                loadPage()
            }
        })
        controls.addView(button("Next") {
            pageNumber++
            loadPage()
        })
        saveButton = button("Save page offline") { saveCurrentPage() }
        controls.addView(saveButton)
        controls.addView(button("Saved pages") { showSavedPages() })

        val controlsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(controls)
        }
        header.addView(title)
        header.addView(pageLabel)
        header.addView(controlsScroll)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message
                ): Boolean {
                    if (!isUserGesture) return false
                    val popup = WebView(this@MainActivity).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                    }
                    val dialog = AlertDialog.Builder(this@MainActivity)
                        .setTitle("Dawn story")
                        .setView(popup)
                        .setNegativeButton("Close", null)
                        .create()
                    popup.webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView) {
                            dialog.dismiss()
                        }
                    }
                    popup.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val uri = request.url
                            if (uri.host != "epaper.dawn.com" ||
                                uri.path !in setOf("/DetailNews.php", "/DetailImage.php")
                            ) return false
                            val storyId = uri.getQueryParameter("StoryText")
                                ?: uri.getQueryParameter("StoryImage")
                                ?: return false
                            val parts = storyId.split('_')
                            if (parts.size != 5) return false
                            val offlineArticle = File(
                                File(File(filesDir, "editions"), parts.take(3).joinToString("_")),
                                "story_$storyId.mht"
                            )
                            if (!offlineArticle.isFile) return false
                            view.loadUrl(Uri.fromFile(offlineArticle).toString())
                            return true
                        }
                    }
                    dialog.setOnDismissListener { popup.destroy() }
                    dialog.show()
                    dialog.window?.setLayout(
                        (resources.displayMetrics.widthPixels * 0.95f).toInt(),
                        (resources.displayMetrics.heightPixels * 0.9f).toInt()
                    )
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    syncPageFromUrl(request.url)
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    isOfflineFile = Uri.parse(url).scheme == "file"
                    val uri = Uri.parse(url)
                    val activeEdition = editionInProgress
                    saveButton.isEnabled = !isOfflineFile && activeEdition == null
                    if ((uri.host == "epaper.dawn.com" || uri.host == "e.dawn.com") &&
                        activeEdition != null
                    ) {
                        val expectedId = if (archivingStories) {
                            editionStoryIds.getOrNull(storyArchiveIndex)
                        } else {
                            activeEdition.pages.getOrNull(editionArchiveIndex)?.id
                        }
                        val currentId = if (archivingStories) {
                            uri.getQueryParameter("StoryImage")
                        } else if (uri.host == "e.dawn.com") {
                            uri.lastPathSegment?.removeSuffix(".html")
                        } else {
                            uri.getQueryParameter("page")
                        }
                        if (expectedId != null && currentId == expectedId &&
                            archivedPageId != expectedId
                        ) {
                            archivedPageId = expectedId
                            view.postDelayed({
                                if (editionInProgress === activeEdition) {
                                    archiveCurrentPage(activeEdition, expectedId, archivingStories)
                                }
                            }, 1_000)
                        }
                        return
                    }
                    if (uri.host == "epaper.dawn.com") {
                        downloadButton.isEnabled = true
                        val requestedDate = dateFormat.format(selectedDate.time)
                        if (checkedEditionDate != requestedDate) {
                            checkedEditionDate = requestedDate
                            discoverEdition(startDownload = false)
                        }
                    }
                    updatePageLabel()
                }
            }
        }

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)
        updatePageLabel()
    }

    private fun loadPage() {
        isOfflineFile = false
        saveButton.isEnabled = false
        downloadButton.isEnabled = false
        updatePageLabel()
        val date = dateFormat.format(selectedDate.time)
        val page = pageNumber.toString().padStart(3, '0')
        webView.loadUrl("https://epaper.dawn.com/?page=${date}_$page")
    }

    private fun chooseDate() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedDate.set(year, month, day)
                pageNumber = 1
                loadPage()
            },
            selectedDate.get(Calendar.YEAR),
            selectedDate.get(Calendar.MONTH),
            selectedDate.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun discoverEdition(startDownload: Boolean) {
        downloadButton.isEnabled = false
        val script = """
            (function() {
                const datePattern = /^\d{2}_\d{2}_\d{4}_001$/;
                const datePicker = Array.from(document.querySelectorAll('select'))
                    .find(select => Array.from(select.options).some(option => datePattern.test(option.value)));
                if (!datePicker || !datePicker.options.length) {
                    return JSON.stringify({error: 'Could not find Dawn edition dates on this page.'});
                }
                const date = (datePicker.value || datePicker.options[0].value).substring(0, 10);
                const latestDate = datePicker.options[0].value.substring(0, 10);
                const pages = [];
                const seen = new Set();
                for (const link of document.querySelectorAll('a[href*="page="]')) {
                    const url = new URL(link.href, location.href);
                    const id = url.searchParams.get('page');
                    if (id && id.startsWith(date + '_') &&
                        /^\d{2}_\d{2}_\d{4}_\d{3}$/.test(id) && !seen.has(id)) {
                        seen.add(id);
                        pages.push({id: id, title: link.innerText.trim()});
                    }
                }
                return JSON.stringify({
                    date: date,
                    latestDate: latestDate,
                    pages: pages
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            try {
                val json = JSONTokener(result).nextValue() as? String
                    ?: throw IllegalStateException("Could not read the Dawn edition page list.")
                val response = JSONObject(json)
                if (response.has("error")) {
                    latestEditionStatus = response.getString("error")
                    downloadButton.isEnabled = true
                    updatePageLabel()
                    showMessage(response.getString("error"))
                } else {
                    val edition = DawnEdition.fromJson(json)
                    val today = dateFormat.format(Calendar.getInstance().time)
                    latestEditionStatus = if (edition.latestDate == today) {
                        "Today's paper is live · ${edition.pages.size} pages"
                    } else {
                        "Today's paper is not live · latest ${edition.latestDate.replace('_', '-')}"
                    }
                    updatePageLabel()
                    if (startDownload) downloadEdition(edition) else downloadButton.isEnabled = true
                }
            } catch (error: Exception) {
                latestEditionStatus = error.message ?: "Could not check Dawn's latest edition."
                downloadButton.isEnabled = true
                updatePageLabel()
                showMessage(error.message ?: "Could not read Dawn's edition page list.")
            }
        }
    }

    private fun downloadEdition(edition: DawnEdition) {
        val selected = dateFormat.format(selectedDate.time)
        val today = dateFormat.format(Calendar.getInstance().time)
        if (edition.date != selected &&
            (selected != today || edition.date != edition.latestDate)
        ) {
            downloadButton.isEnabled = true
            showMessage("Dawn is showing ${edition.date.replace('_', '-')}, not the selected date. Reload the page and try again.")
            return
        }
        if (edition.date != selected) {
            dateFormat.parse(edition.date)?.let { selectedDate.time = it }
            pageNumber = 1
        }

        val liveStatus = if (edition.latestDate == today) {
            "Today's paper is live"
        } else {
            "Today's paper is not live · latest ${edition.latestDate.replace('_', '-')}"
        }
        latestEditionStatus = liveStatus
        pageLabel.text = "$liveStatus · ${edition.pages.size} pages found"
        downloadButton.isEnabled = false
        setReaderControlsEnabled(false)
        showMessage("Downloading ${edition.pages.size} Dawn pages…")

        val directory = File(File(filesDir, "editions"), edition.date)
        downloadExecutor.execute {
            try {
                edition.downloadPageImages(directory) { completed, total ->
                    runOnUiThread {
                        pageLabel.text =
                            "Downloading ${edition.date.replace('_', '-')} · $completed/$total pages"
                    }
                }
                runOnUiThread {
                    editionInProgress = edition
                    editionArchiveDirectory = directory
                    editionArchiveIndex = 0
                    editionStoryIds = emptyList()
                    storyArchiveIndex = 0
                    archivingStories = false
                    archivedPageId = null
                    pageLabel.text = "Saving Dawn page links · 0/${edition.pages.size}"
                    loadNextEditionPage()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    downloadButton.isEnabled = true
                    setReaderControlsEnabled(true)
                    pageLabel.text = "Download incomplete · ${edition.date.replace('_', '-')}"
                    showMessage(error.message ?: "Could not download the full edition.")
                }
            }
        }
    }

    private fun loadNextEditionPage() {
        val edition = editionInProgress ?: return
        if (archivingStories) {
            val storyId = editionStoryIds.getOrNull(storyArchiveIndex)
            if (storyId == null) {
                completeEditionDownload(edition)
                return
            }
            pageLabel.text = "Saving story text · ${storyArchiveIndex + 1}/${editionStoryIds.size}"
            webView.loadUrl("https://epaper.dawn.com/DetailImage.php?StoryImage=$storyId")
            return
        }

        val page = edition.pages.getOrNull(editionArchiveIndex)
        if (page == null) {
            downloadLinkedStories(edition)
            return
        }
        pageLabel.text =
            "Saving Dawn page links · ${editionArchiveIndex + 1}/${edition.pages.size}"
        val parts = edition.date.split('_')
        webView.loadUrl(
            "https://e.dawn.com/${parts[2]}/${parts[1]}/${parts[0]}/pages/${page.id}.html",
            mapOf("Referer" to "https://epaper.dawn.com/")
        )
    }

    private fun archiveCurrentPage(edition: DawnEdition, id: String, isStory: Boolean) {
        val directory = editionArchiveDirectory ?: run {
            failEditionDownload("Edition storage is unavailable.")
            return
        }
        val archiveName = if (isStory) "story_$id.mht" else "$id.mht"
        val archive = File(directory, archiveName)
        webView.evaluateJavascript("document.title") { titleResult ->
            val title = try {
                JSONTokener(titleResult).nextValue() as? String
            } catch (_: Exception) {
                null
            }
            if (title?.contains("Dawn-ePaper", ignoreCase = true) != true &&
                title?.contains("DAWN ePaper", ignoreCase = true) != true
            ) {
                failEditionDownload("Dawn did not load content $id; check the connection and try again.")
                return@evaluateJavascript
            }
            val saveArchive = {
                webView.saveWebArchive(archive.absolutePath, false) { savedPath ->
                    runOnUiThread {
                        if (editionInProgress !== edition) return@runOnUiThread
                        if (savedPath == null || !archive.isFile || archive.length() == 0L) {
                            failEditionDownload("Could not save Dawn content $id for offline reading.")
                            return@runOnUiThread
                        }
                        if (isStory) storyArchiveIndex++ else editionArchiveIndex++
                        archivedPageId = null
                        loadNextEditionPage()
                    }
                }
            }
            if (isStory) {
                saveArchive()
            } else {
                val script = """
                    JSON.stringify(Array.from(document.querySelectorAll('area[href]'))
                        .map(area => (area.getAttribute('href') || '').match(/StoryText=([0-9_]+)/i))
                        .filter(match => match && /^\d{2}_\d{2}_\d{4}_\d{3}_\d{3}$/.test(match[1]))
                        .map(match => match[1]))
                """.trimIndent()
                webView.evaluateJavascript(script) { result ->
                    try {
                        val json = JSONTokener(result).nextValue() as? String
                            ?: throw IllegalStateException("Could not read Dawn's story links for page $id.")
                        val stories = org.json.JSONArray(json)
                        for (index in 0 until stories.length()) {
                            val storyId = stories.getString(index)
                            if (storyId.startsWith("${edition.date}_") && storyId !in editionStoryIds) {
                                editionStoryIds = editionStoryIds + storyId
                            }
                        }
                        saveArchive()
                    } catch (error: Exception) {
                        failEditionDownload(error.message ?: "Could not read story links for page $id.")
                    }
                }
            }
        }
    }

    private fun downloadLinkedStories(edition: DawnEdition) {
        if (editionArchiveDirectory == null) {
            failEditionDownload("Edition storage is unavailable.")
            return
        }
        archivingStories = editionStoryIds.isNotEmpty()
        storyArchiveIndex = 0
        archivedPageId = null
        if (archivingStories) loadNextEditionPage() else completeEditionDownload(edition)
    }

    private fun completeEditionDownload(edition: DawnEdition) {
        try {
            val directory = editionArchiveDirectory
                ?: throw IllegalStateException("Edition storage is unavailable.")
            edition.removeLegacyStoryImages(directory, editionStoryIds)
            edition.saveMetadata(
                directory,
                editionStoryIds
            )
            editionInProgress = null
            editionArchiveDirectory = null
            archivingStories = false
            setReaderControlsEnabled(true)
            pageLabel.text =
                "Offline edition ready · ${edition.pages.size} pages · ${editionStoryIds.size} stories"
            showMessage("Saved pages, clickable links, and ${editionStoryIds.size} text stories for offline reading.")
            pageNumber = 1
            loadPage()
        } catch (error: Exception) {
            failEditionDownload(error.message ?: "Could not save edition details.")
        }
    }

    private fun failEditionDownload(message: String) {
        editionInProgress = null
        editionArchiveDirectory = null
        archivingStories = false
        setReaderControlsEnabled(true)
        showMessage(message)
        pageLabel.text = "Edition download incomplete"
        pageNumber = 1
        loadPage()
    }

    private fun setReaderControlsEnabled(enabled: Boolean) {
        readerButtons.forEach { it.isEnabled = enabled }
        if (enabled) downloadButton.isEnabled = true
    }

    private fun showOfflineEditions() {
        val editionDirectory = File(filesDir, "editions")
        val editions = editionDirectory.listFiles()
            ?.mapNotNull { directory ->
                val metadata = File(directory, "edition.json")
                if (!metadata.isFile) return@mapNotNull null
                try {
                    DawnEdition.readMetadata(metadata) to directory
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { dateFormat.parse(it.first.date)?.time ?: Long.MIN_VALUE }
            .orEmpty()
        if (editions.isEmpty()) {
            showMessage("No complete editions have been downloaded yet.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Offline editions")
            .setItems(editions.map { it.first.date.replace('_', '-') }.toTypedArray()) { _, index ->
                showOfflinePages(editions[index].first, editions[index].second)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showOfflinePages(edition: DawnEdition, directory: File) {
        AlertDialog.Builder(this)
            .setTitle("Pages · ${edition.date.replace('_', '-')}")
            .setItems(edition.pages.map { "${it.id.takeLast(3)} · ${it.title}" }.toTypedArray()) { _, index ->
                val page = edition.pages[index]
                val image = File(directory, page.imageFileName)
                val webArchive = File(directory, "${page.id}.mht")
                val offlineFile = if (webArchive.isFile) webArchive else image
                if (!offlineFile.isFile) {
                    showMessage("This page is missing from the offline edition.")
                    return@setItems
                }
                val parsedDate = dateFormat.parse(edition.date)
                if (parsedDate != null) selectedDate.time = parsedDate
                pageNumber = page.id.takeLast(3).toInt()
                isOfflineFile = true
                saveButton.isEnabled = false
                updatePageLabel("Offline · ${page.title}")
                webView.loadUrl(Uri.fromFile(offlineFile).toString())
            }
            .setNeutralButton("Text stories") { _, _ -> showOfflineStories(edition, directory) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showOfflineStories(edition: DawnEdition, directory: File) {
        if (edition.storyIds.isEmpty()) {
            showMessage("No story text was archived for this edition.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Text stories · ${edition.date.replace('_', '-')}")
            .setItems(edition.storyIds.map { id ->
                val parts = id.split('_')
                "Page ${parts[3]} · Story ${parts[4]}"
            }.toTypedArray()) { _, index ->
                val id = edition.storyIds[index]
                val storyArchive = File(directory, "story_$id.mht")
                if (!storyArchive.isFile) {
                    showMessage("This story's text is missing from the offline edition.")
                    return@setItems
                }
                isOfflineFile = true
                saveButton.isEnabled = false
                webView.loadUrl(Uri.fromFile(storyArchive).toString())
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun saveCurrentPage() {
        val archiveDirectory = File(filesDir, "saved-pages")
        if (!archiveDirectory.exists() && !archiveDirectory.mkdirs()) {
            showMessage("Could not create the offline storage folder.")
            return
        }
        val filename =
            "${dateFormat.format(selectedDate.time)}_${pageNumber.toString().padStart(3, '0')}.mht"
        val archive = File(archiveDirectory, filename)
        saveButton.isEnabled = false
        webView.saveWebArchive(archive.absolutePath, false) { savedPath ->
            runOnUiThread {
                saveButton.isEnabled = !isOfflineFile
                if (savedPath == null || !archive.isFile || archive.length() == 0L) {
                    showMessage("Could not save this page. Wait for it to finish loading and try again.")
                } else {
                    showMessage("Page saved for offline reading.")
                }
            }
        }
    }

    private fun showSavedPages() {
        val archiveDirectory = File(filesDir, "saved-pages")
        val archives = archiveDirectory.listFiles()
            ?.filter { it.isFile && it.extension == "mht" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (archives.isEmpty()) {
            showMessage("No pages have been saved yet.")
            return
        }

        val labels = archives.map { it.nameWithoutExtension.replace('_', ' ') }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Saved pages")
            .setItems(labels) { _, index ->
                isOfflineFile = true
                saveButton.isEnabled = false
                webView.loadUrl(Uri.fromFile(archives[index]).toString())
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun syncPageFromUrl(uri: Uri) {
        if (uri.host != "epaper.dawn.com") return
        val pageId = uri.getQueryParameter("page") ?: return
        val parts = pageId.split('_')
        if (parts.size != 4) return
        val parsedDate = Calendar.getInstance()
        val date = dateFormat.parse(parts.take(3).joinToString("_")) ?: return
        parsedDate.time = date
        val parsedPage = parts[3].toIntOrNull() ?: return
        selectedDate = parsedDate
        pageNumber = parsedPage
        updatePageLabel()
    }

    private fun updatePageLabel(status: String? = null) {
        val date = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(selectedDate.time)
        val page = "$date  ·  Page ${pageNumber.toString().padStart(3, '0')}"
        pageLabel.text = listOfNotNull(page, status ?: latestEditionStatus).joinToString("  ·  ")
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            minHeight = dp(40)
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { onClick() }
        }.also(readerButtons::add)

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        downloadExecutor.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }
}
