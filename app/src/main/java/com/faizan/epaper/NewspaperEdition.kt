package com.faizan.epaper

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.webkit.CookieManager

data class NewspaperPage(val id: String, val title: String) {
    val imageFileName: String
        get() = "$id.jpg"
}

enum class NewspaperHotspotType {
    TEXT,
    IMAGE
}

data class NewspaperHotspot(
    val pageId: String,
    val targetId: String,
    val type: NewspaperHotspotType,
    val label: String,
    val shape: String,
    val coordinates: List<Int>,
    val imageUrl: String? = null
)

data class NewspaperArticle(
    val id: String,
    val title: String,
    val byline: String,
    val paragraphs: List<String>,
    val imageUrls: List<String> = emptyList()
)

data class NewspaperEdition(
    val date: String,
    val latestDate: String,
    val pages: List<NewspaperPage>,
    val fullImageIds: List<String> = emptyList(),
    val hotspots: List<NewspaperHotspot> = emptyList(),
    val articles: List<NewspaperArticle> = emptyList(),
    val processedPageIds: List<String> = emptyList(),
    val isJangEdition: Boolean = false,
    val jangImageAreasVersion: Int = 0,
    val paperId: NewspaperId = if (isJangEdition) {
        NewspaperId.JANG_LAHORE
    } else {
        NewspaperId.DAWN
    }
) {
    val readingOrder: List<NewspaperPage>
        get() {
            fun pageNumber(page: NewspaperPage): Int =
                page.id.takeLast(3).toIntOrNull() ?: Int.MAX_VALUE

            fun priority(page: NewspaperPage): Int = when {
                page.title.contains("front", ignoreCase = true) -> 0
                page.title.contains("back", ignoreCase = true) -> 1
                listOf("opinion", "editorial", "op-ed", "oped").any {
                    page.title.contains(it, ignoreCase = true)
                } -> 2
                else -> 3
            }

            return pages.sortedWith(compareBy(::priority, ::pageNumber))
        }

    fun downloadPageImage(
        directory: File,
        id: String,
        jangImageDownloader: JangImageDownloader? = null
    ) {
        require(pages.any { it.id == id }) { "Invalid newspaper page ID." }
        if (isJangEdition) {
            downloadJangPageImage(directory, id, jangImageDownloader)
        } else {
            downloadImages(directory, listOf(id), "pages", { pageId -> "$pageId.jpg" }) { _, _ -> }
        }

    }

    fun hasPageImage(directory: File, id: String): Boolean =
        pages.any { it.id == id } &&
            (File(directory, "$id.jpg").isImageFile() || File(directory, "$id.png").isImageFile())

    fun downloadPageImages(directory: File, onProgress: (Int, Int) -> Unit) {
        downloadImages(
            directory,
            readingOrder.map { it.id },
            "pages",
            { id -> "$id.jpg" },
            onProgress
        )
    }

    fun fullImageFile(directory: File, id: String): File =
        File(File(directory, "images"), if (isJangEdition) id else "$id.jpg")

    fun hasFullImage(directory: File, id: String): Boolean {
        val imageFile = fullImageFile(directory, id)
        if (!imageFile.isImageFile()) return false
        if (!isJangEdition) return true
        val hotspot = hotspots.firstOrNull {
            it.targetId == id && it.type == NewspaperHotspotType.IMAGE
        } ?: return false
        return !isJangImagePageScanCopy(directory, imageFile, hotspot.pageId)
    }

    fun downloadFullImages(
        directory: File,
        ids: List<String>,
        onProgress: (Int, Int) -> Unit,
        jangImageDownloader: JangImageDownloader? = null
    ) {
        if (isJangEdition) {
            downloadJangImages(directory, ids, onProgress, jangImageDownloader)
            return
        }
        downloadImages(
            File(directory, "images"),
            ids,
            "stories",
            { id -> "$id.jpg" },
            onProgress
        )
    }

    fun articleImageFile(directory: File, article: NewspaperArticle, index: Int): File? {
        if (!isJangEdition) return null
        val source = article.imageUrls.getOrNull(index) ?: return null
        val url = validatedJangArticleImageUrl(source)
        val extension = url.path.substringAfterLast('.').lowercase()
        val safeArticleId = article.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(File(directory, "article-images"), "$safeArticleId-$index.$extension")
    }

    fun hasArticleImages(directory: File): Boolean =
        articles.all { article ->
            article.imageUrls.indices.all { index ->
                hasArticleImage(directory, article, index)
            }
        }

    fun hasArticleImage(directory: File, article: NewspaperArticle, index: Int): Boolean =
        articleImageFile(directory, article, index)?.isImageFile() ?: true

    fun downloadArticleImages(
        directory: File,
        jangImageDownloader: JangImageDownloader? = null
    ) = downloadArticleImages(directory, articles, jangImageDownloader)

    fun downloadArticleImages(
        directory: File,
        articlesToDownload: List<NewspaperArticle>,
        jangImageDownloader: JangImageDownloader? = null
    ) {
        if (!isJangEdition) return
        val imageDirectory = File(directory, "article-images")
        if (articlesToDownload.any { it.imageUrls.isNotEmpty() } &&
            !imageDirectory.exists() && !imageDirectory.mkdirs()
        ) {
            throw IllegalStateException("Could not create article image storage.")
        }
        articlesToDownload.forEach { article ->
            article.imageUrls.indices.forEach { index ->
                val destination = articleImageFile(directory, article, index)
                    ?: throw IllegalStateException("Jang article image has no saved source URL.")
                if (!destination.isImageFile()) {
                    downloadJangArticleImage(
                        validatedJangArticleImageUrl(article.imageUrls[index]),
                        article.id,
                        destination,
                        jangImageDownloader
                    )
                }
            }
        }
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

    private fun downloadJangImages(
        directory: File,
        ids: List<String>,
        onProgress: (Int, Int) -> Unit,
        jangImageDownloader: ((URL, String, File) -> Unit)?
    ) {
        val imageDirectory = File(directory, "images")
        if (!imageDirectory.exists() && !imageDirectory.mkdirs()) {
            throw IllegalStateException("Could not create image storage.")
        }
        ids.forEachIndexed { index, id ->
            val hotspot = hotspots.firstOrNull {
                it.targetId == id && it.type == NewspaperHotspotType.IMAGE
            } ?: throw IllegalStateException("Jang image $id is not listed in this edition.")
            val imageUrl = hotspot.imageUrl
                ?: throw IllegalStateException("Jang image $id has no saved source URL.")
            val imagePath = imageUrl.substringBefore('?')
            val extension = imagePath.substringAfterLast('.', "").lowercase()
            require(extension in setOf("jpg", "jpeg", "png")) {
                "Jang returned an unsupported image file."
            }
            require(id.matches(JANG_IMAGE_ID_PATTERN) && id.endsWith(".$extension")) {
                "Invalid Jang image ID."
            }
            val pageNumber = hotspot.pageId.substringAfterLast('_').toInt()
            val dateParts = date.split('_')
            val jangDate = "${dateParts[0]}-${dateParts[1]}-${dateParts[2]}"
            val staticDate = "${dateParts[1]}-${dateParts[0].toInt()}-${dateParts[2]}"
            require(imageUrl.matches(JANG_IMAGE_PATH_PATTERN)) {
                "Jang returned an invalid image URL."
            }
            val imageFileName = imagePath.substringAfterLast('/')
            val url = URL(
                "https://e.jang.com.pk/static_pages/$staticDate/lahore/small/$imageFileName"
            )
            val destination = File(imageDirectory, id)
            if (!hasFullImage(directory, id)) {
                if (destination.exists() && !destination.delete()) {
                    throw IllegalStateException("Could not remove the invalid Jang image $id.")
                }
                downloadJangImage(
                    url,
                    jangDate,
                    pageNumber,
                    destination,
                    jangImageDownloader
                )
                if (isJangImagePageScanCopy(directory, destination, hotspot.pageId)) {
                    if (!destination.delete()) {
                        throw IllegalStateException("Could not remove the page scan saved as image $id.")
                    }
                    throw IllegalStateException("Jang returned the page scan instead of image $id.")
                }
            }
            onProgress(index + 1, ids.size)
        }
    }

    private fun isJangImagePageScanCopy(directory: File, imageFile: File, pageId: String): Boolean {
        val pageImages = listOf(
            File(directory, "$pageId.jpg"),
            File(directory, "$pageId.png")
        )
        return pageImages.any { pageImage ->
            filesHaveSameContents(pageImage, imageFile)
        }
    }

    private fun filesHaveSameContents(first: File, second: File): Boolean {
        if (!first.isFile || !second.isFile || first.length() != second.length()) return false
        first.inputStream().buffered().use { firstInput ->
            second.inputStream().buffered().use { secondInput ->
                val firstBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val secondBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val firstRead = firstInput.read(firstBuffer)
                    val secondRead = secondInput.read(secondBuffer)
                    if (firstRead != secondRead) return false
                    if (firstRead < 0) return true
                    for (index in 0 until firstRead) {
                        if (firstBuffer[index] != secondBuffer[index]) return false
                    }
                }
            }
        }
    }

    private fun downloadJangArticleImage(
        url: URL,
        articleId: String,
        destination: File,
        jangImageDownloader: JangImageDownloader?
    ) {
        if (jangImageDownloader != null) {
            val temporaryImage = File(destination.parentFile, "${destination.name}.download")
            try {
                jangImageDownloader(
                    url,
                    "https://e.jang.com.pk/detail/$articleId",
                    temporaryImage
                )
                require(temporaryImage.isImageFile()) {
                    "Jang returned invalid article image data for $url."
                }
                if (destination.exists() && !destination.delete()) {
                    throw IllegalStateException("Could not replace the saved Jang article image.")
                }
                if (!temporaryImage.renameTo(destination)) {
                    throw IllegalStateException("Could not save the Jang article image.")
                }
            } catch (error: Exception) {
                temporaryImage.delete()
                throw error
            }
            return
        }
        var lastFailure: Exception? = null
        repeat(3) { attempt ->
            try {
                downloadJangArticleImageOnce(url, articleId, destination)
                return
            } catch (error: Exception) {
                lastFailure = error
                if (!isRetryableJangImageError(error) || attempt == 2) throw error
                pauseBeforeJangImageRetry(attempt)
            }
        }
        throw IllegalStateException("Could not download the Jang article image.", lastFailure)
    }

    private fun downloadJangArticleImageOnce(url: URL, articleId: String, destination: File) {
        val temporaryImage = File(destination.parentFile, "${destination.name}.tmp")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            applyJangImageHeaders(url, "https://e.jang.com.pk/detail/$articleId")
        }
        try {
            connection.connect()
            require(connection.url.protocol == "https" &&
                connection.url.host.equals(url.host, ignoreCase = true)
            ) {
                "Jang redirected an article image to an unexpected host."
            }
            if (connection.responseCode !in 200..299) {
                throw jangImageResponseError(
                    url,
                    connection,
                    "Jang returned HTTP ${connection.responseCode} for an article image."
                )
            }
            val extension = url.path.substringAfterLast('.').lowercase()
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (!isJangImageContentType(contentType)) {
                throw jangImageResponseError(
                    url,
                    connection,
                    "Jang returned ${contentType ?: "no content type"} instead of an article image."
                )
            }
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) {
                throw IllegalStateException("Jang article image is larger than expected.")
            }
            connection.inputStream.use { input ->
                FileOutputStream(temporaryImage).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val signature = ByteArray(if (extension == "png") 8 else 2)
                    var signatureBytes = 0
                    var totalBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        val copyLength = minOf(read, signature.size - signatureBytes)
                        if (copyLength > 0) {
                            buffer.copyInto(signature, signatureBytes, 0, copyLength)
                            signatureBytes += copyLength
                        }
                        totalBytes += read
                        if (totalBytes > MAX_IMAGE_BYTES) {
                            throw IllegalStateException("Jang article image is larger than expected.")
                        }
                        output.write(buffer, 0, read)
                    }
                    val validSignature = if (extension == "png") {
                        signature.contentEquals(
                            byteArrayOf(
                                0x89.toByte(), 0x50, 0x4e, 0x47,
                                0x0d, 0x0a, 0x1a, 0x0a
                            )
                        )
                    } else {
                        signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte()
                    }
                    if (!validSignature) {
                        throw IllegalStateException("Jang returned an invalid article image.")
                    }
                }
            }
            if (destination.exists() && !destination.delete()) {
                throw IllegalStateException("Could not replace the saved Jang article image.")
            }
            if (!temporaryImage.renameTo(destination)) {
                throw IllegalStateException("Could not save the Jang article image.")
            }
        } catch (error: Exception) {
            temporaryImage.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun validatedJangArticleImageUrl(source: String): URL {
        val url = URL(source)
        require(url.protocol == "https" && url.host.equals("e.jang.com.pk", ignoreCase = true) &&
            url.path.startsWith("/assets/uploads/") &&
            url.path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png")
        ) {
            "Jang returned an invalid article image URL."
        }
        return url
    }

    private fun downloadJangImage(
        url: URL,
        jangDate: String,
        pageNumber: Int,
        destination: File,
        jangImageDownloader: JangImageDownloader?
    ) {
        if (jangImageDownloader != null) {
            val extension = url.path.substringAfterLast('.').lowercase()
            val temporaryImage = File(
                destination.parentFile,
                "${destination.name}.download.$extension"
            )
            try {
                jangImageDownloader(
                    url,
                    "https://e.jang.com.pk/lahore/$jangDate/page$pageNumber",
                    temporaryImage
                )
                require(temporaryImage.isImageFile()) {
                    "Jang returned invalid image data for $url."
                }
                if (destination.exists() && !destination.delete()) {
                    throw IllegalStateException("Could not replace the saved Jang image.")
                }
                if (!temporaryImage.renameTo(destination)) {
                    throw IllegalStateException("Could not save the Jang image.")
                }
            } catch (error: Exception) {
                temporaryImage.delete()
                throw error
            }
            return
        }
        var lastFailure: Exception? = null
        repeat(3) { attempt ->
            try {
                downloadJangImageOnce(url, jangDate, pageNumber, destination)
                return
            } catch (error: Exception) {
                lastFailure = error
                val retryable = isRetryableJangImageError(error)
                if (!retryable || attempt == 2) throw error
                pauseBeforeJangImageRetry(attempt)
            }
        }
        throw IllegalStateException("Could not download the Jang image.", lastFailure)
    }

    private fun downloadJangImageOnce(
        url: URL,
        jangDate: String,
        pageNumber: Int,
        destination: File
    ) {
        val temporaryImage = File(destination.parentFile, "${destination.name}.tmp")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            applyJangImageHeaders(url, "https://e.jang.com.pk/lahore/$jangDate/page$pageNumber")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw jangImageResponseError(
                    url,
                    connection,
                    "Jang returned HTTP ${connection.responseCode} for an image."
                )
            }
            val extension = url.path.substringAfterLast('.').lowercase()
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (!isJangImageContentType(contentType)) {
                throw jangImageResponseError(
                    url,
                    connection,
                    "Jang returned ${contentType ?: "no content type"} instead of an image."
                )
            }
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) {
                throw IllegalStateException("Jang image is larger than expected.")
            }
            connection.inputStream.use { input ->
                FileOutputStream(temporaryImage).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val signature = ByteArray(if (extension == "png") 8 else 2)
                    var signatureBytes = 0
                    var totalBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        val copyLength = minOf(read, signature.size - signatureBytes)
                        if (copyLength > 0) {
                            buffer.copyInto(signature, signatureBytes, 0, copyLength)
                            signatureBytes += copyLength
                        }
                        totalBytes += read
                        if (totalBytes > MAX_IMAGE_BYTES) {
                            throw IllegalStateException("Jang image is larger than expected.")
                        }
                        output.write(buffer, 0, read)
                    }
                    val validSignature = if (extension == "png") {
                        signature.contentEquals(
                            byteArrayOf(
                                0x89.toByte(), 0x50, 0x4e, 0x47,
                                0x0d, 0x0a, 0x1a, 0x0a
                            )
                        )
                    } else {
                        signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte()
                    }
                    if (!validSignature) throw IllegalStateException("Jang returned an invalid image.")
                }
            }
            if (destination.exists() && !destination.delete()) {
                throw IllegalStateException("Could not replace the saved Jang image.")
            }
            if (!temporaryImage.renameTo(destination)) {
                throw IllegalStateException("Could not save the Jang image.")
            }
        } catch (error: Exception) {
            temporaryImage.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun saveMetadata(directory: File) {
        val metadata = JSONObject().apply {
            put("date", date)
            put("latestDate", latestDate)
            put("paperId", paperId.name)
            put("isJangEdition", isJangEdition)
            put("jangImageAreasVersion", jangImageAreasVersion)
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
                        hotspot.imageUrl?.let { put("imageUrl", it) }
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
                        put("imageUrls", JSONArray(article.imageUrls))
                    })
                }
            })
            put("processedPageIds", JSONArray(processedPageIds))
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
        synchronized(dawnImageDownloadLock) {
            if (destination.isJpeg()) return
            val parts = id.split('_')
            require(
                (PAGE_PATTERN.matches(id) || STORY_PATTERN.matches(id)) &&
                    parts.take(3).joinToString("_") == date
            ) { "Invalid Dawn image ID." }
            val url = URL(
                "https://e.dawn.com/${parts[2]}/${parts[1]}/${parts[0]}/$remoteSection/$id.jpg"
            )
            val temporaryImage = File.createTempFile(
                "${id}_",
                ".jpg.tmp",
                destination.parentFile
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DawnEpaperAndroid/1.0")
            }

            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException(
                        "Dawn returned HTTP ${connection.responseCode} for image $id."
                    )
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
                            if (secondByte < 0 && read > 1) {
                                secondByte = buffer[1].toInt() and 0xff
                            }
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
                if (destination.exists() && !destination.delete()) {
                    throw IllegalStateException("Could not replace image $id.")
                }
                if (!temporaryImage.renameTo(destination) && !destination.isJpeg()) {
                    throw IllegalStateException("Could not save image $id.")
                }
            } catch (error: Exception) {
                temporaryImage.delete()
                throw error
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun downloadJangPageImage(
        directory: File,
        id: String,
        jangImageDownloader: JangImageDownloader?
    ) {
        require(JANG_PAGE_PATTERN.matches(id) && id.startsWith("J${date}_")) {
            "Invalid Jang page ID."
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create image storage.")
        }
        val dateParts = date.split('_')
        val jangDate = "${dateParts[0]}-${dateParts[1]}-${dateParts[2]}"
        val staticDate = "${dateParts[1]}-${dateParts[0].toInt()}-${dateParts[2]}"
        val pageNumber = id.substringAfterLast('_').toInt()
        val referer = "https://e.jang.com.pk/lahore/$jangDate/page$pageNumber"
        var lastFailure: Exception? = null
        for (extension in listOf("jpg", "png")) {
            val url = URL(
                "https://e.jang.com.pk/static_pages/$staticDate/lahore/mainpage/page$pageNumber.$extension"
            )
            for (attempt in 0 until 3) {
                try {
                    downloadJangPageImageOnce(
                        url,
                        referer,
                        directory,
                        id,
                        pageNumber,
                        extension,
                        jangImageDownloader
                    )
                    return
                } catch (error: Exception) {
                    lastFailure = error
                    if (!isRetryableJangImageError(error) || attempt == 2) break
                    pauseBeforeJangImageRetry(attempt)
                }
            }
        }
        val failureDetail = lastFailure?.message ?: "No response details were available."
        throw IllegalStateException(
            "Could not download Jang page $pageNumber as JPG or PNG: $failureDetail",
            lastFailure
        )
    }

    private fun downloadJangPageImageOnce(
        url: URL,
        referer: String,
        directory: File,
        id: String,
        pageNumber: Int,
        extension: String,
        jangImageDownloader: ((URL, String, File) -> Unit)?
    ) {
        val destination = File(directory, "$id.$extension")
        val temporaryImage = File(directory, "$id.download.tmp.$extension")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            applyJangImageHeaders(url, referer)
        }
        try {
            if (jangImageDownloader != null) {
                jangImageDownloader(url, referer, temporaryImage)
                require(temporaryImage.isImageFile()) {
                    "Jang returned invalid image data for $url."
                }
                val otherFormat = File(directory, "$id.${if (extension == "jpg") "png" else "jpg"}")
                if (otherFormat.exists() && !otherFormat.delete()) {
                    throw IllegalStateException("Could not replace Jang page $pageNumber.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IllegalStateException("Could not replace Jang page $pageNumber.")
                }
                if (!temporaryImage.renameTo(destination)) {
                    throw IllegalStateException("Could not save Jang page $pageNumber.")
                }
                return
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw jangImageResponseError(
                    url,
                    connection,
                    "Jang returned HTTP ${connection.responseCode} for an image."
                )
            }
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (!isJangImageContentType(contentType)) {
                throw jangImageResponseError(
                    url,
                    connection,
                    "Jang returned ${contentType ?: "no content type"} instead of an image."
                )
            }
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) {
                throw IllegalStateException("Jang page $pageNumber is larger than expected.")
            }
            connection.inputStream.use { input ->
                FileOutputStream(temporaryImage).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val signature = ByteArray(if (extension == "png") 8 else 2)
                    var signatureBytes = 0
                    var totalBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        val signatureLength = minOf(read, signature.size - signatureBytes)
                        if (signatureLength > 0) {
                            buffer.copyInto(signature, signatureBytes, 0, signatureLength)
                            signatureBytes += signatureLength
                        }
                        totalBytes += read
                        if (totalBytes > MAX_IMAGE_BYTES) {
                            throw IllegalStateException("Jang page $pageNumber is larger than expected.")
                        }
                        output.write(buffer, 0, read)
                    }
                    val validSignature = if (extension == "png") {
                        signature.contentEquals(
                            byteArrayOf(
                                0x89.toByte(), 0x50, 0x4e, 0x47,
                                0x0d, 0x0a, 0x1a, 0x0a
                            )
                        )
                    } else {
                        signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte()
                    }
                    if (!validSignature) {
                        throw IllegalStateException(
                            "Jang returned invalid image data for page $pageNumber."
                        )
                    }
                }
            }
            val otherFormat = File(directory, "$id.${if (extension == "jpg") "png" else "jpg"}")
            if (otherFormat.exists() && !otherFormat.delete()) {
                throw IllegalStateException("Could not replace Jang page $pageNumber.")
            }
            if (destination.exists() && !destination.delete()) {
                throw IllegalStateException("Could not replace Jang page $pageNumber.")
            }
            if (!temporaryImage.renameTo(destination)) {
                throw IllegalStateException("Could not save Jang page $pageNumber.")
            }
        } catch (error: Exception) {
            temporaryImage.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.applyJangImageHeaders(url: URL, referer: String) {
        setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
        )
        setRequestProperty("Referer", referer)
        setRequestProperty("Accept", "*/*")
        CookieManager.getInstance().getCookie(url.toString())
            ?.takeIf { it.isNotBlank() }
            ?.let { setRequestProperty("Cookie", it) }
    }

    private fun jangImageResponseError(
        requestedUrl: URL,
        connection: HttpURLConnection,
        reason: String
    ): IllegalStateException {
        val responseBody = try {
            val stream = if (connection.responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var totalBytes = 0
                while (totalBytes < MAX_JANG_RESPONSE_BODY_BYTES) {
                    val remaining = MAX_JANG_RESPONSE_BODY_BYTES - totalBytes
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    totalBytes += read
                }
                val truncated = totalBytes == MAX_JANG_RESPONSE_BODY_BYTES && input.read() >= 0
                String(output.toByteArray(), Charsets.UTF_8) +
                    if (truncated) "\n[response body truncated]" else ""
            } ?: "[response body unavailable]"
        } catch (error: java.io.IOException) {
            "[could not read response body: ${error.message ?: error.javaClass.simpleName}]"
        }
        return IllegalStateException(
            "$reason\n" +
                "Requested URL: $requestedUrl\n" +
                "HTTP status: ${connection.responseCode}\n" +
                "Final URL: ${connection.url}\n" +
                "Content-Type: ${connection.contentType ?: "unknown"}\n" +
                "Response body:\n$responseBody"
        )
    }

    private fun isJangImageContentType(contentType: String?): Boolean =
        contentType?.startsWith("image/") == true || contentType == "application/octet-stream"

    private fun isRetryableJangImageError(error: Exception): Boolean {
        if (error is java.io.IOException) return true
        val message = error.message.orEmpty()
        return "instead of an" in message ||
            "invalid image" in message ||
            Regex("Jang returned HTTP (403|408|429|5\\d{2})").containsMatchIn(message)
    }

    private fun pauseBeforeJangImageRetry(attempt: Int) {
        try {
            Thread.sleep(500L * (attempt + 1))
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Jang image download was interrupted.", interrupted)
        }
    }

    companion object {
        private val dawnImageDownloadLock = Any()
        private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
        private const val MAX_JANG_RESPONSE_BODY_BYTES = 16 * 1024
        private val DATE_PATTERN = Regex("\\d{2}_\\d{2}_\\d{4}")
        private val PAGE_PATTERN = Regex("\\d{2}_\\d{2}_\\d{4}_\\d{3}")
        private val JANG_PAGE_PATTERN = Regex("J\\d{2}_\\d{2}_\\d{4}_\\d{3}")
        private val JANG_IMAGE_ID_PATTERN =
            Regex("J\\d{2}_\\d{2}_\\d{4}_I\\d{3}_[A-Za-z0-9_.-]+\\.(jpg|jpeg|png)")
        private val JANG_IMAGE_PATH_PATTERN =
            Regex("pic/[A-Za-z0-9_.-]+\\.(jpg|jpeg|png)(\\?farman=1)?", RegexOption.IGNORE_CASE)
        private val STORY_PATTERN = Regex("\\d{2}_\\d{2}_\\d{4}_\\d{3}_\\d{3}")

        private fun isValidPageId(id: String, date: String, isJangEdition: Boolean): Boolean =
            if (isJangEdition) JANG_PAGE_PATTERN.matches(id) && id.startsWith("J${date}_")
            else PAGE_PATTERN.matches(id) && id.startsWith("${date}_")

        fun fromJson(json: String): NewspaperEdition {
            val data = JSONObject(json)
            val date = data.getString("date")
            val latestDate = data.getString("latestDate")
            val legacyJangEdition = data.optBoolean("isJangEdition", false)
            val paperId = if (data.has("paperId")) {
                runCatching { NewspaperId.valueOf(data.getString("paperId")) }
                    .getOrElse { throw IllegalArgumentException("The saved paper ID is invalid.", it) }
            } else if (legacyJangEdition) {
                NewspaperId.JANG_LAHORE
            } else {
                NewspaperId.DAWN
            }
            val isJangEdition = paperId == NewspaperId.JANG_LAHORE
            require(!data.has("paperId") || legacyJangEdition == isJangEdition) {
                "The saved paper metadata is inconsistent."
            }
            require(DATE_PATTERN.matches(date) && DATE_PATTERN.matches(latestDate)) {
                "The newspaper returned an invalid edition date."
            }
            val pages = data.getJSONArray("pages").objects().map { item ->
                val id = item.getString("id")
                require(isValidPageId(id, date, isJangEdition)) {
                    "The newspaper returned an invalid page ID."
                }
                NewspaperPage(id, item.optString("title").ifBlank { "Page ${id.takeLast(3)}" })
            }.distinctBy { it.id }
            require(pages.isNotEmpty()) { "No pages were listed for this edition." }

            val hotspots = data.optJSONArray("hotspots")?.objects()?.map { item ->
                val pageId = item.getString("pageId")
                val targetId = item.getString("targetId")
                val type = NewspaperHotspotType.valueOf(item.getString("type"))
                require(isValidPageId(pageId, date, isJangEdition)) {
                    "The newspaper returned an invalid hotspot page ID."
                }
                require(
                    if (isJangEdition && type == NewspaperHotspotType.IMAGE) {
                        JANG_IMAGE_ID_PATTERN.matches(targetId) &&
                            targetId.startsWith("J${date}_")
                    } else if (isJangEdition) targetId.matches(Regex("\\d+"))
                    else STORY_PATTERN.matches(targetId) && targetId.startsWith("${date}_")
                ) {
                    "The newspaper returned an invalid hotspot target ID."
                }
                if (isJangEdition && type == NewspaperHotspotType.IMAGE) {
                    require(item.optString("imageUrl").matches(JANG_IMAGE_PATH_PATTERN)) {
                        "The newspaper returned an invalid Jang image URL."
                    }
                }
                val coordinates = item.getJSONArray("coordinates").ints()
                require(coordinates.size >= 4) { "Dawn returned invalid hotspot coordinates." }
                NewspaperHotspot(
                    pageId,
                    targetId,
                    type,
                    item.optString("label"),
                    item.optString("shape", "rect"),
                    coordinates,
                    item.optString("imageUrl").takeIf { it.isNotBlank() }
                )
            }.orEmpty()

            val fullImageIds = data.optJSONArray("fullImageIds")?.strings().orEmpty()
            require(fullImageIds.all {
                if (isJangEdition) {
                    JANG_IMAGE_ID_PATTERN.matches(it) && it.startsWith("J${date}_")
                } else STORY_PATTERN.matches(it) && it.startsWith("${date}_")
            }) {
                "Dawn returned an invalid image ID."
            }
            val articles = data.optJSONArray("articles")?.objects()?.map { item ->
                val id = item.getString("id")
                require(
                    if (isJangEdition) id.matches(Regex("\\d+"))
                    else STORY_PATTERN.matches(id) && id.startsWith("${date}_")
                ) {
                    "The newspaper returned an invalid story ID."
                }
                NewspaperArticle(
                    id,
                    item.getString("title"),
                    item.optString("byline"),
                    item.getJSONArray("paragraphs").strings(),
                    item.optJSONArray("imageUrls")?.strings().orEmpty()
                )
            }.orEmpty()
            val jangImageAreasVersion = data.optInt("jangImageAreasVersion", 0)
            val savedProcessedPageIds = data.optJSONArray("processedPageIds")?.strings().orEmpty()
            val processedPageIds = if (isJangEdition && jangImageAreasVersion < 1) {
                emptyList()
            } else {
                savedProcessedPageIds
            }
            require(processedPageIds.all { id ->
                isValidPageId(id, date, isJangEdition) && pages.any { it.id == id }
            }) {
                "Dawn returned invalid processed page IDs."
            }
            return NewspaperEdition(
                date,
                latestDate,
                pages,
                fullImageIds.distinct(),
                hotspots,
                articles,
                processedPageIds.distinct(),
                isJangEdition,
                jangImageAreasVersion,
                paperId
            )
        }

        fun readMetadata(file: File): NewspaperEdition =
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

        private fun File.isImageFile(): Boolean =
            isJpeg() || (isFile && extension == "png" && inputStream().use {
                val signature = ByteArray(8)
                it.read(signature) == signature.size && signature.contentEquals(
                    byteArrayOf(
                        0x89.toByte(), 0x50, 0x4e, 0x47,
                        0x0d, 0x0a, 0x1a, 0x0a
                    )
                )
            })
    }
}
