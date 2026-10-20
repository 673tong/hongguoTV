package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    @Test fun simpleReleaseAssetNamingIsAlsoAccepted() {
        val update = AppUpdate.parse(releaseJson(name = "hongguoTV-0.9.3.apk", tag = "093").toString())
        assertEquals("0.9.3", update.versionName); assertEquals("093", update.tag)
        assertEquals("hongguoTV-0.9.3.apk", update.fileName)
        assertEquals("https://github.com/673tong/hongguoTV/releases/download/093/hongguoTV-0.9.3.apk", update.downloadUrl)
        assertTrue(update.newerThan(11, 26, "0.9.2")); assertFalse(update.newerThan(11, 26, "0.9.3"))
        // 大小写必须与发布附件完全一致，避免宽松匹配吃进任意文件。
        assertThrows(IOException::class.java) { AppUpdate.parse(releaseJson(name = "hongguotv-0.9.3.apk").toString()) }
        assertThrows(IOException::class.java) { AppUpdate.parse(releaseJson(name = "hongguoTV-0.9.3.apk.bak").toString()) }
    }
    @Test fun enforceSizeBounds() {
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(releaseJson(size = 0).toString()) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(releaseJson(size = -1).toString()) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(releaseJson(size = AppUpdate.MAX_APK_BYTES+1).toString()) }
    }
    @Test fun parsesCommittedMirrorManifest() {
        val json = JSONObject()
            .put("versionName", "0.9.6").put("tag", "096")
            .put("fileName", "hongguotv-kotlin-0.9.6-android8.apk").put("size", bytes.size.toLong())
            .put("minSdk", 26).put("notes", "mirror fallback")
        val update = AppUpdate.parseManifest(json.toString())
        assertEquals("0.9.6", update.versionName); assertEquals("096", update.tag)
        assertEquals("hongguotv-kotlin-0.9.6-android8.apk", update.fileName)
        assertEquals("https://github.com/673tong/hongguoTV/releases/download/096/hongguotv-kotlin-0.9.6-android8.apk", update.downloadUrl)
        assertTrue(update.newerThan(13, 26, "0.9.4")); assertFalse(update.newerThan(15, 26, "0.9.6"))
    }
    @Test fun mirrorManifestRejectsIncompleteOrTamperedData() {
        // 缺字段、坏命名、大小越界、非 JSON 一律拒绝，避免镜像返回错误页被当成更新。
        assertThrows(Exception::class.java) { AppUpdate.parseManifest("{\"versionName\":\"0.9.6\"}") }
        assertThrows(Exception::class.java) {
            AppUpdate.parseManifest(JSONObject().put("versionName", "0.9.6").put("tag", "096")
                .put("fileName", "evil.html").put("size", 10).toString())
        }
        assertThrows(Exception::class.java) {
            AppUpdate.parseManifest(JSONObject().put("versionName", "0.9.6").put("tag", "096")
                .put("fileName", "hongguotv-kotlin-0.9.6-android8.apk").put("size", 0).toString())
        }
        assertThrows(Exception::class.java) { AppUpdate.parseManifest("<html>Bad Gateway</html>") }
    }
    @Test fun mirrorPrefixesAndManifestUrlAreWellFormed() {
        assertEquals(listOf("https://gh-proxy.com/", "https://ghfast.top/"), UpdateMirrors.PREFIXES)
        val api = UpdateMirrors.apiUrl()
        assertEquals("https://api.github.com/repos/673tong/hongguoTV/releases/latest", api)
        assertEquals("https://cdn.jsdelivr.net/gh/673tong/hongguoTV@main/distribution/latest.json", UpdateMirrors.manifestUrl())
        // 每个镜像前缀拼上 GitHub URL 后必须仍在白名单主机内。
        UpdateMirrors.PREFIXES.forEach { prefix ->
            val host = prefix.toHttpUrlOrNull()?.host ?: fail("镜像前缀不是合法 URL: $prefix")
            assertTrue(host in UpdateMirrors.ALLOWED_HOSTS)
        }
        assertTrue("cdn.jsdelivr.net" in UpdateMirrors.ALLOWED_HOSTS)
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
