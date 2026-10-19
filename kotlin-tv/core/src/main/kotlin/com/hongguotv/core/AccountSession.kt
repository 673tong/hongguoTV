// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 抖音扫码登录（番茄 / 木叶网页 passport）。
 *
 * 流程与网页版一致：
 * 1. [requestQr] 取二维码图片与 `token`；
 * 2. 用抖音 App 扫码并在手机上确认；
 * 3. [poll] 轮询状态，`confirmed` 后跟随 `redirect_url` 拿到会话 Cookie；
 * 4. [profile] 调用 `passport/account/info/v2` 确认会话有效并取昵称。
 *
 * 注意范围：这里拿到的是**网页域**会话（aid=2503）。红果短剧 App（aid=8704）
 * 的 passport 明确拒绝网页签发（错误码 16「该应用无权限」），因此红果短剧自身的
 * 收藏 / 观看历史不能用这条路直接读取；能读到哪些列表由登录后的实测结果决定。
 */
class AccountSession(
    http: OkHttpClient,
    private val store: SessionStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 会话持久化。实现方负责把 Cookie 存到私有存储，不写日志。 */
    interface SessionStore {
        fun read(): Map<String, String>
        fun write(cookies: Map<String, String>)
    }

    data class QrCode(val token: String, val pngBase64: String, val expiresAt: Long, val hint: String)
    enum class Status { WAITING, SCANNED, CONFIRMED, EXPIRED, CANCELLED, UNKNOWN }
    data class Progress(val status: Status, val nickname: String = "", val detail: String = "")
    data class Profile(val nickname: String, val userId: String)

    // 关闭自动重定向：每一跳的 Set-Cookie 都要自己抓，否则会话会丢。
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    @Volatile private var cookies: Map<String, String> = store.read()

    private fun cookieHeader() = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }

    /** 只保留 name=value，丢弃 Path/Domain/Expires 等属性。 */
    private fun absorb(response: okhttp3.Response) {
        val merged = mergeCookies(cookies, response.headers("Set-Cookie"))
        if (merged != cookies) {
            cookies = merged
            store.write(merged)
        }
    }

    private data class Reply(val code: Int, val body: String, val location: String?)

    /** 发一次请求并返回正文与跳转目标；3xx 由 [follow] 手动处理。 */
    private fun get(url: HttpUrl, referer: String = SITE): Reply {
        val request = Request.Builder().url(url)
            .header("User-Agent", WEB_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", referer)
            .apply { val cookie = cookieHeader(); if (cookie.isNotEmpty()) header("Cookie", cookie) }
            .get().build()
        return RequestScope.execute(client.newCall(request)) { response ->
            absorb(response)
            Reply(response.code, response.body?.string().orEmpty(), response.header("Location"))
        }
    }

    /** 手动跟随重定向（最多 5 跳），沿途收集 Set-Cookie。 */
    private fun follow(url: String, referer: String = SITE): String {
        var next = url.toHttpUrl()
        repeat(5) {
            val reply = get(next, referer)
            if (reply.code !in 300..399) return reply.body
            val location = reply.location ?: return reply.body
            next = if (location.startsWith("http")) location.toHttpUrl() else next.resolve(location) ?: return reply.body
        }
        return ""
    }

    fun isLoggedIn(): Boolean = profile() != null

    /** 会话过期或未登录返回 null。 */
    fun profile(): Profile? {
        if (cookies.isEmpty()) return null
        return runCatching {
            val reply = get("$SITE/passport/account/info/v2/?aid=$WEB_AID".toHttpUrl())
            if (reply.code != 200) return null
            val data = JSONObject(reply.body).optJSONObject("data") ?: return null
            if (data.optInt("error_code", 0) != 0) return null
            val name = data.str("name").ifEmpty { data.str("screen_name").ifEmpty { data.str("nickname") } }
            val id = data.str("user_id").ifEmpty { data.str("uid") }
            if (name.isEmpty() && id.isEmpty()) null else Profile(name.ifEmpty { "已登录账号" }, id)
        }.getOrNull()
    }

    fun requestQr(): QrCode {
        val url = "$SITE/passport/web/get_qrcode/".toHttpUrl().newBuilder()
            .addQueryParameter("aid", WEB_AID)
            .addQueryParameter("service", SERVICE)
            .addQueryParameter("next", "/")
            .build()
        val reply = get(url)
        if (reply.code != 200) throw IOException("无法获取登录二维码（HTTP ${reply.code}）")
        val data = JSONObject(reply.body).optJSONObject("data") ?: throw IOException("登录二维码格式异常")
        val error = data.optInt("error_code", 0)
        if (error != 0) throw IOException(data.str("description").ifEmpty { "登录二维码暂不可用（$error）" })
        val token = data.str("token")
        val png = data.str("qrcode")
        if (token.isEmpty() || png.isEmpty()) throw IOException("登录二维码不完整")
        // 服务端给的是 base64 的 PNG，直接交给 UI 解码，不需要本地生成二维码。
        val expires = data.optLong("expire_time", 0L).takeIf { it > 0 }?.times(1000) ?: (now() + 120_000)
        return QrCode(token, png, expires, hint(data.str("copywriting")))
    }

    /** 轮询一次扫码状态；调用方按 1–2 秒节奏循环直到非 [Status.WAITING]/[Status.SCANNED]。 */
    fun poll(token: String): Progress {
        val url = "$SITE/passport/web/check_qrconnect/".toHttpUrl().newBuilder()
            .addQueryParameter("aid", WEB_AID)
            .addQueryParameter("service", SERVICE)
            .addQueryParameter("next", "/")
            .addQueryParameter("token", token)
            .build()
        val reply = get(url)
        if (reply.code != 200) return Progress(Status.UNKNOWN, detail = "HTTP ${reply.code}")
        val data = JSONObject(reply.body).optJSONObject("data") ?: return Progress(Status.UNKNOWN, detail = "状态响应异常")
        if (data.optInt("error_code", 0) != 0) return Progress(Status.EXPIRED, detail = data.str("description"))
        val status = mapStatus(data.str("status"))
        if (status != Status.CONFIRMED) return Progress(status)
        return complete(data)
    }

    /** 确认后跟随 redirect_url 落会话 Cookie，再取昵称。 */
    private fun complete(data: JSONObject): Progress {
        val redirect = listOf("redirect_url", "redirect_uri", "url", "next_url")
            .firstNotNullOfOrNull { data.str(it).takeIf { value -> value.isNotEmpty() } }
        if (redirect != null) runCatching { follow(redirect) }
        val profile = profile()
        return if (profile == null) Progress(Status.CONFIRMED, detail = "会话建立失败，请重试")
        else Progress(Status.CONFIRMED, nickname = profile.nickname)
    }

    fun signOut() {
        runCatching { get("$SITE/passport/web/logout/?aid=$WEB_AID&need_redirect=0".toHttpUrl()) }
        cookies = emptyMap()
        store.write(emptyMap())
    }

    /** 当前会话 Cookie 快照（只读用途，便于诊断；不要写日志）。 */
    fun cookieNames(): List<String> = cookies.keys.sorted()

    private fun hint(copywriting: String): String {
        if (copywriting.isEmpty()) return "请用抖音 App 扫码并确认登录"
        val text = runCatching { JSONObject(copywriting).str("desc") }.getOrDefault("")
            .ifEmpty { runCatching { JSONObject(copywriting).str("title") }.getOrDefault("") }
        return text.replace(Regex("\\[/?[a-zA-Z0-9_#=,\\-]*\\]"), "").ifEmpty { "请用抖音 App 扫码并确认登录" }
    }

    companion object {
        private const val SITE = "https://fanqienovel.com"
        private const val WEB_AID = "2503"
        private const val SERVICE = "fanqienovel.com"
        private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        internal fun decodePng(base64: String): ByteArray = Base64.getDecoder().decode(base64)
        /** 轮询间隔：网页版也是 2 秒左右，太快会被 passport 限流。 */
        fun pollIntervalMillis() = TimeUnit.SECONDS.toMillis(2)

        /** 服务端状态字符串 → 内部枚举。未知值一律当作"还能再等一次"，避免误判成功。 */
        internal fun mapStatus(raw: String): Status = when (raw.trim().lowercase()) {
            "new" -> Status.WAITING
            "scanned", "scan", "scanning" -> Status.SCANNED
            "confirmed", "confirm", "success" -> Status.CONFIRMED
            "expired" -> Status.EXPIRED
            "canceled", "cancelled", "refused" -> Status.CANCELLED
            else -> Status.UNKNOWN
        }

        /**
         * 合并 Set-Cookie。
         *
         * 服务端用空值或 `Max-Age=0` 表示删除；这里必须真的移除，否则旧的
         * `sessionid` 会一直被带上去，表现为"已退出但仍显示登录"。
         */
        internal fun mergeCookies(current: Map<String, String>, setCookie: List<String>): Map<String, String> {
            if (setCookie.isEmpty()) return current
            val merged = LinkedHashMap(current)
            for (raw in setCookie) {
                val pair = raw.substringBefore(';').trim()
                val at = pair.indexOf('=')
                if (at <= 0) continue
                val name = pair.substring(0, at).trim()
                val value = pair.substring(at + 1).trim()
                if (name.isEmpty()) continue
                if (value.isEmpty() || raw.contains("Max-Age=0", ignoreCase = true)) merged.remove(name) else merged[name] = value
            }
            return merged
        }
    }
}
