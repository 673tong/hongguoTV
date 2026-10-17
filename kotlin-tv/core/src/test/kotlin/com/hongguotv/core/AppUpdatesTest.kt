package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AppUpdatesTest {
    private val bytes = "signed-apk-test-bytes".toByteArray()
    private fun releaseJson(name: String = "hongguotv-kotlin-0.10.0-android8.apk", size: Long = bytes.size.toLong(), tag: String = "0100") = JSONObject()
        .put("tag_name", tag).put("body", "release notes")
        .put("assets", JSONArray().put(JSONObject().put("name", name).put("size", size)))
    private fun update() = AppUpdate.parse(releaseJson().toString())
    private fun withFile(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("update-test").toFile()
        try { block(File(dir, "update.apk")) } finally { dir.deleteRecursively() }
    }
    @Test fun parsesLatestReleaseAndComparesSemver() {
        val update = update()
        assertEquals("0.10.0", update.versionName); assertEquals("0100", update.tag); assertEquals("hongguotv-kotlin-0.10.0-android8.apk", update.fileName)
        assertTrue(update.newerThan(9, 26, "0.9.0")); assertTrue(update.newerThan(9, 26, "0.9.9"))
        assertFalse(update.newerThan(9, 26, "0.10.0")); assertFalse(update.newerThan(9, 26, "0.11.0")); assertFalse(update.newerThan(9, 25, "0.1.0"))
        assertEquals("https://github.com/673tong/hongguoTV/releases/download/0100/hongguotv-kotlin-0.10.0-android8.apk", update.downloadUrl)
    }
    @Test fun semverComparisonHandlesMultiDigitParts() {
        assertEquals(1, AppUpdate.compareSemver("0.10.0", "0.9.0"))
        assertEquals(0, AppUpdate.compareSemver("1.2.3", "1.2.3"))
        assertEquals(-1, AppUpdate.compareSemver("1.2.3", "1.2.4"))
    }
    @Test fun downloadUrlUsesOnlyFixedRepositoryAndValidatedNames() {
        assertEquals("https://github.com/673tong/hongguoTV/releases/download/0100/hongguotv-kotlin-0.10.0-android8.apk", update().downloadUrl)
        // Non-APK assets are skipped; a release without a TV APK is rejected.
        assertThrows(IOException::class.java) { AppUpdate.parse(releaseJson(name = "notes.txt").toString()) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(releaseJson(tag = "").toString()) }
        // Traversal-style names simply do not match the APK regex and yield no update.
        assertThrows(IOException::class.java) { AppUpdate.parse(releaseJson(name = "../../bad.apk").toString()) }
    }
    @Test fun enforceSizeBounds() {
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(releaseJson(size = 0).toString()) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(releaseJson(size = -1).toString()) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(releaseJson(size = AppUpdate.MAX_APK_BYTES+1).toString()) }
    }
    @Test fun verifiedDownloadAtomicallyReplacesOldFile() = withFile { file ->
        file.writeText("previous"); val progress = mutableListOf<Int>()
        UpdateFiles.receive(ByteArrayInputStream(bytes), file, update(), progress::add)
        assertTrue(UpdateFiles.verify(file, update())); assertEquals(100, progress.last()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun truncatedDownloadPreservesPreviousFile() = withFile { file ->
        file.writeText("previous")
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes.copyOf(4)), file, update()) }
        assertEquals("previous", file.readText()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun oversizedDownloadIsRejectedAndRemoved() = withFile { file ->
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes+byteArrayOf(1)), file, update()) }
        assertFalse(file.exists()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun interruptedDownloadCleansUpPartialFile() = withFile { file ->
        Thread.currentThread().interrupt()
        try { assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes), file, update()) } }
        finally { Thread.interrupted() }
        assertFalse(file.exists()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun cacheMustStillMatchExpectedSize() = withFile { file ->
        file.writeBytes(bytes); assertTrue(UpdateFiles.verify(file, update()))
        // 校验基于发布元数据中的大小；同体积异内容由安装阶段的包名/版本/签名校验兜底。
        file.writeBytes(ByteArray(bytes.size - 1)); assertFalse(UpdateFiles.verify(file, update()))
        assertFalse(UpdateFiles.verify(File(file.parentFile, "missing.apk"), update()))
    }
}
