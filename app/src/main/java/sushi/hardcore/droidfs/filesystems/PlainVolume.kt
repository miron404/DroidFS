package sushi.hardcore.droidfs.filesystems

import android.util.Log
import sushi.hardcore.droidfs.R
import sushi.hardcore.droidfs.explorers.ExplorerElement
import sushi.hardcore.droidfs.util.ObjRef
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject
import java.util.Base64

class PlainVolume(
    private val rootPath: String,
    private val volumeFileHandles: ConcurrentHashMap<Long, RandomAccessFile>,
    private val handleCounter: AtomicLong,
) : EncryptedVolume() {

    companion object {
        const val CONFIG_FILE_NAME = ".plain.conf"
        const val PLAIN_VOLUME_TYPE: Byte = 0

        private const val CONFIG_VERSION = 1
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 210_000
        private const val SALT_SIZE = 16
        private const val HASH_SIZE = 32
        const val KEY_LEN = HASH_SIZE

        private const val TAG = "PlainVolume"

        private fun computeHash(password: ByteArray, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(
                String(password, Charsets.UTF_8).toCharArray(),
                salt,
                PBKDF2_ITERATIONS,
                HASH_SIZE * 8
            )
            val skf = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            return skf.generateSecret(spec).encoded
        }

        fun createAndOpenVolume(
            rootPath: String,
            password: ByteArray,
            volume: ObjRef<EncryptedVolume?>
        ): Boolean {
            return try {
                val dir = File(rootPath)
                if (!dir.exists()) dir.mkdirs()
                val salt = ByteArray(SALT_SIZE)
                SecureRandom().nextBytes(salt)
                val hash = computeHash(password, salt)

                val configJson = JSONObject().apply {
                    put("version", CONFIG_VERSION)
                    put("kdf", KDF_ALGORITHM)
                    put("kdf_params", JSONObject().apply {
                        put("iterations", PBKDF2_ITERATIONS)
                        put("hash_size", HASH_SIZE)
                    })
                    put("salt", Base64.getEncoder().encodeToString(salt))
                    put("hash", Base64.getEncoder().encodeToString(hash))
                }

                File(rootPath, CONFIG_FILE_NAME).writeText(configJson.toString(4))
                volume.value = PlainVolume(rootPath, ConcurrentHashMap(), AtomicLong(0))
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create volume: ${e.message}")
                false
            }
        }

        fun init(
            rootPath: String,
            password: ByteArray?,
            givenHash: ByteArray?,
            returnedHash: ByteArray?
        ): InitResult {
            val result = InitResult.Builder()
            try {
                val configFile = File(rootPath, CONFIG_FILE_NAME)
                if (!configFile.isFile) {
                    result.errorCode = -1
                    result.errorStringId = R.string.config_load_error
                    return result.build()
                }

                val configJson = JSONObject(configFile.readText())
                val storedSalt = Base64.getDecoder().decode(configJson.getString("salt"))
                val storedHash = Base64.getDecoder().decode(configJson.getString("hash"))

                val hashToCompare: ByteArray
                if (givenHash != null) {
                    hashToCompare = givenHash
                } else if (password != null) {
                    hashToCompare = computeHash(password, storedSalt)
                    Arrays.fill(password, 0)
                } else {
                    result.errorCode = -2
                    result.errorStringId = R.string.wrong_password
                    result.worthRetry = true
                    return result.build()
                }

                if (!MessageDigest.isEqual(hashToCompare, storedHash)) {
                    result.errorCode = -2
                    result.errorStringId = R.string.wrong_password
                    result.worthRetry = true
                    return result.build()
                }

                returnedHash?.let { dest ->
                    System.arraycopy(hashToCompare.copyOf(), 0, dest, 0, minOf(KEY_LEN, dest.size))
                }

                result.volume = PlainVolume(rootPath, ConcurrentHashMap(), AtomicLong(0))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init volume: ${e.message}")
                result.errorCode = -1
                result.errorStringId = R.string.config_load_error
            }
            return result.build()
        }

        fun changePassword(
            rootPath: String,
            currentPassword: ByteArray?,
            givenHash: ByteArray?,
            newPassword: ByteArray,
            returnedHash: ByteArray?
        ): Boolean {
            return try {
                val configFile = File(rootPath, CONFIG_FILE_NAME)
                if (!configFile.isFile) return false

                val configJson = JSONObject(configFile.readText())
                val storedSalt = Base64.getDecoder().decode(configJson.getString("salt"))
                val storedHash = Base64.getDecoder().decode(configJson.getString("hash"))

                val hashToCheck: ByteArray
                if (givenHash != null) {
                    hashToCheck = givenHash
                } else if (currentPassword != null) {
                    hashToCheck = computeHash(currentPassword, storedSalt)
                    Arrays.fill(currentPassword, 0)
                } else {
                    return false
                }

                if (!MessageDigest.isEqual(hashToCheck, storedHash)) return false

                val newSalt = ByteArray(SALT_SIZE)
                SecureRandom().nextBytes(newSalt)
                val newHash = computeHash(newPassword, newSalt)

                configJson.put("salt", Base64.getEncoder().encodeToString(newSalt))
                configJson.put("hash", Base64.getEncoder().encodeToString(newHash))
                configFile.writeText(configJson.toString(4))

                returnedHash?.let { dest ->
                    System.arraycopy(newHash, 0, dest, 0, minOf(KEY_LEN, dest.size))
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change password: ${e.message}")
                false
            }
        }
    }

    // ── Path helpers ──────────────────────────────────────────────────

    private fun getRealPath(volumePath: String): String {
        return if (volumePath == "/") rootPath
        else if (volumePath.startsWith("/")) File(rootPath, volumePath.substring(1)).path
        else File(rootPath, volumePath).path
    }

    private fun newHandle(): Long = handleCounter.incrementAndGet()

    // ── EncryptedVolume abstract methods ──────────────────────────────

    override fun openFileReadMode(path: String): Long {
        return try {
            val raf = RandomAccessFile(getRealPath(path), "r")
            val handle = newHandle()
            volumeFileHandles[handle] = raf
            handle
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open file for read: $path: ${e.message}")
            -1L
        }
    }

    override fun openFileWriteMode(path: String): Long {
        return try {
            val realPath = getRealPath(path)
            if (File(realPath).name == CONFIG_FILE_NAME) return -1L
            File(realPath).parentFile?.mkdirs()
            val raf = RandomAccessFile(realPath, "rw")
            val handle = newHandle()
            volumeFileHandles[handle] = raf
            handle
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open file for write: $path: ${e.message}")
            -1L
        }
    }

    override fun read(fileHandle: Long, fileOffset: Long, buffer: ByteArray, dstOffset: Long, length: Long): Int {
        val raf = volumeFileHandles[fileHandle] ?: return -1
        return try {
            val toRead = minOf(length, (buffer.size - dstOffset).toLong()).toInt()
            if (toRead <= 0) return 0
            val bb = ByteBuffer.wrap(buffer, dstOffset.toInt(), toRead)
            val n = raf.channel.read(bb, fileOffset)
            if (n < 0) -1 else n
        } catch (e: Exception) {
            Log.e(TAG, "Read failed: ${e.message}")
            -1
        }
    }

    override fun write(fileHandle: Long, fileOffset: Long, buffer: ByteArray, srcOffset: Long, length: Long): Int {
        val raf = volumeFileHandles[fileHandle] ?: return -1
        return try {
            val toWrite = minOf(length, (buffer.size - srcOffset).toLong()).toInt()
            if (toWrite <= 0) return 0
            val bb = ByteBuffer.wrap(buffer, srcOffset.toInt(), toWrite)
            raf.channel.write(bb, fileOffset)
            toWrite
        } catch (e: Exception) {
            Log.e(TAG, "Write failed: ${e.message}")
            -1
        }
    }

    override fun closeFile(fileHandle: Long): Boolean {
        val raf = volumeFileHandles.remove(fileHandle) ?: return false
        return try {
            raf.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun truncate(path: String, size: Long): Boolean {
        return try {
            val realPath = getRealPath(path)
            if (File(realPath).name == CONFIG_FILE_NAME) return false
            RandomAccessFile(realPath, "rw").use { it.setLength(size) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Truncate failed: ${e.message}")
            false
        }
    }

    override fun deleteFile(path: String): Boolean {
        return try {
            val realPath = getRealPath(path)
            if (File(realPath).name == CONFIG_FILE_NAME) return false
            File(realPath).delete()
        } catch (e: Exception) { false }
    }

    override fun readDir(path: String): MutableList<ExplorerElement>? {
        return try {
            val realPath = getRealPath(path)
            val dir = File(realPath)
            if (!dir.isDirectory) return null

            val files = dir.listFiles() ?: return null
            val result = mutableListOf<ExplorerElement>()

            for (file in files) {
                if (file.name == CONFIG_FILE_NAME) continue
                val stat = fileToStat(file)
                if (stat != null) {
                    result.add(ExplorerElement.new(
                        file.name, stat.type, stat.size, stat.mTime / 1000, path
                    ))
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "readDir failed: ${e.message}")
            null
        }
    }

    private fun fileToStat(file: File): Stat? {
        return try {
            if (!file.exists()) return null
            Stat(if (file.isDirectory) Stat.S_IFDIR else Stat.S_IFREG, file.length(), file.lastModified())
        } catch (e: Exception) { null }
    }

    override fun mkdir(path: String): Boolean {
        return try { File(getRealPath(path)).mkdirs() } catch (e: Exception) { false }
    }

    override fun rmdir(path: String): Boolean {
        return try {
            val dir = File(getRealPath(path))
            dir.isDirectory && dir.list()?.isEmpty() == true && dir.delete()
        } catch (e: Exception) { false }
    }

    override fun getAttr(path: String): Stat? {
        return try { fileToStat(File(getRealPath(path))) } catch (e: Exception) { null }
    }

    override fun rename(srcPath: String, dstPath: String): Boolean {
        return try {
            val realSrc = getRealPath(srcPath)
            val realDst = getRealPath(dstPath)
            if (File(realSrc).name == CONFIG_FILE_NAME || File(realDst).name == CONFIG_FILE_NAME) return false
            File(realSrc).renameTo(File(realDst))
        } catch (e: Exception) { false }
    }

    override fun setMtime(path: String, mtime: Long): Boolean {
        return try { File(getRealPath(path)).setLastModified(mtime) } catch (e: Exception) { false }
    }

    override fun close() {
        volumeFileHandles.values.forEach { try { it.close() } catch (_: Exception) {} }
        volumeFileHandles.clear()
    }

    override fun isClosed(): Boolean = volumeFileHandles.isEmpty()
}
