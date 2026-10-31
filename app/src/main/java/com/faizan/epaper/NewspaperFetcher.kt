package com.faizan.epaper

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class NewspaperId(val storageName: String, val displayName: String) {
    DAWN("dawn", "Dawn"),
    JANG_LAHORE("jang", "Jang Lahore")
}

interface NewspaperFetcher {
    val id: NewspaperId
    val supportsImageDetail: Boolean
    val offlineOrigin: String

    fun editionDirectory(root: File, date: String): File
    fun availabilityUrl(date: String): String
    fun pageUrl(date: String, pageId: String): String
    fun articleUrl(storyId: String): String
    fun isPageUrl(url: String, date: String, pageId: String): Boolean
    fun hotspotExtractionScript(date: String, pageId: String): String
    fun parseHotspots(json: String, edition: NewspaperEdition, page: NewspaperPage): List<NewspaperHotspot>
}

object NewspaperFetchers {
    private val fetchers: Map<NewspaperId, NewspaperFetcher> = listOf(
        DawnFetcher(),
        JangLahoreFetcher()
    ).associateBy { it.id }

    val all: List<NewspaperFetcher> = fetchers.values.toList()

    operator fun get(id: NewspaperId): NewspaperFetcher =
        fetchers[id] ?: error("No fetcher is registered for $id.")

    fun forEdition(edition: NewspaperEdition): NewspaperFetcher =
        get(if (edition.isJangEdition) NewspaperId.JANG_LAHORE else NewspaperId.DAWN)
}

private class DawnFetcher : NewspaperFetcher {
    override val id = NewspaperId.DAWN
    override val supportsImageDetail = true
    override val offlineOrigin = "https://offline.dawn/"

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

    override fun isPageUrl(url: String, date: String, pageId: String): Boolean =
        url.startsWith("https://e.dawn.com/") &&
            url.substringBefore('?').endsWith("/pages/$pageId.html")

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
}

private class JangLahoreFetcher : NewspaperFetcher {
    override val id = NewspaperId.JANG_LAHORE
    override val supportsImageDetail = false
    override val offlineOrigin = "https://offline.jang/"

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

    override fun isPageUrl(url: String, date: String, pageId: String): Boolean =
        url.startsWith(pageUrl(date, pageId))

    override fun hotspotExtractionScript(date: String, pageId: String): String {
        val pageNumber = pageId.takeLast(3)
        return """
            JSON.stringify(Array.from(document.querySelectorAll('area[href]')).flatMap(area => {
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
            }))
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
