package sushi.hardcore.droidfs.filesystems

import android.content.Context
import android.net.Uri
import sushi.hardcore.droidfs.Constants
import sushi.hardcore.droidfs.VolumeData
import sushi.hardcore.droidfs.explorers.ExplorerElement
import sushi.hardcore.droidfs.util.ObjRef
import sushi.hardcore.droidfs.util.Observable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

abstract class EncryptedVolume: Observable<EncryptedVolume.Observer>() {

    interface Observer {
        fun onClose()
    }

    class InitResult(
        val errorCode: Int,
        val errorStringId: Int,
        val worthRetry: Boolean,
        val volume: EncryptedVolume?,
    ) {
        class Builder {
            var errorCode = 0
            var errorStringId = 0
            var worthRetry = false
            var volume: EncryptedVolume? = null

            fun build() = InitResult(errorCode, errorStringId, worthRetry, volume)
        }
    }

    companion object {
        const val PLAIN_VOLUME_TYPE: Byte = 0

        /**
         * Get the type of a volume.
         *
         * @return The volume type or -1 if the path is not recognized as a volume
         */
        fun getVolumeType(path: String): Byte {
            return if (File(path, PlainVolume.CONFIG_FILE_NAME).isFile) {
                PLAIN_VOLUME_TYPE
            } else {
                -1
            }
        }

        fun init(
            volume: VolumeData,
            filesDir: String,
            password: ByteArray?,
            givenHash: ByteArray?,
            returnedHash: ObjRef<ByteArray?>?
        ): InitResult {
            return when (volume.type) {
                PLAIN_VOLUME_TYPE -> {
                    PlainVolume.init(
                        volume.getFullPath(filesDir),
                        password,
                        givenHash,
                        returnedHash?.apply {
                            value = ByteArray(PlainVolume.KEY_LEN)
                        }?.value
                    )
                }
                else -> throw invalidVolumeType()
            }
        }

        private fun invalidVolumeType(): java.lang.RuntimeException {
            return RuntimeException("Invalid volume type")
        }
    }

    abstract fun openFileReadMode(path: String): Long
    abstract fun openFileWriteMode(path: String): Long
    abstract fun read(fileHandle: Long, fileOffset: Long, buffer: ByteArray, dstOffset: Long, length: Long): Int
    abstract fun write(fileHandle: Long, fileOffset: Long, buffer: ByteArray, srcOffset: Long, length: Long): Int
    abstract fun closeFile(fileHandle: Long): Boolean
    // For PlainVolume, truncate can work without the file being open
    abstract fun truncate(path: String, size: Long): Boolean
    abstract fun deleteFile(path: String): Boolean
    abstract fun readDir(path: String): MutableList<ExplorerElement>?
    abstract fun mkdir(path: String): Boolean
    abstract fun rmdir(path: String): Boolean
    abstract fun getAttr(path: String): Stat?
    abstract fun rename(srcPath: String, dstPath: String): Boolean
    protected abstract fun close()
    abstract fun isClosed(): Boolean

    open fun setMtime(path: String, mtime: Long): Boolean = false

    fun closeVolume() {
        observers.forEach { it.onClose() }
        close()
    }

    fun pathExists(path: String): Boolean {
        return getAttr(path) != null
    }

    fun exportFile(fileHandle: Long, os: OutputStream): Boolean {
        var offset: Long = 0
        val ioBuffer = ByteArray(Constants.IO_BUFF_SIZE)
        var length: Int
        while (read(fileHandle, offset, ioBuffer, 0, ioBuffer.size.toLong()).also { length = it } > 0) {
            os.write(ioBuffer, 0, length)
            offset += length.toLong()
        }
        os.close()
        return true
    }

    fun exportFile(src_path: String, os: OutputStream): Boolean {
        var success = false
        val srcfileHandle = openFileReadMode(src_path)
        if (srcfileHandle != -1L) {
            success = exportFile(srcfileHandle, os)
            closeFile(srcfileHandle)
        }
        return success
    }

    fun exportFile(src_path: String, dst_path: String): Boolean {
        val success = exportFile(src_path, FileOutputStream(dst_path))
        if (success) {
            val srcAttr = getAttr(src_path)
            if (srcAttr != null) {
                File(dst_path).setLastModified(srcAttr.mTime)
            }
        }
        return success
    }

    fun exportFile(context: Context, src_path: String, output_path: Uri): Boolean {
        val os = context.contentResolver.openOutputStream(output_path)
        if (os != null) {
            return exportFile(src_path, os)
        }
        return false
    }

    fun importFile(inputStream: InputStream, dst_path: String, mtime: Long = -1): Boolean {
        val dstfileHandle = openFileWriteMode(dst_path)
        if (dstfileHandle != -1L) {
            var success = true
            var offset: Long = 0
            val ioBuffer = ByteArray(Constants.IO_BUFF_SIZE)
            var length: Long
            while (inputStream.read(ioBuffer).also { length = it.toLong() } > 0) {
                val written = write(dstfileHandle, offset, ioBuffer, 0, length).toLong()
                if (written == length) {
                    offset += written
                } else {
                    success = false
                    break
                }
            }
            truncate(dst_path, offset)
            closeFile(dstfileHandle)
            inputStream.close()
            if (success && mtime > 0) {
                setMtime(dst_path, mtime)
            }
            return success
        }
        return false
    }

    fun importFile(context: Context, src_uri: Uri, dst_path: String): Boolean {
        val inputStream = context.contentResolver.openInputStream(src_uri)
        if (inputStream != null) {
            return importFile(inputStream, dst_path)
        }
        return false
    }

    fun loadWholeFile(fullPath: String, size: Long? = null, maxSize: Long? = null): Pair<ByteArray?, Int> {
        val fileSize = size ?: getAttr(fullPath)?.size ?: -1
        return if (fileSize >= 0) {
            maxSize?.let {
                if (fileSize > it) {
                    return Pair(null, 0)
                }
            }
            try {
                val fileBuff = ByteArray(fileSize.toInt())
                val fileHandle = openFileReadMode(fullPath)
                if (fileHandle == -1L) {
                    Pair(null, 3)
                } else {
                    var offset: Long = 0
                    while (offset < fileSize && read(fileHandle, offset, fileBuff, offset, fileSize-offset).also { offset += it } > 0) {}
                    closeFile(fileHandle)
                    if (offset == fileBuff.size.toLong()) {
                        Pair(fileBuff, 0)
                    } else {
                        Pair(null, 4)
                    }
                }
            } catch (e: OutOfMemoryError) {
                Pair(null, 2)
            }
        } else {
            Pair(null, 1)
        }
    }

    fun recursiveMapFiles(rootPath: String): MutableList<ExplorerElement>? {
        val result = mutableListOf<ExplorerElement>()
        val explorerElements = readDir(rootPath) ?: return null
        result.addAll(explorerElements)
        for (e in explorerElements) {
            if (e.isDirectory) {
                result.addAll(recursiveMapFiles(e.fullPath) ?: return null)
            }
        }
        return result
    }
}
