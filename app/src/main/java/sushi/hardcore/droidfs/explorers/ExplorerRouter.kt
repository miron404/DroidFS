package sushi.hardcore.droidfs.explorers

import sushi.hardcore.droidfs.util.Logger
import android.content.Context
import android.content.Intent
import sushi.hardcore.droidfs.util.IntentUtils

class ExplorerRouter(private val context: Context, private val intent: Intent, callingPackage: String?) {
    /**
     * Pick mode hands the caller back the plaintext paths of the files selected inside an
     * unlocked volume, so it must never be reachable from outside the app. The "pick" action
     * is not declared in any intent filter, but that only stops external callers on Android 16
     * and later (see android:intentMatchingFlags in the manifest): on older releases an explicit
     * intent bypasses filters entirely. Requiring the caller to be ourselves closes that gap on
     * every supported release.
     */
    var pickMode = intent.action == "pick" && run {
        val isSelf = callingPackage == context.packageName
        if (!isSelf) {
            Logger.w("ExplorerRouter", "Rejecting pick request from $callingPackage")
        }
        isSelf
    }
    var dropMode = (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) && intent.extras != null

    fun getExplorerIntent(volumeId: Int, volumeShortName: String): Intent {
        var explorerIntent: Intent? = null
        if (dropMode) { //import via android share menu
            explorerIntent = Intent(context, ExplorerActivityDrop::class.java)
            IntentUtils.forwardIntent(intent, explorerIntent)
        } else if (pickMode) {
            explorerIntent = Intent(context, ExplorerActivityPick::class.java)
            explorerIntent.flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
        }
        if (explorerIntent == null) {
            explorerIntent = Intent(context, ExplorerActivity::class.java) //default opening
        }
        explorerIntent.putExtra("volumeId", volumeId)
        explorerIntent.putExtra("volumeName", volumeShortName)
        return explorerIntent
    }
}