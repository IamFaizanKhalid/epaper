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
    private lateinit var downloadButton: Button
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val readerButtons = mutableListOf<Button>()
    private var selectedDate = Calendar.getInstance()
    private var pageNumber = 1
    private var editionInProgress: DawnEdition? = null
    private var activeEditionDirectory: File? = null
    private var editionPageIndex = 0
    private var textStoryIds: List<String> = emptyList()
    private var fullImageIds: List<String> = emptyList()
    private var collectedHotspots: List<DawnHotspot> = emptyList()
    private var collectedArticles: List<DawnArticle> = emptyList()
    private var storyIndex = 0
    private var archivingStories = false
    private var processingContentId: String? = null
    private var offlineEdition: DawnEdition? = null
    private var offlineDirectory: File? = null
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
                                uri.path !in setOf(
                                    "/DetailNews.php",
                                    "/DetailImage.php",
                                    "/Advt.php"
                                )
                            ) return false
                            val storyId = uri.getQueryParameter("StoryText")
                                ?: uri.getQueryParameter("StoryImage")
                                ?: return false
                            val parts = storyId.split('_')
                            if (parts.size != 5) return false
                            val editionDirectory = File(
                                File(filesDir, "editions"),
                                parts.take(3).joinToString("_")
                            )
                            val metadata = File(editionDirectory, "edition.json")
                            if (!metadata.isFile) return false
                            val edition = DawnEdition.readMetadata(metadata)
                            val article = edition.articles.firstOrNull { it.id == storyId }
                            if (uri.path == "/DetailImage.php" && article != null) {
                                view.loadDataWithBaseURL(
                                    "https://offline.dawn/",
                                    renderArticle(article),
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                                return true
                            }
                            val image = File(File(editionDirectory, "images"), "$storyId.jpg")
                            if (uri.path in setOf("/DetailNews.php", "/Advt.php") &&
                                storyId in edition.fullImageIds && image.isFile
                            ) {
                                view.loadUrl(Uri.fromFile(image).toString())
                                return true
                            }
                            return false
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
                    if (request.url.host == "offline.dawn") {
                        openOfflineTarget(request.url)
                        return true
                    }
                    syncPageFromUrl(request.url)
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    val uri = Uri.parse(url)
                    val activeEdition = editionInProgress
                    if (activeEdition != null && uri.host == "e.dawn.com" &&
                        !archivingStories
                    ) {
                        val page = activeEdition.pages.getOrNull(editionPageIndex)
                        val currentId = uri.lastPathSegment?.removeSuffix(".html")
                        if (page != null && currentId == page.id &&
                            processingContentId != page.id
                        ) {
                            processingContentId = page.id
                            view.postDelayed({
                                if (editionInProgress === activeEdition) {
                                    collectPageHotspots(activeEdition, page)
                                }
                            }, 250)
                        }
                        return
                    }
                    if (activeEdition != null && archivingStories &&
                        uri.host == "epaper.dawn.com"
                    ) {
                        val storyId = textStoryIds.getOrNull(storyIndex)
                        if (storyId != null && uri.path == "/DetailImage.php" &&
                            uri.getQueryParameter("StoryImage") == storyId &&
                            processingContentId != storyId
                        ) {
                            processingContentId = storyId
                            view.postDelayed({
                                if (editionInProgress === activeEdition) {
                                    collectStoryText(storyId)
                                }
                            }, 250)
                        }
                        return
                    }
                    if (activeEdition != null && archivingStories) return
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

        latestEditionStatus = if (edition.latestDate == today) {
            "Today's paper is live"
        } else {
            "Today's paper is not live · latest ${edition.latestDate.replace('_', '-')}"
        }
        pageLabel.text = "${latestEditionStatus} · ${edition.pages.size} pages"
        downloadButton.isEnabled = false
        setReaderControlsEnabled(false)
        showMessage("Downloading ${edition.pages.size} Dawn page images…")

        val directory = File(File(filesDir, "editions"), edition.date)
        downloadExecutor.execute {
            try {
                edition.downloadPageImages(directory) { completed, total ->
                    runOnUiThread {
                        pageLabel.text = "Downloading page images · $completed/$total"
                    }
                }
                runOnUiThread {
                    editionInProgress = edition
                    activeEditionDirectory = directory
                    editionPageIndex = 0
                    textStoryIds = emptyList()
                    fullImageIds = emptyList()
                    collectedHotspots = emptyList()
                    collectedArticles = emptyList()
                    storyIndex = 0
                    archivingStories = false
                    processingContentId = null
                    loadNextEditionPage()
                }
            } catch (error: Exception) {
                runOnUiThread { failEditionDownload(error.message ?: "Could not download Dawn's page images.") }
            }
        }
    }

    private fun loadNextEditionPage() {
        val edition = editionInProgress ?: return
        if (archivingStories) {
            val storyId = textStoryIds.getOrNull(storyIndex)
            if (storyId == null) {
                completeEditionDownload(edition)
                return
            }
            pageLabel.text = "Saving story text · ${storyIndex + 1}/${textStoryIds.size}"
            processingContentId = null
            webView.loadUrl("https://epaper.dawn.com/DetailImage.php?StoryImage=$storyId")
            return
        }

        val page = edition.pages.getOrNull(editionPageIndex)
        if (page == null) {
            downloadLinkedContent(edition)
            return
        }
        pageLabel.text = "Reading page links · ${editionPageIndex + 1}/${edition.pages.size}"
        processingContentId = null
        val parts = edition.date.split('_')
        webView.loadUrl(
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
        webView.evaluateJavascript(script) { result ->
            try {
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
                loadNextEditionPage()
            } catch (error: Exception) {
                failEditionDownload(error.message ?: "Could not read clickable areas on ${page.id}.")
            }
        }
    }

    private fun downloadLinkedContent(edition: DawnEdition) {
        val directory = activeEditionDirectory ?: run {
            failEditionDownload("Edition storage is unavailable.")
            return
        }
        downloadExecutor.execute {
            try {
                edition.downloadFullImages(directory, fullImageIds) { completed, total ->
                    runOnUiThread { pageLabel.text = "Downloading full-size images · $completed/$total" }
                }
                runOnUiThread {
                    archivingStories = textStoryIds.isNotEmpty()
                    storyIndex = 0
                    if (archivingStories) loadNextEditionPage() else completeEditionDownload(edition)
                }
            } catch (error: Exception) {
                runOnUiThread { failEditionDownload(error.message ?: "Could not download full-size images.") }
            }
        }
    }

    private fun collectStoryText(id: String) {
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
        webView.evaluateJavascript(script) { result ->
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
                collectedArticles = collectedArticles.filterNot { it.id == id } + article
                storyIndex++
                loadNextEditionPage()
            } catch (error: Exception) {
                failEditionDownload(error.message ?: "Could not save Dawn story text.")
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
                articles = collectedArticles
            )
            savedEdition.removeLegacyArchives(directory)
            savedEdition.saveMetadata(directory)
            editionInProgress = null
            activeEditionDirectory = null
            archivingStories = false
            setReaderControlsEnabled(true)
            pageLabel.text =
                "Offline edition ready · ${edition.pages.size} pages · ${collectedArticles.size} texts · ${fullImageIds.size} images"
            showMessage("Saved page images, clickable areas, full-size images, and text stories.")
            pageNumber = 1
            loadPage()
        } catch (error: Exception) {
            failEditionDownload(error.message ?: "Could not save edition details.")
        }
    }

    private fun failEditionDownload(message: String) {
        editionInProgress = null
        activeEditionDirectory = null
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
                openOfflinePage(edition, edition.pages[index], directory)
            }
            .setNeutralButton("Images") { _, _ -> showOfflineImages(edition, directory) }
            .setPositiveButton("Text stories") { _, _ -> showOfflineStories(edition, directory) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openOfflinePage(edition: DawnEdition, page: DawnPage, directory: File) {
        if (!File(directory, page.imageFileName).isFile) {
            showMessage("This page image is missing from the offline edition.")
            return
        }
        offlineEdition = edition
        offlineDirectory = directory
        selectedDate.time = dateFormat.parse(edition.date) ?: selectedDate.time
        pageNumber = page.id.takeLast(3).toInt()
        updatePageLabel("Offline · ${page.title}")
        val pageHotspots = edition.hotspots.filter { it.pageId == page.id }
        val html = renderOfflinePage(page, pageHotspots)
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
            <style>body{margin:0;background:#222;text-align:center}img{display:block;width:100%;height:auto;margin:auto}</style>
            </head><body><img id="page" src="${page.imageFileName}" usemap="#page-map"><map name="page-map">$areas</map>
            <script>
            (function(){const img=document.getElementById('page');const areas=document.querySelectorAll('area');
            function scale(){if(!img.naturalWidth)return;const sx=img.clientWidth/img.naturalWidth,sy=img.clientHeight/img.naturalHeight;
            areas.forEach(a=>{if(!a.dataset.original)a.dataset.original=a.coords;const c=a.dataset.original.split(',').map(Number);
            a.coords=c.map((n,i)=>Math.round(n*(i%2?sy:sx))).join(',')})}
            img.addEventListener('load',scale);new ResizeObserver(scale).observe(img);scale();})();
            </script></body></html>
        """.trimIndent()
    }

    private fun openOfflineTarget(uri: Uri) {
        val edition = offlineEdition ?: return
        val directory = offlineDirectory ?: return
        val parts = uri.pathSegments
        if (parts.size != 2) return
        val id = parts[1]
        when (parts[0]) {
            "text" -> {
                val article = edition.articles.firstOrNull { it.id == id } ?: run {
                    showMessage("This story's text is missing from the offline edition.")
                    return
                }
                webView.loadDataWithBaseURL(
                    "https://offline.dawn/",
                    renderArticle(article),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
            "image" -> {
                if (id !in edition.fullImageIds) return
                val image = File(File(directory, "images"), "$id.jpg")
                if (!image.isFile) {
                    showMessage("This full-size image is missing from the offline edition.")
                    return
                }
                webView.loadUrl(Uri.fromFile(image).toString())
            }
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

    private fun showOfflineImages(edition: DawnEdition, directory: File) {
        if (edition.fullImageIds.isEmpty()) {
            showMessage("No full-size images were saved for this edition.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Full-size images")
            .setItems(edition.fullImageIds.map { "Page ${it.split('_')[3]} · Image ${it.split('_')[4]}" }
                .toTypedArray()) { _, index ->
                offlineEdition = edition
                offlineDirectory = directory
                openOfflineTarget(Uri.parse("https://offline.dawn/image/${edition.fullImageIds[index]}"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showOfflineStories(edition: DawnEdition, directory: File) {
        if (edition.articles.isEmpty()) {
            showMessage("No text stories were saved for this edition.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Text stories · ${edition.date.replace('_', '-')}")
            .setItems(edition.articles.map { it.title }.toTypedArray()) { _, index ->
                offlineEdition = edition
                offlineDirectory = directory
                webView.loadDataWithBaseURL(
                    "https://offline.dawn/",
                    renderArticle(edition.articles[index]),
                    "text/html",
                    "UTF-8",
                    null
                )
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
