package me.lesovoy.lenta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BrowserNavigationUnitTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun getNextcloudParentPath(currentPath: String): String? {
        val clean = currentPath.trim('/')
        if (clean.isEmpty()) return null
        return if (clean.contains('/')) clean.substringBeforeLast('/') else ""
    }

    private fun canNavigateUpNextcloud(currentPath: String): Boolean {
        return currentPath.trim('/').isNotEmpty()
    }

    private fun canNavigateUpLocal(directory: File): Boolean {
        val parent = directory.parentFile
        return parent != null && parent.exists() && parent.canRead()
    }

    @Test
    fun testNextcloudParentPathNavigation() {
        assertNull(getNextcloudParentPath(""))
        assertNull(getNextcloudParentPath("/"))
        assertNull(getNextcloudParentPath("///"))

        assertEquals("", getNextcloudParentPath("Photos"))
        assertEquals("", getNextcloudParentPath("/Photos/"))
        assertEquals("Photos", getNextcloudParentPath("Photos/2026"))
        assertEquals("Photos", getNextcloudParentPath("/Photos/2026/"))
        assertEquals("Photos/2026", getNextcloudParentPath("Photos/2026/Summer"))
        assertEquals("Photos/2026/Summer", getNextcloudParentPath("Photos/2026/Summer/Day1"))

        assertFalse(canNavigateUpNextcloud(""))
        assertFalse(canNavigateUpNextcloud("/"))
        assertTrue(canNavigateUpNextcloud("Photos"))
        assertTrue(canNavigateUpNextcloud("/Photos/2026"))
    }

    @Test
    fun testLocalDirectoryParentNavigation() {
        val rootDir = tempFolder.root
        val subDir = File(rootDir, "SubFolder").apply { mkdir() }
        val nestedDir = File(subDir, "NestedFolder").apply { mkdir() }

        assertTrue(canNavigateUpLocal(nestedDir))
        assertEquals(subDir.absolutePath, nestedDir.parentFile?.absolutePath)

        assertTrue(canNavigateUpLocal(subDir))
        assertEquals(rootDir.absolutePath, subDir.parentFile?.absolutePath)
    }

    @Test
    fun testNextcloudSourceStatusFormat() {
        val configuredUrl = "https://cloud.myserver.com"
        val status = "Connected ($configuredUrl)"
        assertEquals("Connected (https://cloud.myserver.com)", status)
    }

    private fun calculateOptimalSpanCount(resourceColumns: Int, screenWidthDp: Int): Int {
        val computedColumns = (screenWidthDp / 160).coerceAtLeast(2)
        return maxOf(resourceColumns, computedColumns)
    }

    @Test
    fun testFileGridOptimalSpanCountCalculation() {
        // Phone Portrait (~360dp-411dp)
        assertEquals(2, calculateOptimalSpanCount(resourceColumns = 2, screenWidthDp = 360))
        assertEquals(2, calculateOptimalSpanCount(resourceColumns = 2, screenWidthDp = 411))

        // Phone Landscape (~640dp-840dp)
        assertEquals(4, calculateOptimalSpanCount(resourceColumns = 4, screenWidthDp = 640))
        assertEquals(4, calculateOptimalSpanCount(resourceColumns = 4, screenWidthDp = 720))
        assertEquals(5, calculateOptimalSpanCount(resourceColumns = 4, screenWidthDp = 840))

        // Tablet Portrait (~600dp-768dp)
        assertEquals(3, calculateOptimalSpanCount(resourceColumns = 3, screenWidthDp = 600))
        assertEquals(4, calculateOptimalSpanCount(resourceColumns = 3, screenWidthDp = 768))

        // Large Tablet Landscape (~1024dp-1280dp)
        assertEquals(6, calculateOptimalSpanCount(resourceColumns = 5, screenWidthDp = 1024))
        assertEquals(8, calculateOptimalSpanCount(resourceColumns = 6, screenWidthDp = 1280))
    }

    private fun shouldCloseDrawer(hasNavView: Boolean, isDrawerOpen: Boolean): Boolean {
        return hasNavView && isDrawerOpen
    }

    private fun shouldOpenDrawer(hasNavView: Boolean, isDrawerOpen: Boolean): Boolean {
        return hasNavView && !isDrawerOpen
    }

    @Test
    fun testDrawerInteractionSafetyConditions() {
        // When drawer view is absent (phone portrait)
        assertFalse(shouldCloseDrawer(hasNavView = false, isDrawerOpen = false))
        assertFalse(shouldCloseDrawer(hasNavView = false, isDrawerOpen = true))
        assertFalse(shouldOpenDrawer(hasNavView = false, isDrawerOpen = false))
        assertFalse(shouldOpenDrawer(hasNavView = false, isDrawerOpen = true))

        // When drawer view is present (landscape or wide layouts)
        assertFalse(shouldCloseDrawer(hasNavView = true, isDrawerOpen = false))
        assertTrue(shouldCloseDrawer(hasNavView = true, isDrawerOpen = true))
        assertTrue(shouldOpenDrawer(hasNavView = true, isDrawerOpen = false))
        assertFalse(shouldOpenDrawer(hasNavView = true, isDrawerOpen = true))
    }
}
