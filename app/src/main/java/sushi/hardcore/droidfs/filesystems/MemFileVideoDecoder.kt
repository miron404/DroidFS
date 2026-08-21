package sushi.hardcore.droidfs.filesystems

import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.drawable.toDrawable
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.video.VideoFrameDecoder
import okio.buffer
import okio.sink
import sushi.hardcore.droidfs.MemFile

/**
 * Decode a video thumbnail from a descriptor rather than through a MediaDataSource.
 *
 * Coil's VideoFrameDecoder hands MediaMetadataRetriever a MediaDataSource for any file system
 * that is not FileSystem.SYSTEM, which an encrypted volume never is. Extraction then runs in the
 * media.extractor process and every read becomes a binder round trip back into this one -- about
 * 85 of them per video in the measurements, which is where the time goes once thumbnails stop
 * queueing against each other.
 *
 * Staging the video in a memfd and passing setDataSource(FileDescriptor) removes all of it: the
 * extractor reads the descriptor directly. Passing the descriptor as the /proc/self/fd/<n> path
 * instead does not work -- setDataSource(String) stats the path first, and for a memfd that is a
 * magic symlink to a deleted inode.
 *
 * The decrypted copy lives in memory, never on storage, and is released as soon as the frame is
 * decoded. Videos larger than [MAX_SIZE], and any device where the extractor refuses a memfd,
 * fall back to Coil's decoder.
 */
class MemFileVideoDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    companion object {
        private const val TAG = "MemFileVideoDecoder"

        /**
         * Above this the copy is not worth the memory: decoding runs up to four at a time, so
         * this bounds the extra footprint at four times the value.
         */
        private const val MAX_SIZE = 64L * 1024 * 1024

        /**
         * Whether the media extractor accepts a descriptor to one of our memfds. Checked once,
         * on the first video, because it is a separate and tightly confined process.
         */
        @Volatile
        private var descriptorUsable: Boolean? = null
    }

    override suspend fun decode(): DecodeResult {
        // Staging reads through its own handle, so `source` stays untouched and delegating
        // remains possible at any point below.
        val memFile = stage() ?: return VideoFrameDecoder(source, options).decode()
        try {
            val start = System.nanoTime()
            val bitmap = ParcelFileDescriptor.AutoCloseInputStream(memFile.dup()).use { input ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(input.fd)
                    if (descriptorUsable == null) {
                        descriptorUsable = true
                    }
                    extractFrame(retriever)
                } catch (e: Exception) {
                    if (descriptorUsable == null) {
                        descriptorUsable = false
                        Log.w(TAG, "The extractor will not take a memfd, using MediaDataSource", e)
                        null
                    } else {
                        throw e
                    }
                } finally {
                    retriever.release()
                }
            }
            if (bitmap == null) {
                return VideoFrameDecoder(source, options).decode()
            }
            Log.i(TAG, "decoded in ${(System.nanoTime() - start) / 1000000}ms")
            return DecodeResult(
                image = bitmap.toDrawable(options.context.resources).asImage(),
                isSampled = true,
            )
        } finally {
            memFile.close()
        }
    }

    private fun extractFrame(retriever: MediaMetadataRetriever): android.graphics.Bitmap? {
        val durationMillis = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        // Same frame Coil would have picked: 10% in, nearest keyframe.
        val frameMicros = (1000 * (0.1 * durationMillis)).toLong()
        val width = options.size.width.pxOrElse { 0 }
        val height = options.size.height.pxOrElse { 0 }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && width > 0 && height > 0) {
            // Decodes straight to the size the row needs instead of full resolution.
            retriever.getScaledFrameAtTime(
                frameMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height
            )
        } else {
            retriever.getFrameAtTime(frameMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }

    /**
     * Copy the decrypted video into a memfd, or return null to let Coil's decoder handle it.
     */
    private fun stage(): MemFile? {
        if (descriptorUsable == false) {
            return null
        }
        val handle = try {
            source.fileSystem.openReadOnly(source.file())
        } catch (e: Exception) {
            return null
        }
        return handle.use {
            val size = it.size()
            if (size <= 0 || size > MAX_SIZE) {
                return@use null
            }
            val memFile = MemFile.create("thumbnail", size) ?: return@use null
            try {
                val start = System.nanoTime()
                ParcelFileDescriptor.AutoCloseOutputStream(memFile.dup()).sink().buffer()
                    .use { sink -> sink.writeAll(it.source()) }
                Log.i(TAG, "staged ${size / 1024}KiB in ${(System.nanoTime() - start) / 1000000}ms")
                memFile
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stage the video in memory", e)
                memFile.close()
                null
            }
        }
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val mimeType = result.mimeType ?: return null
            if (!mimeType.startsWith("video/")) return null
            return MemFileVideoDecoder(result.source, options)
        }
    }
}
