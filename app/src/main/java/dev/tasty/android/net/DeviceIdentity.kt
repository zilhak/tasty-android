package dev.tasty.android.net

import android.content.Context
import androidx.annotation.WorkerThread
import dev.tasty.android.core.DeviceKeyException
import dev.tasty.android.core.loadOrCreateDeviceKey
import java.io.File

/**
 * The device key of the Tasty network, kept in [keyDir]. The key is created the first time it
 * is asked for; every instance that uses the same folder gets the same device ID.
 *
 * The calls read and write files, so they must not run on the UI thread.
 */
class DeviceIdentity(
    private val keyDir: File,
) {
    /**
     * The device ID (the public key) as 64 lowercase hex digits.
     *
     * @throws DeviceKeyException when the key cannot be read or created.
     */
    @WorkerThread
    fun deviceId(): String = loadOrCreateDeviceKey(keyDir.absolutePath).deviceId

    companion object {
        /** The folder under [Context.getFilesDir] that holds the app's key. */
        const val KEY_DIR_NAME = "tasty-net"

        /** The identity of this app install, kept in its private files folder. */
        fun forApp(context: Context): DeviceIdentity = DeviceIdentity(File(context.filesDir, KEY_DIR_NAME))
    }
}
