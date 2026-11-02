package com.faizan.epaper

import org.json.JSONArray
import org.json.JSONObject
import android.app.job.JobService
import java.io.File
import java.net.URL

typealias JangImageDownloader = (URL, String, File) -> Unit

enum class NewspaperId(val storageName: String, val displayName: String) {
    DAWN("dawn", "Dawn"),
    JANG_LAHORE("jang", "Jang Lahore")
}

data class PaperBackgroundJob(
    val periodicJobId: Int,
    val nineAmJobId: Int,
    val serviceClass: Class<out JobService>
)

interface Paper {
    val id: NewspaperId
    val supportsImageDetail: Boolean
    val offlineOrigin: String
    val backgroundJob: PaperBackgroundJob
    val supportsInlineArticleImages: Boolean
        get() = false
    val supportsForegroundEditionPrefetch: Boolean
        get() = false
    val requiresPageVerification: Boolean
        get() = false
    val refreshArticleWhenBylineMissing: Boolean
        get() = false
    val articleFontFamily: String
        get() = "Georgia, serif"
    val articleTextDirection: String
        get() = "ltr"

    fun articleImageDetailTarget(storyId: String): String? = null
    fun inlineArticleImageTarget(articleId: String, imageIndex: Int): String? = null
    fun localArticleImageUrl(articleId: String, imageIndex: Int): String? = null

    fun editionDirectory(root: File, date: String): File
    fun availabilityUrl(date: String): String
    fun pageUrl(date: String, pageId: String): String
    fun articleUrl(storyId: String): String
    fun isArticleUrl(url: String, storyId: String): Boolean
    fun isPageUrl(url: String, date: String, pageId: String): Boolean
    fun hotspotExtractionScript(date: String, pageId: String): String
    fun availabilityDiscoveryScript(date: String): String
    fun isAvailabilityPageUrl(url: String, date: String): Boolean
    fun parseAvailability(json: String): NewspaperEdition
    fun articleExtractionScript(storyId: String): String
    fun parseArticle(json: String, storyId: String): NewspaperArticle
    fun downloadEdition(
        edition: NewspaperEdition,
        directory: File,
        jangImageDownloader: JangImageDownloader? = null,
        onProgress: (Int, Int) -> Unit
    ) {
        val pages = edition.readingOrder
        pages.forEachIndexed { index, page ->
            if (!edition.hasPageImage(directory, page.id)) {
                edition.downloadPageImage(directory, page.id, jangImageDownloader)
            }
            onProgress(index + 1, pages.size)
        }
    }

    fun parseHotspots(json: String, edition: NewspaperEdition, page: NewspaperPage): List<NewspaperHotspot>
}

object Papers {
    val all: List<Paper> = listOf(
        DawnPaper(),
        JangLahorePaper()
    )

    private val byId: Map<NewspaperId, Paper> = all.associateBy { it.id }

    operator fun get(id: NewspaperId): Paper =
        byId[id] ?: error("No paper is registered for $id.")

    fun forEdition(edition: NewspaperEdition): Paper =
        get(edition.paperId)
}

private class DawnPaper : Paper {
    override val id = NewspaperId.DAWN
    override val supportsImageDetail = true
    override val offlineOrigin = "https://offline.dawn/"
    override val supportsForegroundEditionPrefetch = true
    override val backgroundJob = PaperBackgroundJob(
        periodicJobId = 2841,
        nineAmJobId = 2842,
        serviceClass = DailyEditionCheckJobService::class.java
    )
    override fun articleImageDetailTarget(storyId: String): String? =
        if (DAWN_STORY_ID_PATTERN.matches(storyId)) {
            "${offlineOrigin}article-image/$storyId"
        } else {
            null
        }

    override fun editionDirectory(root: File, date: String): File =
        File(root, date)

    override fun availabilityUrl(date: String): String =
        "https://epaper.dawn.com/?page=${date}_001"

    override fun pageUrl(date: String, pageId: String): String {
        val (day, month, year) = date.split('_')
        return "https://e.dawn.com/$year/$month/$day/pages/$pageId.html"
    }

    override fun articleUrl(storyId: String): String =
        "https://epaper.dawn.com/DetailImage.php?StoryImage=$storyId"

    override fun isArticleUrl(url: String, storyId: String): Boolean =
        url.substringBefore('#').startsWith(articleUrl(storyId))

    override fun isPageUrl(url: String, date: String, pageId: String): Boolean =
        url.startsWith("https://e.dawn.com/") &&
            url.substringBefore('?').endsWith("/pages/$pageId.html")

    override fun isAvailabilityPageUrl(url: String, date: String): Boolean =
        url.substringBefore('?') == "https://epaper.dawn.com/"

    override fun parseAvailability(json: String): NewspaperEdition =
        NewspaperEdition.fromJson(json)

    override fun availabilityDiscoveryScript(date: String): String = """
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

    override fun articleExtractionScript(storyId: String): String = """
        (() => {
            const article = Array.from(document.querySelectorAll('.px-4.font-merriweather > div'))
                .find(element => element.querySelector('h2') &&
                    element.querySelector('.overflow-hidden'));
            if (!article) return JSON.stringify({error: 'Could not find the text for story $storyId.'});
            const title = article.querySelector('h2')?.innerText.trim() || '';
            const byline = article.querySelector(':scope > .my-3')?.innerText.trim() || '';
            const body = article.querySelector(':scope > .overflow-hidden');
            const paragraphs = Array.from(body.querySelectorAll('p'))
                .map(paragraph => paragraph.innerText.trim()).filter(Boolean);
            if (!paragraphs.length && body.innerText.trim()) paragraphs.push(body.innerText.trim());
            return JSON.stringify({id: '$storyId', title: title, byline: byline, paragraphs: paragraphs});
        })()
    """.trimIndent()

    override fun parseArticle(json: String, storyId: String): NewspaperArticle {
        val data = JSONObject(json)
        if (data.has("error")) throw IllegalStateException(data.getString("error"))
        val paragraphs = data.getJSONArray("paragraphs").toStringList()
        val article = NewspaperArticle(
            storyId,
            data.getString("title"),
            data.optString("byline"),
            paragraphs
        )
        require(article.title.isNotBlank() && article.paragraphs.isNotEmpty()) {
            "Dawn returned empty text for story $storyId."
        }
        return article
    }

    override fun hotspotExtractionScript(date: String, pageId: String): String = """
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

    override fun parseHotspots(
        json: String,
        edition: NewspaperEdition,
        page: NewspaperPage
    ): List<NewspaperHotspot> {
        val entries = JSONArray(json)
        return (0 until entries.length()).mapNotNull { index ->
            val entry = entries.getJSONObject(index)
            val targetId = entry.getString("targetId")
            if (!targetId.startsWith("${edition.date}_")) return@mapNotNull null
            val coordinates = entry.getJSONArray("coordinates")
            if (coordinates.length() < 4) return@mapNotNull null
            NewspaperHotspot(
                page.id,
                targetId,
                NewspaperHotspotType.valueOf(entry.getString("type")),
                entry.optString("label"),
                entry.optString("shape", "rect"),
                coordinates.toIntList()
            )
        }
    }

    private companion object {
        val DAWN_STORY_ID_PATTERN = Regex("\\d{2}_\\d{2}_\\d{4}_\\d{3}_\\d{3}")
    }
}

private class JangLahorePaper : Paper {
    override val id = NewspaperId.JANG_LAHORE
    override val supportsImageDetail = false
    override val offlineOrigin = "https://offline.jang/"
    override val supportsInlineArticleImages = true
    override val requiresPageVerification = true
    override val refreshArticleWhenBylineMissing = true
    override val articleFontFamily = "'AliNastaliq', serif"
    override val articleTextDirection = "rtl"
    override val backgroundJob = PaperBackgroundJob(
        periodicJobId = 2843,
        nineAmJobId = 2844,
        serviceClass = JangEditionCheckJobService::class.java
    )

    override fun inlineArticleImageTarget(articleId: String, imageIndex: Int): String =
        "${offlineOrigin}article-image/$articleId/$imageIndex"

    override fun localArticleImageUrl(articleId: String, imageIndex: Int): String =
        "${offlineOrigin}article-asset/${android.net.Uri.encode(articleId)}/$imageIndex"

    override fun editionDirectory(root: File, date: String): File =
        File(File(root, id.storageName), date)

    override fun availabilityUrl(date: String): String =
        "https://e.jang.com.pk/lahore/${date.replace('_', '-')}/page1"

    override fun pageUrl(date: String, pageId: String): String {
        val pageNumber = pageId.takeLast(3).toInt()
        return "https://e.jang.com.pk/lahore/${date.replace('_', '-')}/page$pageNumber"
    }

    override fun articleUrl(storyId: String): String =
        "https://e.jang.com.pk/detail/$storyId"

    override fun isArticleUrl(url: String, storyId: String): Boolean =
        url.substringBefore('#').startsWith(articleUrl(storyId))

    override fun isPageUrl(url: String, date: String, pageId: String): Boolean =
        url.startsWith(pageUrl(date, pageId))

    override fun isAvailabilityPageUrl(url: String, date: String): Boolean =
        url.substringBefore('?') == availabilityUrl(date)

    override fun parseAvailability(json: String): NewspaperEdition =
        NewspaperEdition.fromJson(json)

    override fun availabilityDiscoveryScript(date: String): String = """
        (() => {
            const title = (document.title || '').toLowerCase();
            const pageText = (document.body?.innerText || '').slice(0, 4000).toLowerCase();
            if (title.includes('moment, please') ||
                title.includes('just a moment') ||
                pageText.includes('verify you are human') ||
                pageText.includes('checking your browser') ||
                pageText.includes('security verification') ||
                pageText.includes('performing security verification') ||
                pageText.includes('request is being verified') ||
                document.querySelector('#challenge-running, #challenge-stage')) {
                return JSON.stringify({error: 'VERIFYING'});
            }
            const requested = '${date.replace('_', '-')}';
            const pages = [];
            const seen = new Set();
            const pageMenus = Array.from(document.querySelectorAll('a.top_parent'))
                .map(menu => Array.from(
                    menu.parentElement?.querySelectorAll('ul a[href]') || []
                ).flatMap(link => {
                    const url = new URL(link.href, location.href);
                    const match = url.pathname.match(
                        /^\/lahore\/(\d{2}-\d{2}-\d{4})\/page(\d+)\/?$/i
                    );
                    if (url.host !== 'e.jang.com.pk' || url.hash || !match ||
                        match[1] !== requested) return [];
                    const number = Number(match[2]);
                    if (number < 1 || number > 99) return [];
                    return [{
                        id: 'J${date}_' + String(number).padStart(3, '0'),
                        title: (link.innerText || link.textContent || '').trim()
                    }];
                }));
            const pageMenu = pageMenus.sort((a, b) => b.length - a.length)[0] || [];
            for (const page of pageMenu) {
                if (seen.has(page.id)) continue;
                seen.add(page.id);
                pages.push(page);
            }
            if (!pages.length) return JSON.stringify({error: 'VERIFYING'});
            pages.sort((a, b) => Number(a.id.slice(-3)) - Number(b.id.slice(-3)));
            return JSON.stringify({
                date: '${date}',
                latestDate: '${date}',
                isJangEdition: true,
                pages: pages
            });
        })()
    """.trimIndent()

    override fun articleExtractionScript(storyId: String): String = """
        (() => {
            const title = document.title.toLowerCase();
            const text = (document.body?.innerText || '').slice(0, 4000).toLowerCase();
            if (title.includes('moment, please') || title.includes('just a moment') ||
                text.includes('verify you are human') || text.includes('checking your browser') ||
                text.includes('security verification') ||
                text.includes('request is being verified') ||
                document.querySelector('#challenge-running, #challenge-stage')) {
                return JSON.stringify({verifying: true});
            }
            const content = document.querySelector('.detail-content');
            const body = content?.querySelector('.description-area .detail_view_content') ||
                content?.querySelector('.description-area');
            const articleTitle = document.querySelector('h1')?.innerText.trim() || '';
            const author = document.querySelector('.detail-w-t .authorFullName')
                ?.innerText.trim() || '';
            const date = Array.from(document.querySelectorAll('.detail-w-t .detail-time'))
                .map(element => element.innerText.trim())
                .find(value => value && value !== author) || '';
            if (!body || !articleTitle || !body.innerText.trim()) {
                return JSON.stringify({pending: true});
            }
            let paragraphs = Array.from(body.querySelectorAll('p'))
                .map(paragraph => paragraph.innerText.trim())
                .filter(value => value && !value.startsWith('فوٹو:'));
            if (!paragraphs.length) {
                paragraphs = body.innerText.split(/\n+/)
                    .map(value => value.trim())
                    .filter(value => value && !value.startsWith('فوٹو:'));
            }
            const imageUrls = Array.from(content.querySelectorAll('img[src*="/assets/uploads/"]'))
                .map(image => image.currentSrc || image.src).filter(Boolean);
            return JSON.stringify({id: '$storyId', title: articleTitle, author: author,
                date: date, paragraphs: paragraphs, imageUrls: imageUrls});
        })()
    """.trimIndent()

    override fun parseArticle(json: String, storyId: String): NewspaperArticle {
        val data = JSONObject(json)
        if (data.has("error")) throw IllegalStateException(data.getString("error"))
        if (data.optBoolean("verifying")) throw IllegalStateException("VERIFYING")
        if (data.optBoolean("pending")) throw IllegalStateException("PENDING")
        val byline = listOf(data.optString("author"), data.optString("date"))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val article = NewspaperArticle(
            storyId,
            data.getString("title"),
            byline,
            data.getJSONArray("paragraphs").toStringList(),
            data.getJSONArray("imageUrls").toStringList()
        )
        require(article.title.isNotBlank() && article.paragraphs.isNotEmpty()) {
            "Jang returned empty text for story $storyId."
        }
        return article
    }

    override fun hotspotExtractionScript(date: String, pageId: String): String {
        val pageNumber = pageId.takeLast(3)
        return """
            (() => {
              const title = document.title.toLowerCase();
              const text = (document.body?.innerText || '').slice(0, 4000).toLowerCase();
              if (title.includes('moment, please') || title.includes('just a moment') ||
                  text.includes('verify you are human') || text.includes('checking your browser') ||
                  text.includes('security verification') ||
                  text.includes('request is being verified') ||
                  document.querySelector('#challenge-running, #challenge-stage')) {
                return JSON.stringify({verifying: true});
              }
              return JSON.stringify(Array.from(document.querySelectorAll('area[href]')).flatMap(area => {
                const href = area.getAttribute('href') || '';
                const story = href.match(/\/detail\/(\d+)/i);
                const onclick = area.getAttribute('onclick') || '';
                const image = onclick.match(/window\.open\s*\(\s*['"]([^'"]+)['"]/i);
                let type, targetId, imageUrl = null;
                if (story) {
                    type = 'TEXT';
                    targetId = story[1];
                } else if (image) {
                    const path = image[1];
                    const asset = path.match(/^pic\/([A-Za-z0-9_.-]+\.(?:jpe?g|png))(?:\?farman=1)?$/i);
                    if (!asset) return [];
                    type = 'IMAGE';
                    imageUrl = path;
                    targetId = 'J${date}_I${pageNumber}_' + asset[1];
                } else {
                    return [];
                }
                const coordinates = (area.getAttribute('coords') || '')
                    .split(/[;,]/).map(value => value.trim()).filter(Boolean)
                    .map(Number).filter(Number.isFinite);
                return [{targetId: targetId, type: type, imageUrl: imageUrl,
                    coordinates: coordinates, label: area.alt || area.title || '',
                    shape: area.shape || 'poly'}];
              }));
            })()
        """.trimIndent()
    }

    override fun parseHotspots(
        json: String,
        edition: NewspaperEdition,
        page: NewspaperPage
    ): List<NewspaperHotspot> {
        val entries = JSONArray(json)
        return (0 until entries.length()).mapNotNull { index ->
            val entry = entries.getJSONObject(index)
            val coordinates = entry.getJSONArray("coordinates")
            if (coordinates.length() < 4) return@mapNotNull null
            NewspaperHotspot(
                page.id,
                entry.getString("targetId"),
                NewspaperHotspotType.valueOf(entry.getString("type")),
                entry.optString("label"),
                entry.optString("shape", "poly"),
                coordinates.toIntList(),
                entry.optString("imageUrl").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }
}

private fun JSONArray.toIntList(): List<Int> =
    (0 until length()).map { getInt(it) }

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).map { getString(it) }
