package com.faizan.epaper

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class DawnPage(val id: String, val title: String) {
    val imageFileName: String
        get() = "$id.jpg"
}

enum class DawnHotspotType {
    TEXT,
    IMAGE
}

data class DawnHotspot(
    val pageId: String,
    val targetId: String,
    val type: DawnHotspotType,
    val label: String,
    val shape: String,
    val coordinates: List<Int>
)

data class DawnArticle(
    val id: String,
    val title: String,
    val byline: String,
    val paragraphs: List<String>
)

data class DawnEdition(
    val date: String,
    val latestDate: String,
    val pages: List<DawnPage>,
    val fullImageIds: List<String> = emptyList(),
    val hotspots: List<DawnHotspot> = emptyList(),
    val articles: List<DawnArticle> = emptyList()
) {
    fun downloadPageImages(directory: File, onProgress: (Int, Int) -> Unit) {
        downloadImages(
            directory,
            pages.map { it.id },
            "pages",
            { id -> "$id.jpg" },
            onProgress
        )
    }

    fun downloadFullImages(directory: File, ids: List<String>, onProgress: (Int, Int) -> Unit) {
        downloadImages(
            File(directory, "images"),
            ids,
            "stories",
            { id -> "$id.jpg" },
            onProgress
        )
    }

    private fun downloadImages(
        directory: File,
        ids: List<String>,
        remoteSection: String,
        fileName: (String) -> String,
        onProgress: (Int, Int) -> Unit
    ) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create image storage.")
        }
        ids.forEachIndexed { index, id ->
            val destination = File(directory, fileName(id))
            if (!destination.isJpeg()) {
                downloadJpeg(id, remoteSection, destination)
            }
            onProgress(index + 1, ids.size)
        }
    }

    fun saveMetadata(directory: File) {
        val metadata = JSONObject().apply {
            put("date", date)
            put("latestDate", latestDate)
            put("pages", JSONArray().apply {
                pages.forEach { page ->
                    put(JSONObject().apply {
                        put("id", page.id)
                        put("title", page.title)
                    })
                }
            })
            put("fullImageIds", JSONArray().apply {
                fullImageIds.forEach(::put)
            })
            put("hotspots", JSONArray().apply {
                hotspots.forEach { hotspot ->
                    put(JSONObject().apply {
                        put("pageId", hotspot.pageId)
                        put("targetId", hotspot.targetId)
                        put("type", hotspot.type.name)
                        put("label", hotspot.label)
                        put("shape", hotspot.shape)
                        put("coordinates", JSONArray(hotspot.coordinates))
                    })
                }
            })
            put("articles", JSONArray().apply {
                articles.forEach { article ->
                    put(JSONObject().apply {
                        put("id", article.id)
                        put("title", article.title)
                        put("byline", article.byline)
                        put("paragraphs", JSONArray(article.paragraphs))
                    })
                }
            })
        }
        val temporaryMetadata = File(directory, "edition.json.tmp")
        FileOutputStream(temporaryMetadata).use {
            it.write(metadata.toString().toByteArray(Charsets.UTF_8))
        }
        val metadataFile = File(directory, "edition.json")
        if (!temporaryMetadata.renameTo(metadataFile)) {
            temporaryMetadata.delete()
            throw IllegalStateException("Could not save edition details.")
        }
    }

    fun removeLegacyArchives(directory: File) {
        directory.listFiles()
            ?.filter { it.isFile && it.extension == "mht" }
            ?.forEach { archive ->
                if (!archive.delete()) {
                    throw IllegalStateException("Could not remove the old archive ${archive.name}.")
                }
            }
        val oldStoryImages = File(directory, "stories")
        if (oldStoryImages.isDirectory) {
            oldStoryImages.listFiles()
                ?.filter { it.isFile && it.extension == "jpg" }
                ?.forEach { image ->
                    if (!image.delete()) {
                        throw IllegalStateException("Could not remove the old story image ${image.name}.")
                    }
                }
            if (oldStoryImages.list()?.isEmpty() == true && !oldStoryImages.delete()) {
                throw IllegalStateException("Could not remove the old story image folder.")
            }
        }
    }

    private fun downloadJpeg(id: String, remoteSection: String, destination: File) {
        val parts = id.split('_')
        require(
            (PAGE_PATTERN.matches(id) || STORY_PATTERN.matches(id)) &&
                parts.take(3).joinToString("_") == date
        ) { "Invalid Dawn image ID." }
        val url = URL(
            "https://e.dawn.com/${parts[2]}/${parts[1]}/${parts[0]}/$remoteSection/$id.jpg"
        )
        val temporaryImage = File(destination.parentFile, "$id.jpg.tmp")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DawnEpaperAndroid/1.0")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Dawn returned HTTP ${connection.responseCode} for image $id.")
            }
            if (connection.contentType?.substringBefore(';')?.trim() != "image/jpeg") {
                throw IllegalStateException("Dawn did not return a JPEG for image $id.")
            }
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) {
                throw IllegalStateException("Image $id is larger than expected.")
            }
            connection.inputStream.use { input ->
                FileOutputStream(temporaryImage).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    var firstByte = -1
                    var secondByte = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (firstByte < 0) firstByte = buffer[0].toInt() and 0xff
                        if (secondByte < 0 && read > 1) secondByte = buffer[1].toInt() and 0xff
                        totalBytes += read
                        if (totalBytes > MAX_IMAGE_BYTES) {
                            throw IllegalStateException("Image $id is larger than expected.")
                        }
                        output.write(buffer, 0, read)
                    }
                    if (firstByte != 0xff || secondByte != 0xd8) {
                        throw IllegalStateException("Dawn returned an invalid image for $id.")
                    }
                }
            }
            destination.delete()
            if (!temporaryImage.renameTo(destination)) {
                throw IllegalStateException("Could not save image $id.")
            }
        } catch (error: Exception) {
            temporaryImage.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
        private val DATE_PATTERN = Regex("\\d{2}_\\d{2}_\\d{4}")
        private val PAGE_PATTERN = Regex("\\d{2}_\\d{2}_\\d{4}_\\d{3}")
        private val STORY_PATTERN = Regex("\\d{2}_\\d{2}_\\d{4}_\\d{3}_\\d{3}")

        fun fromJson(json: String): DawnEdition {
            val data = JSONObject(json)
            val date = data.getString("date")
            val latestDate = data.getString("latestDate")
            require(DATE_PATTERN.matches(date) && DATE_PATTERN.matches(latestDate)) {
                "Dawn returned an invalid edition date."
            }
            val pages = data.getJSONArray("pages").objects().map { item ->
                val id = item.getString("id")
                require(PAGE_PATTERN.matches(id) && id.startsWith("${date}_")) {
                    "Dawn returned an invalid page ID."
                }
                DawnPage(id, item.optString("title").ifBlank { "Page ${id.takeLast(3)}" })
            }.distinctBy { it.id }
            require(pages.isNotEmpty()) { "No pages were listed for this edition." }

            val hotspots = data.optJSONArray("hotspots")?.objects()?.map { item ->
                val pageId = item.getString("pageId")
                val targetId = item.getString("targetId")
                require(PAGE_PATTERN.matches(pageId) && pageId.startsWith("${date}_")) {
                    "Dawn returned an invalid hotspot page ID."
                }
                require(STORY_PATTERN.matches(targetId) && targetId.startsWith("${date}_")) {
                    "Dawn returned an invalid hotspot target ID."
                }
                val type = DawnHotspotType.valueOf(item.getString("type"))
                val coordinates = item.getJSONArray("coordinates").ints()
                require(coordinates.size >= 4) { "Dawn returned invalid hotspot coordinates." }
                DawnHotspot(
                    pageId,
                    targetId,
                    type,
                    item.optString("label"),
                    item.optString("shape", "rect"),
                    coordinates
                )
            }.orEmpty()

            val fullImageIds = data.optJSONArray("fullImageIds")?.strings().orEmpty()
            require(fullImageIds.all { STORY_PATTERN.matches(it) && it.startsWith("${date}_") }) {
                "Dawn returned an invalid image ID."
            }
            val articles = data.optJSONArray("articles")?.objects()?.map { item ->
                val id = item.getString("id")
                require(STORY_PATTERN.matches(id) && id.startsWith("${date}_")) {
                    "Dawn returned an invalid story ID."
                }
                DawnArticle(
                    id,
                    item.getString("title"),
                    item.optString("byline"),
                    item.getJSONArray("paragraphs").strings()
                )
            }.orEmpty()
            return DawnEdition(date, latestDate, pages, fullImageIds.distinct(), hotspots, articles)
        }

        fun readMetadata(file: File): DawnEdition =
            fromJson(file.readText(Charsets.UTF_8))

        private fun JSONArray.objects(): List<JSONObject> =
            (0 until length()).map { getJSONObject(it) }

        private fun JSONArray.strings(): List<String> =
            (0 until length()).map { getString(it) }

        private fun JSONArray.ints(): List<Int> =
            (0 until length()).map { getInt(it) }

        private fun File.isJpeg(): Boolean =
            isFile && length() > 2 && inputStream().use {
                it.read() == 0xff && it.read() == 0xd8
            }
    }
}
