package dev.tasty.android.net

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tasty.android.core.PeerKeyException
import dev.tasty.android.core.PeerPin
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs the peer key records through Kotlin, the UniFFI binding and the Rust library on a device. */
@RunWith(AndroidJUnit4::class)
class PeerKeysTest {
    private lateinit var parent: File
    private lateinit var peersDir: File

    @Before
    fun makeFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        parent = File(context.cacheDir, "tasty-peers-test-${System.nanoTime()}")
        check(parent.mkdirs()) { "cannot create $parent" }
        // Not created yet, so the first record also creates the folder.
        peersDir = File(parent, "peers")
    }

    @After
    fun removeFolder() {
        parent.deleteRecursively()
    }

    /** [count] different valid device IDs, made as device keys in folders of this test. */
    private fun deviceIds(count: Int): List<String> = List(count) { DeviceIdentity(File(parent, "key-$it")).deviceId() }

    @Test
    fun theFirstPinInsertsAndTheNextFindsIt() {
        val (first, second) = deviceIds(2)
        val keys = PeerKeys(peersDir)
        assertNull(keys.get(PEER))
        assertEquals(PeerPin(inserted = true, deviceId = first), keys.pinIfAbsent(PEER, first))
        assertEquals(PeerPin(inserted = false, deviceId = first), keys.pinIfAbsent(PEER, first))
        val record = File(peersDir, "$PEER.id")
        val bytes = record.readBytes()
        // Another ID does not change the record.
        val other = PeerKeys(peersDir).pinIfAbsent(PEER, second)
        Log.i(TAG, "pin: first=$first second=$second other=$other")
        assertEquals(PeerPin(inserted = false, deviceId = first), other)
        assertEquals(first, keys.get(PEER))
        assertArrayEquals(bytes, record.readBytes())
        assertEquals("$first\n", record.readText())
        assertEquals(listOf("$PEER.id"), peersDir.list()?.sorted())
    }

    @Test
    fun threadsPinningAtOnceInsertOnce() {
        val threads = 8
        val ids = deviceIds(threads)
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                ids.map { id ->
                    pool.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            id to PeerKeys(peersDir).pinIfAbsent(PEER, id)
                        },
                    )
                }
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            val winners = results.filter { (_, pin) -> pin.inserted }
            Log.i(
                TAG,
                "race: ${results.size} threads, inserted=${winners.size}, " +
                    "recorded=${results.map { it.second.deviceId }.toSet()}",
            )
            assertEquals(results.toString(), 1, winners.size)
            val winner = winners.single().first
            assertEquals(winner, winners.single().second.deviceId)
            // Every call that lost got the winner's ID, not its own.
            assertTrue(results.toString(), results.all { (_, pin) -> pin.deviceId == winner })
            assertEquals(winner, PeerKeys(peersDir).get(PEER))
            assertEquals(listOf("$PEER.id"), peersDir.list()?.sorted())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun reregisterReplacesTheRecord() {
        val (first, second) = deviceIds(2)
        val keys = PeerKeys(peersDir)
        keys.pinIfAbsent(PEER, first)
        keys.reregister(PEER, second)
        Log.i(TAG, "reregister: first=$first second=$second now=${keys.get(PEER)}")
        assertEquals(second, keys.get(PEER))
        assertEquals(PeerPin(inserted = false, deviceId = second), keys.pinIfAbsent(PEER, first))
    }

    @Test
    fun aCorruptRecordIsReportedAndKept() {
        val (id) = deviceIds(1)
        check(peersDir.mkdirs()) { "cannot create $peersDir" }
        val record = File(peersDir, "$PEER.id")
        record.writeText("broken\n")
        val keys = PeerKeys(peersDir)
        val onGet = assertThrows(PeerKeyException.Corrupt::class.java) { keys.get(PEER) }
        val onPin = assertThrows(PeerKeyException.Corrupt::class.java) { keys.pinIfAbsent(PEER, id) }
        Log.i(TAG, "corrupt: get=${onGet.reason} pin=${onPin.reason}")
        assertEquals("broken\n", record.readText())
        assertEquals(listOf("$PEER.id"), peersDir.list()?.sorted())
    }

    @Test
    fun invalidInputIsRefused() {
        val (id) = deviceIds(1)
        val keys = PeerKeys(peersDir)
        assertThrows(PeerKeyException.InvalidPeer::class.java) { keys.pinIfAbsent("a/b", id) }
        assertThrows(PeerKeyException.InvalidPeer::class.java) { keys.get("") }
        assertThrows(PeerKeyException.InvalidDeviceId::class.java) { keys.pinIfAbsent(PEER, id.uppercase()) }
        assertThrows(PeerKeyException.InvalidDeviceId::class.java) { keys.reregister(PEER, "zz") }
        assertFalse(peersDir.exists())
    }

    @Test
    fun forAppKeepsTheRecordsUnderTheNoBackupFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val netDir = File(context.noBackupFilesDir, "net")
        val appPeersDir = File(netDir, "peers")
        // Only remove folders afterwards that this test made; records and keys already there stay.
        val netExisted = netDir.exists()
        val peersExisted = appPeersDir.exists()
        val peer = "forapp-test-${System.nanoTime()}"
        val record = File(appPeersDir, "$peer.id")
        val (id) = deviceIds(1)
        try {
            val pin = PeerKeys.forApp(context).pinIfAbsent(peer, id)
            Log.i(TAG, "forApp: dir=$appPeersDir netExisted=$netExisted peersExisted=$peersExisted pin=$pin")
            assertEquals(PeerPin(inserted = true, deviceId = id), pin)
            assertTrue(record.isFile)
            assertEquals(id, PeerKeys(appPeersDir).get(peer))
            assertEquals(id, PeerKeys.forApp(context).get(peer))
        } finally {
            record.delete()
            if (!peersExisted) appPeersDir.deleteRecursively()
            if (!netExisted) netDir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "PeerKeysTest"
        const val PEER = "profile-1"
    }
}
