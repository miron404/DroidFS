package sushi.hardcore.droidfs.filesystems

import android.util.Log
import sushi.hardcore.droidfs.R
import sushi.hardcore.droidfs.explorers.ExplorerElement
import sushi.hardcore.droidfs.util.ObjRef
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
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
        const val KEY_LEN = HASH_SIZE // for returnedHash sizing

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
                if (!dir.exists()) {
                    dir.mkdirs()
                }
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

                val configFile = File(rootPath, CONFIG_FILE_NAME)
                configFile.writeText(configJson.toString(4))

                volume.value = PlainVolume(
                    rootPath,
                    ConcurrentHashMap(),
                    AtomicLong(0)
                )
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
                    // Biometric unlock: givenHash is already the validated hash from Keystore
                    hashToCompare = givenHash
                } else if (password != null) {
                    hashToCompare = computeHash(password, storedSalt)
                    // Wipe password after use
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

                // Write hash to returnedHash if provided
                returnedHash?.let { dest ->
                    System.arraycopy(computeHashForReturn(storedSalt, hashToCompare), 0, dest, 0, minOf(KEY_LEN, dest.size))
                }

                result.volume = PlainVolume(
                    rootPath,
                    ConcurrentHashMap(),
                    AtomicLong(0)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init volume: ${e.message}")
                result.errorCode = -1
                result.errorStringId = R.string.config_load_error
            }
            return result.build()
        }

        private fun computeHashForReturn(salt: ByteArray, computedHash: ByteArray): ByteArray {
            // Return a copy of the computed hash for biometric storage
            return computedHash.copyOf()
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

                // Verify current password or givenHash
                val hashToCheck: ByteArray
                if (givenHash != null) {
                    hashToCheck = givenHash
                } else if (currentPassword != null) {
                    hashToCheck = computeHash(currentPassword, storedSalt)
                    Arrays.fill(currentPassword, 0)
                } else {
                    return false
                }

                if (!MessageDigest.isEqual(hashToCheck, storedHash)) {
                    return false
                }

                // Generate new salt and hash for new password
                val newSalt = ByteArray(SALT_SIZE)
                SecureRandom().nextBytes(newSalt)
                val newHash = computeHash(newPassword, newSalt)

                // Update config
                configJson.put("salt", Base64.getEncoder().encodeToString(newSalt))
                configJson.put("hash", Base64.getEncoder().encodeToString(newHash))
                configFile.writeText(configJson.toString(4))

                // Write new hash to returnedHash if provided
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

    private fun getRealPath(volumePath: String): String {
        return if (volumePath == "/") {
            rootPath
        } else if (volumePath.startsWith("/")) {
            File(rootPath, volumePath.substring(1)).path
        } else {
            File(rootPath, volumePath).path
        }
    }

    private fun getVolumePath(realPath: String): String {
        return if (realPath == rootPath) {
            "/"
        } else if (realPath.startsWith(rootPath + "/")) {
            realPath.substring(rootPath.length)
        } else {
            realPath
        }
    }

    private fun newHandle(): Long {
        return handleCounter.incrementAndGet()
    }

    override fun openFileReadMode(path: String): Long {
        return try {
            val realPath = getRealPath(path)
            val file = RandomAccessFile(realPath, "r")
            val handle = newHandle()
            volumeFileHandles[handle] = file
            handle
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open file for read: $path: ${e.message}")
            -1L
        }
    }

    override fun openFileWriteMode(path: String): Long {
        return try {
            val realPath = getRealPath(path)
            // Prevent writing to the volume config file
            if (File(realPath).name == CONFIG_FILE_NAME) return -1L
            // Ensure parent directory exists
            File(realPath).parentFile?.mkdirs()
            val file = RandomAccessFile(realPath, "rw")
            val handle = newHandle()
            volumeFileHandles[handle] = file
            handle
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open file for write: $path: ${e.message}")
            -1L
        }
    }

    override fun read(fileHandle: Long, fileOffset: Long, buffer: ByteArray, dstOffset: Long, length: Long): Int {
        val raf = volumeFileHandles[fileHandle] ?: return -1
        return try {
            raf.seek(fileOffset)
            val toRead = minOf(length, (buffer.size - dstOffset).toLong()).toInt()
            if (toRead <= 0) return 0
            raf.read(buffer, dstOffset.toInt(), toRead)
        } catch (e: Exception) {
            Log.e(TAG, "Read failed: ${e.message}")
            -1
        }
    }

    override fun write(fileHandle: Long, fileOffset: Long, buffer: ByteArray, srcOffset: Long, length: Long): Int {
        val raf = volumeFileHandles[fileHandle] ?: return -1
        return try {
            raf.seek(fileOffset)
            val toWrite = minOf(length, (buffer.size - srcOffset).toLong()).toInt()
            if (toWrite <= 0) return 0
            raf.write(buffer, srcOffset.toInt(), toWrite)
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
            // Prevent truncation of the volume config file
            if (File(realPath).name == CONFIG_FILE_NAME) return false
            val raf = RandomAccessFile(realPath, "rw")
            raf.setLength(size)
            raf.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Truncate failed: ${e.message}")
            false
        }
    }

    override fun deleteFile(path: String): Boolean {
        return try {
            val realPath = getRealPath(path)
            // Prevent deletion of the volume config file
            if (File(realPath).name == CONFIG_FILE_NAME) return false
            File(realPath).delete()
        } catch (e: Exception) {
            false
        }
    }

    override fun readDir(path: String): MutableList<ExplorerElement>? {
        return try {
            val realPath = getRealPath(path)
            val dir = File(realPath)
            if (!dir.isDirectory) return null

            val files = dir.listFiles() ?: return null
            val result = mutableListOf<ExplorerElement>()

            for (file in files) {
                // Hide the volume config file from the user
                if (file.name == CONFIG_FILE_NAME) continue
                val stat = fileToStat(file)
                if (stat != null) {
                    result.add(ExplorerElement.new(
                        file.name,
                        stat.type,
                        stat.size,
                        stat.mTime / 1000, // ExplorerElement.new expects seconds
                        path
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
            val mode = if (file.isDirectory) Stat.S_IFDIR else Stat.S_IFREG
            Stat(mode, file.length(), file.lastModified())
        } catch (e: Exception) {
            null
        }
    }

    override fun mkdir(path: String): Boolean {
        return try {
            val realPath = getRealPath(path)
            File(realPath).mkdirs()
        } catch (e: Exception) {
            false
        }
    }

    override fun rmdir(path: String): Boolean {
        return try {
            val realPath = getRealPath(path)
            val dir = File(realPath)
            if (dir.isDirectory && (dir.list()?.isEmpty() == true)) {
                dir.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun getAttr(path: String): Stat? {
        return try {
            val realPath = getRealPath(path)
            fileToStat(File(realPath))
        } catch (e: Exception) {
            null
        }
    }

    override fun rename(srcPath: String, dstPath: String): Boolean {
        return try {
            val realSrc = getRealPath(srcPath)
            val realDst = getRealPath(dstPath)
            // Prevent renaming of the volume config file
            if (File(realSrc).name == CONFIG_FILE_NAME || File(realDst).name == CONFIG_FILE_NAME) return false
            File(realSrc).renameTo(File(realDst))
        } catch (e: Exception) {
            false
        }
    }

    override fun setMtime(path: String, mtime: Long): Boolean {
        return try {
            val realPath = getRealPath(path)
            File(realPath).setLastModified(mtime)
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        // Close all open file handles
        volumeFileHandles.values.forEach { raf ->
            try {
                raf.close()
            } catch (_: Exception) {}
        }
        volumeFileHandles.clear()
    }

    override fun isClosed(): Boolean {
        // PlainVolume doesn't have a persistent session; files are closed individually.
        // The volume is "closed" when all file handles are closed.
        return volumeFileHandles.isEmpty()
    }
}
