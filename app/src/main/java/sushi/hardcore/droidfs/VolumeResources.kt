package sushi.hardcore.droidfs

import android.content.Context
import coil3.Extras
import coil3.ImageLoader
import coil3.video.VideoFrameDecoder
import coil3.video.preferVideoFrameEmbeddedThumbnail
import coil3.video.videoFramePercent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import sushi.hardcore.droidfs.filesystems.EncryptedFileReaderFileSystem
import sushi.hardcore.droidfs.filesystems.EncryptedVolume

class VolumeResources(val volume: EncryptedVolume, context: Context) {
    companion object {
        /**
         * The context video thumbnails decode on, capped where image thumbnails are not.
         *
         * Images decode in process with BitmapFactory: that is plain CPU work, and letting the
         * default IO dispatcher spread it over every core is exactly right. A video frame goes
         * through the device's hardware decoder instead, and there are only a handful of those,
         * so extra requests do not decode in parallel -- they queue. Measured on a directory of
         * videos: 45 in flight gave 2833 ms median per thumbnail, 4 gave 868 ms, and both
         * finished about 4 files a second. The cap does not cost throughput, it stops the rows
         * on screen from waiting behind a hundred that are not.
         *
         * This is a limit, not a thread pool: limitedParallelism hands out a view of the same
         * shared IO threads that lets at most this many of them run at once.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val videoDecoderContext: CoroutineContext = Dispatchers.IO.limitedParallelism(4)
    }

    private val scopeDelegate = lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val imageLoaderDelegate = lazy {
        ImageLoader.Builder(context).diskCache(null)
            .fileSystem(EncryptedFileReaderFileSystem(volume))
            .components {
                add(VideoFrameDecoder.Factory())
            }.also {
                it.extras[Extras.Key.videoFramePercent] = 0.1
                it.extras[Extras.Key.preferVideoFrameEmbeddedThumbnail] = true
            }.build()
    }
    val scope by scopeDelegate
    val imageLoader by imageLoaderDelegate

    fun evictImageCache(shouldEvict: (String) -> Boolean) {
        val cache = imageLoader.memoryCache ?: return
        cache.keys.filter { shouldEvict(it.key) }.forEach { cache.remove(it) }
    }

    fun destroy() {
        if (scopeDelegate.isInitialized()) scope.cancel()
        if (imageLoaderDelegate.isInitialized()) imageLoader.shutdown()
    }
}
