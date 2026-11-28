package dev.tasty.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoticeTest {
    @Test
    fun emptyTextHasNoLines() {
        assertEquals(emptyList<String>(), Notice.lines(""))
    }

    @Test
    fun finalLineFeedDoesNotAddAnEmptyLine() {
        assertEquals(listOf("a", "", "b"), Notice.lines("a\n\nb\n"))
    }

    @Test
    fun lastLineWithoutLineFeedIsKept() {
        assertEquals(listOf("a", "b"), Notice.lines("a\nb"))
    }

    @Test
    fun loneLineFeedIsOneEmptyLine() {
        assertEquals(listOf(""), Notice.lines("\n"))
    }

    @Test
    fun carriageReturnIsNotALineBreak() {
        assertEquals(listOf("a\r", "b"), Notice.lines("a\r\nb"))
    }

    /** The screen relies on the committed file having LF line ends only. */
    @Test
    fun committedNoticeHasNoCarriageReturn() {
        assertFalse(repoFile(Notice.ASSET).readText().contains('\r'))
    }

    /** The notice starts with the app's own license, the repo root LICENSE. */
    @Test
    fun committedNoticeStartsWithTheAppLicense() {
        val notice = repoFile(Notice.ASSET).readText()
        val license = repoFile("LICENSE").readText()
        val rule = "=".repeat(80)
        val head = "$rule\nTasty for Android\n$rule\n\n$license"
        assertTrue("THIRD_PARTY_NOTICES.txt does not start with the app license", notice.startsWith(head))
    }

    /** Unit tests run in the app module folder; the notice and LICENSE are at the repo root. */
    private fun repoFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val file = dir.resolve(name)
            if (file.isFile && dir.resolve("settings.gradle.kts").isFile) return file
            dir = dir.parentFile
        }
        error("$name not found above ${System.getProperty("user.dir")}")
    }
}
