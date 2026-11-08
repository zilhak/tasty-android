package dev.tasty.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoTest {
    @Test
    fun homeTitleIsAppName() {
        assertEquals("Tasty", AppInfo.homeTitle())
    }
}
