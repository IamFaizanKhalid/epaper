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

data class DawnEdition(
    val date: String,
    val latestDate: String,
    val pages: List<DawnPage>,
    val storyIds: List<String> = emptyList()
) {
    fun downloadPageImages(directory: File, onProgress: (Int, Int) -> Unit) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create the edition storage folder.")
        }
        pages.forEachIndexed { index, page ->
            val destination = File(directory, page.imageFileName)
            if (!destination.isJpeg()) {
                downloadJpeg(page.id, destination)
            }
            onProgress(index + 1, pages.size)
        }
    }

    fun saveMetadata(directory: File, storyIds: List<String>) {
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
            put("storyIds", JSONArray().apply {
                storyIds.forEach { put(it) }
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

    fun removeLegacyStoryImages(directory: File, storyIds: List<String>) {
        val storiesDirectory = File(directory, "stories")
        storyIds.forEach { id ->
            require(STORY_PATTERN.matches(id) && id.startsWith("${date}_")) {
                "Invalid Dawn story ID."
            }
            val image = File(storiesDirectory, "$id.jpg")
            if (image.exists() && !image.delete()) {
                throw IllegalStateException("Could not remove the old story image for $id.")
            }
        }
        if (storiesDirectory.isDirectory &&
            storiesDirectory.list()?.isEmpty() == true &&
            !storiesDirectory.delete()
        ) {
            throw IllegalStateException("Could not remove the empty story image folder.")
        }
    }

    private fun downloadJpeg(id: String, destination: File) {
        val parts = id.split('_')
        require(PAGE_PATTERN.matches(id) && parts.take(3).joinToString("_") == date) {
            "Invalid Dawn page ID."
        }
        val url = URL(
            "https://e.dawn.com/${parts[2]}/${parts[1]}/${parts[0]}/pages/$id.jpg"
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
            val pageArray = data.getJSONArray("pages")
            val pages = ArrayList<DawnPage>(pageArray.length())
            val seenIds = HashSet<String>()
            for (index in 0 until pageArray.length()) {
                val item = pageArray.getJSONObject(index)
                val id = item.getString("id")
                require(PAGE_PATTERN.matches(id) && id.startsWith("${date}_")) {
                    "Dawn returned an invalid page ID."
                }
                if (seenIds.add(id)) {
                    pages.add(DawnPage(id, item.optString("title").ifBlank { "Page ${id.takeLast(3)}" }))
                }
            }
            require(pages.isNotEmpty()) { "No pages were listed for this edition." }

            val storyIds = ArrayList<String>()
            val storyArray = data.optJSONArray("storyIds") ?: JSONArray()
            for (index in 0 until storyArray.length()) {
                val id = storyArray.optString(index)
                require(STORY_PATTERN.matches(id) && id.startsWith("${date}_")) {
                    "Dawn returned an invalid story ID."
                }
                if (id !in storyIds) storyIds.add(id)
            }
            return DawnEdition(date, latestDate, pages, storyIds)
        }

        fun readMetadata(file: File): DawnEdition =
            fromJson(file.readText(Charsets.UTF_8))

        private fun File.isJpeg(): Boolean =
            isFile && length() > 2 && inputStream().use {
                it.read() == 0xff && it.read() == 0xd8
            }
    }
}
