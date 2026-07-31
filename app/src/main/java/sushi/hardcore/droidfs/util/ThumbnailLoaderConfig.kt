package sushi.hardcore.droidfs.util

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import coil3.video.preferVideoFrameEmbeddedThumbnailKey
import coil3.video.videoFramePercent
import sushi.hardcore.droidfs.filesystems.EncryptedFileReaderFileSystem
import sushi.hardcore.droidfs.filesystems.EncryptedVolume
import java.io.File
import java.io.FileOutputStream

object ThumbnailLoaderConfig {
    fun imageLoader(context: Context, encryptedVolume: EncryptedVolume): ImageLoader {
        return ImageLoader.Builder(context)
            .diskCache(null)
            .fileSystem(EncryptedFileReaderFileSystem(encryptedVolume))
            .components {
                add(VideoFrameDecoder.Factory())
            }.build()
    }

    fun applyVideoConfig(builder: ImageRequest.Builder) = builder.apply {
        videoFramePercent(0.1)
        preferVideoFrameEmbeddedThumbnailKey(true)
    }
}

/**
 * Simple file-based thumbnail cache, keyed by path + size + mtime.
 * On cache hit, returns the cached file immediately.
 * On cache miss, saves the decoded bitmap after Coil loads it.
 */
class ThumbnailCache(context: Context) {
    private val cacheDir = File(context.cacheDir, "thumbnails")

    /** Get cached thumbnail file, or null if not present or stale. */
    fun get(fullPath: String, size: Long, mtime: Long): File? {
        val file = cacheFile(fullPath, size, mtime)
        return if (file.isFile) file else null
    }

    /** Save a decoded bitmap to the cache. */
    fun put(fullPath: String, size: Long, mtime: Long, bitmap: Bitmap) {
        val file = cacheFile(fullPath, size, mtime)
        file.parentFile?.mkdirs()
        try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }
        } catch (_: Exception) {
            file.delete()
        }
    }

    private fun cacheFile(fullPath: String, size: Long, mtime: Long): File {
        // Replace '/' and '#' in path to avoid directory traversal and URI issues
        val safePath = fullPath.replace("/", "_").replace("#", "%23")
        return File(cacheDir, "${safePath}_${size}_${mtime}.jpg")
    }
}
