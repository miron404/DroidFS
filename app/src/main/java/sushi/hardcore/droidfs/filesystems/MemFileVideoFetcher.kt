package sushi.hardcore.droidfs.filesystems

import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.MimeTypeMap
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.FileSystem
import okio.Path.Companion.toPath
import sushi.hardcore.droidfs.FileTypes
import sushi.hardcore.droidfs.MemFile
import java.io.File

/**
 * Decrypt a video into a memfd and let the frame decoder read a real file descriptor.
 *
 * Coil's VideoFrameDecoder only passes a path to MediaMetadataRetriever when the image source
 * sits on FileSystem.SYSTEM; for any other file system it wraps the source in a MediaDataSource.
 * That is what happens with an encrypted volume, and it is expensive: extraction runs in the
 * media.extractor process, so every read becomes a binder round trip back into this process --
 * measured at about 85 of them per video.
 *
 * Pointing the retriever at /proc/self/fd/<n> avoids all of it, because
 * MediaMetadataRetriever.setDataSource(String) opens the path here and hands the descriptor over,
 * after which the extractor reads it directly.
 *
 * The decrypted copy lives in a memfd: it is memory, never storage, and it is released as soon as
 * the decoder is done with it. Files above [MAX_SIZE] return null so that Coil falls through to
 * the MediaDataSource path rather than holding a large video in RAM.
 */
class MemFileVideoFetcher(
    private val path: String,
    private val mimeType: String,
    private val volume: EncryptedVolume,
) : Fetcher {

    companion object {
        private const val TAG = "MemFileVideoFetcher"

        /**
         * Above this, the copy is not worth the memory: the decoder runs up to four of these at a
         * time, so this bounds the extra footprint at four times this value.
         */
        private const val MAX_SIZE = 64L * 1024 * 1024

        /**
         * Whether the extractor can actually read a descriptor handed to it this way.
         *
         * Passing an app memfd across processes is what the "open with" path already does, but
         * the media extractor is a separate, tightly confined process, so the first video checks
         * it instead of assuming. On failure this stays false and every request falls through to
         * the MediaDataSource path, which is slower but always works.
         */
        @Volatile
        private var fdPathUsable: Boolean? = null

        private fun canReadFdPath(fdPath: String): Boolean {
            return try {
                MediaMetadataRetriever().apply {
                    setDataSource(fdPath)
                    release()
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "The extractor cannot read a memfd, falling back to MediaDataSource", e)
                false
            }
        }
    }

    override suspend fun fetch(): FetchResult? {
        val size = volume.getAttr(path)?.size ?: return null
        if (size <= 0 || size > MAX_SIZE) {
            // Fall through to the next factory, which reads through MediaDataSource.
            return null
        }
        val start = System.nanoTime()
        // The name is visible as the memfd link target, so it says nothing about the file.
        val memFile = MemFile.create("thumbnail", size) ?: return null
        val copied = try {
            ParcelFileDescriptor.AutoCloseOutputStream(memFile.dup()).use {
                volume.exportFile(path, it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stage the video in memory", e)
            false
        }
        if (!copied) {
            memFile.close()
            return null
        }
        val reader = memFile.dup()
        val fdPath = "/proc/self/fd/${reader.fd}"
        if (fdPathUsable == null) {
            fdPathUsable = canReadFdPath(fdPath)
        }
        if (fdPathUsable != true) {
            reader.close()
            memFile.close()
            return null
        }
        val copyMillis = (System.nanoTime() - start) / 1000000
        Log.i(TAG, "staged ${size / 1024}KiB in ${copyMillis}ms")
        val staged = System.nanoTime()
        return SourceFetchResult(
            source = ImageSource(
                file = fdPath.toPath(),
                fileSystem = FileSystem.SYSTEM,
                closeable = AutoCloseable {
                    Log.i(TAG, "decoded in ${(System.nanoTime() - staged) / 1000000}ms")
                    reader.close()
                    memFile.close()
                },
            ),
            mimeType = mimeType,
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory(private val volume: EncryptedVolume) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme
            if (scheme != null && scheme != "file") return null
            val path = data.path ?: return null
            if (!FileTypes.isVideo(path)) return null
            val mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(File(path).extension.lowercase())
                ?.takeIf { it.startsWith("video/") }
                ?: "video/*"
            return MemFileVideoFetcher(path, mimeType, volume)
        }
    }
}
