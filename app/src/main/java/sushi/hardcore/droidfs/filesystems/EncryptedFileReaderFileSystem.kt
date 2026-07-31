package sushi.hardcore.droidfs.filesystems

import okio.Buffer
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Source
import okio.Timeout

fun unsupported(): Nothing = throw UnsupportedOperationException()

class EncryptedFileReaderFileSystem(private val encryptedVolume: EncryptedVolume) : FileSystem() {

    class EncryptedReadOnlyFileHandle(
        private val encryptedVolume: EncryptedVolume,
        private val path: String,
        private val fileHandle: Long
    ) : FileHandle(false) {
        override fun protectedClose() {
            encryptedVolume.closeFile(fileHandle)
        }

        override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int
        ): Int {
            return encryptedVolume.read(
                fileHandle, fileOffset, array, arrayOffset.toLong(),
                byteCount.toLong()
            )
        }

        override fun protectedSize(): Long {
            return (encryptedVolume.getAttr(path) ?: throw RuntimeException("getAttr() failed for $path")).size
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
        private val readBuffer = ByteArray(8192) // reusable, Okio typically reads 8KB

        override fun close() {
            encryptedVolume.closeFile(fileHandle)
        }

        override fun read(sink: Buffer, byteCount: Long): Long {
            val toRead = minOf(byteCount, readBuffer.size.toLong()).toInt()
            val n = encryptedVolume.read(fileHandle, fileOffset, readBuffer, 0, toRead.toLong())
            if (n > 0) {
                sink.write(readBuffer, 0, n)
                fileOffset += n
            }
            return n.toLong()
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