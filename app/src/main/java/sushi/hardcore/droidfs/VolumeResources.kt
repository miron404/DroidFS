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
import sushi.hardcore.droidfs.filesystems.EncryptedFileReaderFileSystem
import sushi.hardcore.droidfs.filesystems.MemFileVideoDecoder
import sushi.hardcore.droidfs.filesystems.EncryptedVolume

class VolumeResources(val volume: EncryptedVolume, context: Context) {
    private val scopeDelegate = lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val imageLoaderDelegate = lazy {
        ImageLoader.Builder(context).diskCache(null)
            .fileSystem(EncryptedFileReaderFileSystem(volume))
            // Coil decodes on Dispatchers.IO, which is up to 64 threads, and it starts a request
            // for every element rather than the visible ones. A directory of videos then had 45
            // frame decodes in flight at once, all queueing for the handful of hardware decoders
            // the device has: measured 2.8 s median per thumbnail for about 60 ms of real work,
            // with the visible rows waiting behind everything else. Capping the decoder keeps the
            // pipeline just as busy while letting what is on screen finish first.
            .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(4))
            .components {
                // Ours first: it stages the video in memory and falls back to Coil's decoder
                // for anything it cannot handle. Staging happens on the capped decoder context,
                // so at most four copies exist at once.
                add(MemFileVideoDecoder.Factory())
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
