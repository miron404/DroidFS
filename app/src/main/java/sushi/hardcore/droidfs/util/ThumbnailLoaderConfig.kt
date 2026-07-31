package sushi.hardcore.droidfs.util

import android.content.Context
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import coil3.video.preferVideoFrameEmbeddedThumbnailKey
import coil3.video.videoFramePercent
import okio.Path.Companion.toPath
import sushi.hardcore.droidfs.filesystems.EncryptedFileReaderFileSystem
import sushi.hardcore.droidfs.filesystems.EncryptedVolume
import java.io.File

@OptIn(ExperimentalCoilApi::class)
object ThumbnailLoaderConfig {
    fun imageLoader(context: Context, encryptedVolume: EncryptedVolume): ImageLoader {
        val cacheDir = File(context.cacheDir, "thumbnails")
        return ImageLoader.Builder(context)
            .diskCache(DiskCache.Builder().directory(cacheDir.absolutePath.toPath()).build())
            .fileSystem(EncryptedFileReaderFileSystem(encryptedVolume))
            .components {
                add(VideoFrameDecoder.Factory())
            }.build()
    }

    fun applyVideoConfig(builder: ImageRequest.Builder) = builder.apply {
        videoFramePercent(0.1)
        preferVideoFrameEmbeddedThumbnailKey(true)
    }

    /** Build a cache key from file path + size + mtime — invalidates on any change. */
    fun cacheKey(fullPath: String, size: Long, mtime: Long): String {
        return "$fullPath|$size|$mtime"
    }
}

