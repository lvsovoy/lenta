package com.example.lenta

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
}
