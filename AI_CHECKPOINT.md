# DroidFS PlainVolume Fork — AI Agent Checkpoint

## Overview

This is a fork of [DroidFS](https://github.com/miron404/DroidFS) (originally by Hardcore Sushi). The original app provided an encrypted volume manager on Android using gocryptfs (Go) and CryFS (C++) via JNI. **This fork removes all native encryption backends** and replaces them with a single "Plain" volume type: files are stored **without encryption** on disk, but access through the app is **password-gated** (PBKDF2 + UI gate).

## High-Level Architecture

```
EncryptedVolume (abstract class)
├── PlainVolume         ← NEW: plain java.io, password-gated
├── GocryptfsVolume     ← DELETED: native gocryptfs via JNI
└── CryfsVolume         ← DELETED: native cryfs via JNI
```

### Volume lifecycle

1. **Create**: User picks a directory → `CreateVolumeFragment` → `PlainVolume.createAndOpenVolume()` → writes `.plain.conf` (JSON with salt+hash) → returns `EncryptedVolume` instance.
2. **Open**: `VolumeOpener` → `EncryptedVolume.init()` → `PlainVolume.init()` → reads `.plain.conf` → verifies password hash → returns `PlainVolume` instance.
3. **Change password**: `ChangePasswordActivity` → `PlainVolume.changePassword()` → re-hashes, rewrites `.plain.conf`.
4. **Close**: `PlainVolume.close()` → closes all open `RandomAccessFile` handles.

### File operations

All file I/O goes through `EncryptedVolume` abstract methods, implemented in `PlainVolume` via `java.io.RandomAccessFile`:
- `openFileReadMode(path)` → `new RandomAccessFile(realPath, "r")` → returns handle (Long)
- `openFileWriteMode(path)` → `new RandomAccessFile(realPath, "rw")` → returns handle
- `read(handle, offset, buffer, dstOffset, length)` → `raf.seek(offset); raf.read(...)`
- `write(handle, offset, buffer, srcOffset, length)` → `raf.seek(offset); raf.write(...)`
- `truncate(path, size)` → opens a temporary RAF, calls `setLength(size)`, closes
- `deleteFile/mkdir/rmdir/rename/getAttr` → mapped to `java.io.File`
- `readDir(path)` → `File.listFiles()` → mapped to `ExplorerElement` list via `ExplorerElement.new()`

Handles are managed via `ConcurrentHashMap<Long, RandomAccessFile>` with `AtomicLong` counter — thread-safe, mimicking the old sessionID/handleID pattern from GocryptfsVolume.

### Path mapping

The volume presents a virtual filesystem root `/` that maps to `rootPath` on real storage:
- `getRealPath("/foo/bar")` → `rootPath + "/foo/bar"`
- `getVolumePath(realPath)` → strips `rootPath` prefix, returns `/foo/bar`
- Root is special-cased: `getRealPath("/")` → `rootPath`

## Password Protection (NOT encryption!)

### Config file: `.plain.conf`

JSON format stored at the volume root:
```json
{
  "version": 1,
  "kdf": "PBKDF2WithHmacSHA256",
  "kdf_params": {
    "iterations": 210000,
    "hash_size": 32
  },
  "salt": "<base64>",
  "hash": "<base64>"
}
```

### KDF

PBKDF2WithHmacSHA256, 210,000 iterations (OWASP 2023 recommendation), 32-byte output. Salt: 16 bytes from SecureRandom.

### Hash comparison

Uses `MessageDigest.isEqual()` for constant-time comparison — NEVER `==` or `.equals()` on ByteArray.

### Biometric unlock

The existing fingerprint/Keystore flow is preserved:
- `givenHash` parameter: if non-null, it's the already-decrypted hash from Android Keystore — compare directly, skip PBKDF2.
- `returnedHash` parameter: if non-null, write the computed hash into it for encryption+storage in Keystore.
- Key size for returnedHash: `PlainVolume.KEY_LEN` = 32 bytes.

### Config file protection

The `.plain.conf` file is:
- Hidden from `readDir()` listings
- Protected from `openFileWriteMode()`, `truncate()`, `deleteFile()`, `rename()` — these return false/-1 if targeting the config file.

## Key Files Changed

### Created
| File | Purpose |
|------|---------|
| `app/src/main/java/.../filesystems/PlainVolume.kt` | New volume implementation (~420 lines) |

### Deleted
| File | Purpose |
|------|---------|
| `.../filesystems/GocryptfsVolume.kt` | gocryptfs JNI bridge |
| `.../filesystems/CryfsVolume.kt` | cryfs JNI bridge |
| `app/src/main/native/gocryptfs_jni.c` | gocryptfs JNI native |
| `app/src/main/native/libcryfs.c` | cryfs JNI native |
| `app/libgocryptfs/` | gocryptfs Go sources (submodule) |
| `app/libcryfs/` | cryfs C++ sources (submodule) |

### Modified (critical path)
| File | Changes |
|------|---------|
| `EncryptedVolume.kt` | Removed GOCRYPTFS_VOLUME_TYPE/CRYFS_VOLUME_TYPE; added PLAIN_VOLUME_TYPE=0; `getVolumeType()` checks for `.plain.conf`; `init()` only calls PlainVolume; added `setMtime()` open fun (default no-op); `importFile()` accepts optional mtime |
| `VolumeData.kt` | `canRead()` checks for `PlainVolume.CONFIG_FILE_NAME` |
| `VolumeOpener.kt` | Removed gocryptfs/cryfs disabled checks; single path for Plain |
| `ChangePasswordActivity.kt` | Calls `PlainVolume.changePassword()` |
| `CreateVolumeFragment.kt` | Removed filesystem selection UI (RadioGroup, cipher Spinner); shows plain warning text; only password fields |
| `VolumeAdapter.kt` | Always shows "Plain volume (no encryption)" label |
| `Constants.kt` | Removed `CRYFS_LOCAL_STATE_DIR` |
| `VolumeDatabase.kt` | Legacy migration code updated to PLAIN_VOLUME_TYPE; removed cryfsLocalState dir check |
| `FileOperationService.kt` | `copyFile` preserves mtime; `importFilesFromUris` queries+passes mtime; `exportFileInto` sets COLUMN_LAST_MODIFIED |

### Modified (build/config)
| File | Changes |
|------|---------|
| `app/build.gradle` | Removed gocryptfs/cryfs disable flags (`disableGocryptfs`/`disableCryFS`), removed ABI splits (replaced with `ndk.abiFilters "arm64-v8a"`), removed cmake arguments, bumped versionCode to 38/2.3.0 |
| `app/CMakeLists.txt` | Removed gocryptfs_jni and cryfs_jni targets; kept memfile, ffmpeg (avformat/avcodec/avutil), mux for video recording |
| `.gitmodules` | Removed libgocryptfs and libcryfs submodules; kept libpdfviewer and ffmpeg |
| `proguard-rules.pro` | Removed JNI native method keep rules |
| `BUILD.md` | Simplified — no native crypto build steps |
| `.github/workflows/main.yml` | Removed libgocryptfs build step; kept submodule init (for ffmpeg+libpdfviewer); removed `-Pabi=` flag |
| `fragment_create_volume.xml` | Added `text_plain_warning` and `text_volume_type_label` IDs; added tools namespace |
| `arrays.xml` | Removed `gocryptfs_encryption_ciphers` and `cryfs_encryption_ciphers` arrays |
| `values/strings.xml` + all 8 locales | Replaced gocryptfs/cryfs strings with `plain_volume`, `plain_volume_details`, `volume_type_not_supported` |

## Gotchas & Important Notes

1. **`.plain.conf` visibility**: The file is filtered in `PlainVolume.readDir()` AND protected in write/delete/rename/truncate. If someone adds a new operation method to the abstract class, they MUST also protect the config file there.

2. **Parent folder ".." entry**: `readDir()` must NOT add ".." — the explorer (`BaseExplorerActivity.kt:376`) adds it. Adding ".." in readDir causes duplicate entries in the UI AND breaks `recursiveExportDirectory()` which iterates readDir results.

3. **NDK/CMake still needed**: FFmpeg (for video recording via `FFmpegMuxer.kt`) and memfile (for `MemFile.kt` export) are still native. The `ndk.abiFilters` is set to `"arm64-v8a"` — if building for other ABIs, both the filter AND the FFmpeg build script need updating.

4. **Submodules still required**: `libpdfviewer` and `app/ffmpeg/ffmpeg` are git submodules. CI runs `git submodule update --init --recursive`.

5. **FFmpeg build.sh**: Expects `app/ffmpeg/ffmpeg/` submodule to be checked out. The build script compiles FFmpeg for a single ABI (arm64-v8a in CI).

6. **Version**: versionCode=38, versionName="2.3.0". Bump both for new releases.

7. **EncryptedVolume.setMtime()**: Default implementation returns false (no-op). PlainVolume overrides it to set `File.lastModified`. If a new volume type is added, override this to preserve timestamps.

8. **Biometric hash flow**: `FingerprintProtector` encrypts hash via Android Keystore. `givenHash` bypasses PBKDF2. `returnedHash` receives hash for encryption. The hash size expected is `PlainVolume.KEY_LEN` (32 bytes).

9. **Password wiping**: Passwords (ByteArray) are zeroed after use via `Arrays.fill(password, 0)` in `PlainVolume.init()` and `CreateVolumeFragment`/`ChangePasswordActivity`.

10. **Truncate behavior**: Unlike the old comment ("Due to gocryptfs internals, truncate requires the file to be open"), PlainVolume's truncate opens a fresh RAF, calls setLength, and closes — no prior open required.

## Testing Checklist

- [x] Create volume → `.plain.conf` appears, not visible in file listing
- [x] Open volume with correct password
- [x] Open volume with wrong password → error
- [x] Change password
- [x] Biometric unlock (if device supports)
- [x] Create/read/write/rename/delete files
- [x] Create/delete folders
- [x] Import files (preserves mtime)
- [x] Export files (preserves mtime)
- [x] Export folders
- [x] Copy/move within volume
- [x] `.plain.conf` cannot be deleted/renamed/overwritten from app
- [x] No duplicate ".." entries
- [x] CI builds successfully (arm64-v8a)
