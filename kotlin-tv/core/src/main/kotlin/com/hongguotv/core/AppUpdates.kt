// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdate(val versionCode: Long, val versionName: String, val minSdk: Int,
                     val tag: String, val fileName: String, val size: Long, val sha256: String, val notes: String) {
    /** Compare by semantic version parsed from the release file name, not an untrusted versionCode. */
    fun newerThan(installed: Long, sdk: Int, installedVersionName: String?): Boolean {
        if (minSdk > sdk) return false
        return if (installedVersionName != null) compareSemver(versionName, installedVersionName) > 0 else versionCode > installed
    }
    val downloadUrl get() = "$RELEASES/download/$tag/$fileName"
    companion object {
        const val REPOSITORY = "673tong/hongguoTV"
        const val RELEASES = "https://github.com/$REPOSITORY/releases"
        const val MAX_APK_BYTES = 80L * 1024 * 1024
        // 两种历史命名都接受：旧 CI 的 hongguotv-kotlin-<ver>-android8.apk 与手工发布的 hongguoTV-<ver>.apk。
        val APK_NAME = Regex("hongguotv-kotlin-([0-9][0-9A-Za-z.+-]{0,80})-android8\\.apk")
        val APK_NAME_SIMPLE = Regex("hongguoTV-([0-9][0-9A-Za-z.+-]{0,80})\\.apk")

        /** Parse e.g. "0.10.1" into comparable major/minor/patch triple. */
        fun compareSemver(a: String, b: String): Int {
            fun parts(v: String) = v.split('.').map { it.trim().toIntOrNull() ?: 0 }
            val pa = parts(a); val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
                if (x != y) return x - y
            }
            return 0
        }

        /** Parse GitHub API `/releases/latest` JSON; pick the first TV APK asset. sha256 is unknown for raw releases. */
        fun parse(json: String): AppUpdate {
            val o = JSONObject(json)
            val tag = o.optString("tag_name").orEmpty().take(80)
            require(tag.isNotEmpty()) { "更新信息缺少版本标签" }
            val assets = o.optJSONArray("assets") ?: throw IOException("更新信息缺少安装包")
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name").orEmpty()
                val match = APK_NAME.matchEntire(name) ?: APK_NAME_SIMPLE.matchEntire(name) ?: continue
                val size = asset.optLong("size", 0)
                require(size in 1..MAX_APK_BYTES) { "安装包大小异常" }
                val version = match.groupValues[1]
                return AppUpdate(0L, version, 26, tag, name, size, "", o.optString("body").orEmpty().take(1500))
            }
            throw IOException("最新发布中没有可用的电视安装包")
        }
    }
}

/** Streaming validation also protects a cached APK from partial downloads and stale metadata. */
object UpdateFiles {
    /** With no upstream sha256, a cached APK is valid when size and a full archive parse both match. */
    fun verify(file: File, update: AppUpdate): Boolean = file.isFile && file.length() == update.size
    fun receive(input: InputStream, destination: File, update: AppUpdate, progress: (Int) -> Unit = {}) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L; var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer); if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常")
                    output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包长度校验未通过，请重新下载")
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                if (destination.exists()) throw IOException("无法保存安装包")
                partial.copyTo(destination, overwrite = true); partial.delete()
                if (!verify(destination, update)) throw IOException("无法保存安装包")
            }
        } finally { partial.delete() }
    }
}

class AppUpdateRepository : java.io.Closeable {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            val url = chain.request().url
            // Follow GitHub's API + CDN redirects, never cleartext or arbitrary manifest-provided hosts.
            if (url.scheme != "https" || url.host !in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
                throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }.build()
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater")
        .header("Accept", "application/vnd.github+json").header("Cache-Control", "no-cache").build()
    fun latest(): AppUpdate {
        val call = http.newCall(request("https://api.github.com/repos/${AppUpdate.REPOSITORY}/releases/latest"))
        call.timeout().timeout(25, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException(if (response.code == 404) "尚无可用的自动更新版本" else "无法检查更新，请稍后重试")
            val bytes = response.body?.byteStream()?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 262144) throw IOException("更新信息过大"); out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: throw IOException("更新信息为空")
            try { AppUpdate.parse(bytes.toString(Charsets.UTF_8)) } catch (_: IOException) { throw IOException("更新信息格式不正确") } catch (_: Exception) { throw IOException("更新信息格式不正确") }
        }
    }
    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        http.newCall(request(update.downloadUrl)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
            val body = response.body ?: throw IOException("安装包为空")
            if (body.contentLength() >= 0 && body.contentLength() != update.size) throw IOException("安装包长度异常")
            body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
        }
    }
    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
