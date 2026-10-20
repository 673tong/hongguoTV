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

        /**
         * Parse the committed mirror fallback manifest (distribution/latest.json on the default branch).
         * Same fields the release API would give us, minus anything we cannot verify ourselves.
         */
        fun parseManifest(json: String): AppUpdate {
            val o = try { JSONObject(json) } catch (_: Exception) { throw IOException("更新信息格式不正确") }
            val version = o.optString("versionName").orEmpty()
            val tag = o.optString("tag").orEmpty()
            val fileName = o.optString("fileName").orEmpty()
            require(version.isNotEmpty() && tag.isNotEmpty() && fileName.isNotEmpty()) { "更新信息不完整" }
            (APK_NAME.matchEntire(fileName) ?: APK_NAME_SIMPLE.matchEntire(fileName))
                ?: throw IOException("更新清单中的安装包命名异常")
            val size = o.optLong("size", 0)
            require(size in 1..MAX_APK_BYTES) { "安装包大小异常" }
            return AppUpdate(0L, version, o.optInt("minSdk", 26), tag, fileName, size,
                o.optString("sha256").orEmpty(), o.optString("notes").orEmpty().take(1500))
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

/** Mirror prefixes tested against this project's API + release assets; each just forwards the full GitHub URL. */
object UpdateMirrors {
    /** Order is priority. gh-proxy also proxies api.github.com; ghfast only proxies github.com assets. */
    val PREFIXES = listOf("https://gh-proxy.com/", "https://ghfast.top/")
    /** Committed manifest on the default branch, served by jsDelivr's China-reachable CDN as the last resort. */
    fun manifestUrl(repository: String = AppUpdate.REPOSITORY) = "https://cdn.jsdelivr.net/gh/$repository@main/distribution/latest.json"
    fun apiUrl(repository: String = AppUpdate.REPOSITORY) = "https://api.github.com/repos/$repository/releases/latest"
    val ALLOWED_HOSTS = setOf(
        "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
        "gh-proxy.com", "ghfast.top", "cdn.jsdelivr.net",
    )
}

class AppUpdateRepository : java.io.Closeable {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            val url = chain.request().url
            // GitHub's API + CDN, plus the mirror prefixes and the jsDelivr manifest host.
            // Mirrors only ever forward fixed GitHub URLs built in this file, never manifest-provided hosts.
            if (url.scheme != "https" || url.host !in UpdateMirrors.ALLOWED_HOSTS)
                throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }.build()
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater")
        .header("Accept", "application/vnd.github+json").header("Cache-Control", "no-cache").build()

    /** 直连 GitHub 常年超时（国内网络），逐个源回退直到拿到可用清单。 */
    private fun fetchManifest(): String {
        val sources = buildList {
            add(UpdateMirrors.apiUrl())
            UpdateMirrors.PREFIXES.forEach { prefix -> add(prefix + UpdateMirrors.apiUrl()) }
            add(UpdateMirrors.manifestUrl())
        }
        var lastError: Exception? = null
        for (url in sources) {
            try {
                val call = http.newCall(request(url))
                call.timeout().timeout(25, TimeUnit.SECONDS)
                return call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val bytes = response.body?.byteStream()?.use { input ->
                        val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                        while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 262144) throw IOException("更新信息过大"); out.write(buffer, 0, n) }
                        out.toByteArray()
                    } ?: throw IOException("更新信息为空")
                    bytes.toString(Charsets.UTF_8)
                }
            } catch (e: Exception) { lastError = e }
        }
        throw IOException("无法连接更新服务器（GitHub 与镜像均不可达），请稍后重试", lastError)
    }
    fun latest(): AppUpdate {
        val body = fetchManifest()
        return try { AppUpdate.parse(body) } catch (e: Exception) {
            try { AppUpdate.parseManifest(body) } catch (e2: Exception) {
                // 两个解析都失败说明拿到的既不是 API JSON 也不是清单 JSON（如镜像返回了错误页）。
                throw IOException("更新信息格式不正确", e2)
            }
        }
    }
    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        val urls = buildList {
            add(update.downloadUrl)
            UpdateMirrors.PREFIXES.forEach { prefix -> add(prefix + update.downloadUrl) }
        }
        var lastError: Exception? = null
        for (url in urls) {
            try {
                http.newCall(request(url)).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("安装包下载失败（HTTP ${response.code}）")
                    val body = response.body ?: throw IOException("安装包为空")
                    if (body.contentLength() >= 0 && body.contentLength() != update.size) throw IOException("安装包长度异常")
                    body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
                }
                return
            } catch (e: Exception) {
                // 用户主动取消不能被镜像重试吞掉。
                if (e.message == "下载已取消" || Thread.currentThread().isInterrupted) throw e
                lastError = e
            }
        }
        throw lastError ?: IOException("安装包下载失败，请稍后重试")
    }
    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
