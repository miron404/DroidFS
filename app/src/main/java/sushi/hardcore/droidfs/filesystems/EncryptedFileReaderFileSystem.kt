package sushi.hardcore.droidfs.filesystems

import android.util.Log
import okio.Buffer
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Source
import okio.Timeout

private const val TAG = "EncryptedFileReader"
private const val WINDOW_SIZE = 256 * 1024

fun unsupported(): Nothing = throw UnsupportedOperationException()

class EncryptedFileReaderFileSystem(private val encryptedVolume: EncryptedVolume) : FileSystem() {

    class EncryptedReadOnlyFileHandle(
        private val encryptedVolume: EncryptedVolume,
        private val path: String,
        private val fileHandle: Long
    ) : FileHandle(false) {
        // Video thumbnails reach this handle through MediaDataSource: the media extractor asks for
        // the file in many small pieces, and every one of them costs a JNI call, a gocryptfs block
        // decrypt and a binder round trip back from the extractor process. Serving those pieces
        // from a window read in one go collapses that traffic.
        private val window = ByteArray(WINDOW_SIZE)
        private var windowStart = -1L
        private var windowLength = 0
        private var cachedSize = -1L

        private var requests = 0
        private var nativeReads = 0
        private var bytesRead = 0L
        private var nativeNanos = 0L

        private fun readNative(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
            val start = System.nanoTime()
            val read = encryptedVolume.read(
                fileHandle, fileOffset, array, arrayOffset.toLong(), byteCount.toLong()
            )
            nativeNanos += System.nanoTime() - start
            nativeReads++
            if (read > 0) {
                bytesRead += read
            }
            return read
        }

        override fun protectedClose() {
            if (requests > 0) {
                // Deliberately no file name: this line is meant to be shareable.
                Log.i(
                    TAG,
                    "${cachedSize / 1024}KiB file: $requests requests -> $nativeReads reads, " +
                    "${bytesRead / 1024}KiB read, ${nativeNanos / 1000000}ms decrypting"
                )
            }
            encryptedVolume.closeFile(fileHandle)
        }

        @Synchronized
        override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int
        ): Int {
            requests++
            // Large requests are already efficient, and buffering them would only add a copy.
            if (byteCount >= WINDOW_SIZE) {
                return readNative(fileOffset, array, arrayOffset, byteCount)
            }
            val wanted = fileOffset + byteCount
            if (windowStart < 0 || fileOffset < windowStart || wanted > windowStart + windowLength) {
                windowLength = readNative(fileOffset, window, 0, WINDOW_SIZE)
                if (windowLength <= 0) {
                    windowStart = -1L
                    return windowLength
                }
                windowStart = fileOffset
            }
            val offsetInWindow = (fileOffset - windowStart).toInt()
            // A short count is allowed here: the caller asks again for the rest.
            val available = (windowLength - offsetInWindow).coerceAtMost(byteCount)
            if (available <= 0) {
                return -1
            }
            window.copyInto(array, arrayOffset, offsetInWindow, offsetInWindow + available)
            return available
        }

        override fun protectedSize(): Long {
            if (cachedSize < 0) {
                // The handle is read-only, so the size cannot change under us, but MediaDataSource
                // asks for it repeatedly and each getAttr() is a full path resolution in Go.
                cachedSize = (encryptedVolume.getAttr(path) ?: throw RuntimeException("getAttr() failed for $path")).size
            }
            return cachedSize
        }

        override fun protectedResize(size: Long) = unsupported()
        override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int
        ) = unsupported()
        override fun protectedFlush() = unsupported()
    }

    class EncryptedFileSource(
        private val encryptedVolume: EncryptedVolume,
        private val fileHandle: Long
    ) : Source {
        private var fileOffset = 0L

        override fun close() {
            encryptedVolume.closeFile(fileHandle)
        }

        override fun read(sink: Buffer, byteCount: Long): Long {
            val buffer = ByteArray(byteCount.toInt())
            val read = encryptedVolume.read(fileHandle, fileOffset, buffer, 0, byteCount)
            if (read <= 0) return -1L
            sink.write(buffer, 0, read)
            fileOffset += read
            return read.toLong()
        }

        override fun timeout() = Timeout.NONE
    }

    private fun tryOpenReadOnly(path: String): Long {
        val fileHandle = encryptedVolume.openFileReadMode(path)
        if (fileHandle == -1L) {
            throw RuntimeException("Failed to open {$path} in read-only mode")
        }
        return fileHandle
    }

    override fun canonicalize(path: Path): Path {
        TODO("Not yet implemented")
    }

    override fun metadataOrNull(path: Path): FileMetadata? {
        TODO("Not yet implemented")
    }

    override fun openReadOnly(file: Path): FileHandle {
        val path = file.toString()
        return EncryptedReadOnlyFileHandle(encryptedVolume, path, tryOpenReadOnly(path))
    }

    override fun source(file: Path): Source {
        return EncryptedFileSource(encryptedVolume, tryOpenReadOnly(file.toString()))
    }

    override fun list(dir: Path) = unsupported()
    override fun listOrNull(dir: Path) = unsupported()
    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean) = unsupported()
    override fun sink(file: Path, mustCreate: Boolean) = unsupported()
    override fun appendingSink(file: Path, mustExist: Boolean) = unsupported()
    override fun createDirectory(dir: Path, mustCreate: Boolean) = unsupported()
    override fun createSymlink(source: Path, target: Path) = unsupported()
    override fun delete(path: Path, mustExist: Boolean) = unsupported()
    override fun atomicMove(source: Path, target: Path) = unsupported()
}