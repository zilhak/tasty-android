package dev.tasty.android.net

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs the device key through Kotlin, the UniFFI binding and the Rust library on a device. */
@RunWith(AndroidJUnit4::class)
class DeviceIdentityTest {
    private lateinit var parent: File
    private lateinit var keyDir: File

    @Before
    fun makeFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        parent = File(context.cacheDir, "tasty-net-test-${System.nanoTime()}")
        check(parent.mkdirs()) { "cannot create $parent" }
        keyDir = File(parent, "keys")
    }

    @After
    fun removeFolder() {
        parent.deleteRecursively()
    }

    @Test
    fun createsAndReloadsTheSameDeviceId() {
        val first = DeviceIdentity(keyDir).deviceId()
        val second = DeviceIdentity(keyDir).deviceId()
        Log.i(TAG, "reload: first=$first second=$second")
        assertTrue(first, DEVICE_ID.matches(first))
        assertEquals(first, second)
        assertTrue(File(keyDir, "device_key").isFile)
    }

    @Test
    fun anExistingKeyIsNotReplaced() {
        val first = DeviceIdentity(keyDir).deviceId()
        val keyFile = File(keyDir, "device_key")
        val bytes = keyFile.readBytes()
        val modified = keyFile.lastModified()
        // The clock of the file system may be coarse; wait so a rewrite would move the time.
        Thread.sleep(1_100)
        val again = DeviceIdentity(keyDir).deviceId()
        Log.i(TAG, "keep: first=$first again=$again modified=$modified")
        assertEquals(first, again)
        assertArrayEquals(bytes, keyFile.readBytes())
        assertEquals(modified, keyFile.lastModified())
    }

    @Test
    fun threadsCreatingAtOnceGetTheSameDeviceId() {
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                List(threads) {
                    pool.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            DeviceIdentity(keyDir).deviceId()
                        },
                    )
                }
            val ids = futures.map { it.get(30, TimeUnit.SECONDS) }
            Log.i(TAG, "race: ${ids.size} threads, distinct=${ids.toSet()}")
            assertEquals(ids.toString(), 1, ids.toSet().size)
            assertTrue(ids.first(), DEVICE_ID.matches(ids.first()))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun forAppKeepsTheKeyUnderTheNoBackupFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appKeyDir = File(context.noBackupFilesDir, "net")
        // Only remove the folder afterwards when this test made it; a key that was already there stays.
        val existed = appKeyDir.exists()
        try {
            val first = DeviceIdentity.forApp(context).deviceId()
            val second = DeviceIdentity.forApp(context).deviceId()
            Log.i(TAG, "forApp: dir=$appKeyDir existed=$existed first=$first second=$second")
            assertTrue(File(appKeyDir, "device_key").isFile)
            assertTrue(first, DEVICE_ID.matches(first))
            assertEquals(first, second)
        } finally {
            if (!existed) appKeyDir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "DeviceIdentityTest"
        val DEVICE_ID = Regex("[0-9a-f]{64}")
    }
}
