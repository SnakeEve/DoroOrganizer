package com.doro.organizer.data.maintenance

import android.content.Context
import android.net.Uri
import android.system.Os
import androidx.core.content.FileProvider
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.DbFiles
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Share-sheet copies of verified backups in `cacheDir/share/` (db-lifecycle.md § For sharing, § Share-file lifetime,
 * DEC-83). A file lives at most one hour: a one-shot timer of the process, the ON_START sweep (wall-clock age), the
 * next backup, the next committed key change and the next cold start delete it.
 */
internal class ShareFiles(
    private val context: Context,
    private val files: DbFiles,
    private val appTime: AppTime,
    private val dispatchers: AppDispatchers,
    private val applicationScope: CoroutineScope,
) {
    /**
     * PUBLISH_SHARE: moves [backup] (same filesystem, no second copy) to a random name, so a grant held by an earlier
     * receiver never reaches a newer file, and serves it under [displayName].
     */
    fun publish(backup: File, displayName: String): Uri {
        // cacheDir can be emptied while the process lives (Clear cache, a low-storage purge), the directory included.
        check(files.shareDir.isDirectory || files.shareDir.mkdirs()) { "share directory" }
        val target = File(files.shareDir, "${UUID.randomUUID()}.db")
        Os.rename(backup.path, target.path)
        applicationScope.launch(dispatchers.io) {
            delay(LIFETIME_MS) // monotonic: a wall clock moved backwards cannot extend it
            target.delete()
        }
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target, displayName)
        } catch (t: Throwable) {
            target.delete() // never served, so no receiver can still read it
            throw t
        }
    }

    /** Deletes every share file whose wall-clock age is at least one hour; needs no lock (db-lifecycle.md). */
    fun sweep() {
        val now = appTime.nowMillis()
        files.shareDir.listFiles().orEmpty().filter { now - it.lastModified() >= LIFETIME_MS }.forEach { it.delete() }
    }

    /** Empties `cacheDir/share/`: at every backup, after a committed key change and at a cold start. */
    fun clear(): Boolean = files.clearDir(files.shareDir)

    companion object {
        const val LIFETIME_MS = 60L * 60 * 1000
    }
}
