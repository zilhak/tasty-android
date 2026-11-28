package dev.tasty.android.net

import android.content.Context
import androidx.annotation.WorkerThread
import dev.tasty.android.core.PeerKeyException
import dev.tasty.android.core.PeerPin
import dev.tasty.android.core.peerKeyGet
import dev.tasty.android.core.peerKeyPinIfAbsent
import dev.tasty.android.core.peerKeyReregister
import java.io.File

/**
 * The trusted device IDs of remote Tasty peers, one record per peer in [dir]. A peer is named by
 * the ID the app gives its profile (1 to 64 of `A-Z`, `a-z`, `0-9`, `_` and `-`), not by an alias
 * the user can edit. Device IDs are 64 lowercase hex digits.
 *
 * The calls read and write files, so they must not run on the UI thread.
 */
class PeerKeys(
    private val dir: File,
) {
    /**
     * The recorded device ID of [peer], or null when it has no record.
     *
     * @throws PeerKeyException when the record cannot be read.
     */
    @WorkerThread
    fun get(peer: String): String? = peerKeyGet(dir.absolutePath, peer)

    /**
     * Records [deviceId] for [peer] when it has no record; otherwise leaves the record as it is.
     * Of several first calls for one peer only one gets [PeerPin.inserted]; the others get the
     * device ID that call recorded.
     *
     * @throws PeerKeyException when the record cannot be read or written.
     */
    @WorkerThread
    fun pinIfAbsent(
        peer: String,
        deviceId: String,
    ): PeerPin = peerKeyPinIfAbsent(dir.absolutePath, peer, deviceId)

    /**
     * Replaces the record of [peer] with [deviceId]. Only the user's explicit re-registration may
     * call this, never a connection.
     *
     * @throws PeerKeyException when the record cannot be written.
     */
    @WorkerThread
    fun reregister(
        peer: String,
        deviceId: String,
    ) = peerKeyReregister(dir.absolutePath, peer, deviceId)

    companion object {
        /** The folder under [Context.getNoBackupFilesDir] that holds the records. */
        const val DIR_NAME = "net/peers"

        /**
         * The records of this app install, next to the device key under the no-backup files
         * folder, so they are never part of a cloud backup or a device transfer.
         */
        fun forApp(context: Context): PeerKeys = PeerKeys(File(context.noBackupFilesDir, DIR_NAME))
    }
}
