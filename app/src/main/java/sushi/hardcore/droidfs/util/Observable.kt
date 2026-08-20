package sushi.hardcore.droidfs.util

import android.app.Activity
import sushi.hardcore.droidfs.filesystems.EncryptedVolume
import java.util.concurrent.CopyOnWriteArrayList

abstract class Observable<T> {
    /**
     * Copy-on-write because the two sides run on different threads: observers are registered from
     * activity lifecycle callbacks, while volume state changes are notified from coroutines and
     * from the screen-off receiver, and notification iterates this list. A plain ArrayList throws
     * ConcurrentModificationException when a registration lands during a notification; a
     * copy-on-write list iterates a snapshot instead.
     */
    protected val observers = CopyOnWriteArrayList<T>()

    fun observe(observer: T) {
        observers.add(observer)
    }

    /**
     * An observer that is never removed keeps whatever it references alive for as long as the
     * observable lives, which for VolumeManager means the whole process.
     */
    fun removeObserver(observer: T) {
        observers.remove(observer)
    }
}

fun Activity.finishOnClose(encryptedVolume: EncryptedVolume) {
    encryptedVolume.observe(object : EncryptedVolume.Observer {
        override fun onClose() {
            finish()
            // no need to remove observer as the EncryptedVolume will be destroyed
        }
    })
}