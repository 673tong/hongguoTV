// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 用 WebView 跑站点播放器的 `aw.wasm`，把 CENC 内容密钥解封出来。
 *
 * 为什么必须借 wasm：`play-info` 返回的 `player_access_key` 只是信封凭证，
 * 真正的内容密钥在 `_decryptKey()` 里被解出来后直接存进 wasm 自己的线性内存，
 * 不经过任何 JS 出口。所以纯 Kotlin 侧拿不到 16 字节密钥，只能原样执行这个模块。
 *
 * 这里的 WebView 不加载任何站点页面：它是一个空白页 + `assets/keybridge.js`
 * 的纯计算宿主，不需要网络、不需要 Cookie，也不注入任何网页脚本。
 *
 * 线程模型：[decrypt] 会阻塞到 JS 返回。所有调用都串行化在 [lock] 上，
 * 因为 wasm 的内容密钥是全局状态，并发喂不同剧集的密钥会互相污染。
 */
class CencKeyBridge(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var webView: WebView? = null
    private var ready = false
    private var accessKey: String? = null

    /** 在 UI 线程上创建 WebView；调用方负责确保线程正确。 */
    private fun ensureWebView(): WebView {
        // 只有 wasm 还没实例化时才走一遍 init；已就绪就直接复用。
        if (ready) webView?.let { return it }
        val view = webView ?: createWebView().also { webView = it }
        awaitJs(view, "hgInit('${wasmBase64()}')", timeoutMs = 15000).getOrThrow()
        ready = true
        return view
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(appContext)
        view.settings.javaScriptEnabled = true
        // 纯本地计算，不需要任何存储或网络能力。
        view.settings.domStorageEnabled = false
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.blockNetworkLoads = true
        view.webViewClient = WebViewClient()
        view.loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
        val script = appContext.assets.open("keybridge.js").bufferedReader().use { it.readText() }
        val latch = CountDownLatch(1)
        view.post { view.evaluateJavascript(script) { latch.countDown() } }
        if (!latch.await(5, TimeUnit.SECONDS)) throw IOException("密钥桥初始化超时")
        return view
    }

    private fun wasmBase64(): String {
        val bytes = appContext.assets.open("aw.wasm").use { it.readBytes() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 同步执行一段 JS，返回字符串结果（JS 抛错/超时都变成失败）。 */
    private fun awaitJs(view: WebView, script: String, timeoutMs: Long): Result<String> {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<String?>(null)
        val error = AtomicReference<String?>(null)
        view.post {
            view.evaluateJavascript(script) { value ->
                if (value == null || value == "null") error.set("脚本没有返回结果")
                else holder.set(value)
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return Result.failure(IOException("密钥桥响应超时"))
        holder.get()?.let { return Result.success(it) }
        return Result.failure(IOException(error.get() ?: "密钥桥未返回结果"))
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("\"") || !trimmed.endsWith("\"")) return trimmed
        // JS 字符串结果里可能出现转义；这里只需要 base64/纯文本，做最小还原。
        return trimmed.substring(1, trimmed.length - 1).replace("\\\\", "\\").replace("\\\"", "\"")
    }

    /**
     * 把任意文本编成 JS 双引号字符串字面量。
     *
     * 站点凭证是标准 base64，含 `+` `/` `=`，直接塞进 `hgSetKey('…')` 里
     * `+` 会被当运算符。**这里只做转义，绝不能做 base64 编码**：
     * `_decryptKey` 内部会自己按 base64 解入参，多编一层就成了错误密文。
     */
    private fun jsLiteral(text: String): String {
        val out = StringBuilder(text.length + 8)
        out.append('"')
        for (ch in text) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '<', '>' -> out.append("\\u").append(String.format("%04x", ch.code))
                else -> if (ch.code < 0x20) {
                    out.append("\\u").append(String.format("%04x", ch.code))
                } else out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }

    /**
     * 喂入本集的 `player_access_key`。同一凭证会缓存，避免重复解封。
     * 必须在后台线程调用（内部会等待 UI 线程的 WebView）。
     */
    fun prepare(accessKeyValue: String) {
        require(accessKeyValue.isNotBlank()) { "缺少播放凭证" }
        synchronized(lock) {
            if (accessKey == accessKeyValue && ready) return
            ready = false
            val view = onUiThread { ensureWebView() }
            val result = onUiThread {
                awaitJs(view, "hgSetKey(${jsLiteral(accessKeyValue)})", timeoutMs = 10000)
            }
            result.getOrThrow()
            accessKey = accessKeyValue
            ready = true
        }
    }

    /**
     * 对一块密文做 AES-CTR。`iv` 必须是 senc 里的 16 字节 IV。
     * 返回与 [data] 等长的明文块。
     */
    fun decrypt(data: ByteArray, iv: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        require(iv.size == 16) { "IV 必须是 16 字节" }
        synchronized(lock) {
            val view = onUiThread { ensureWebView() }
            val dataB64 = Base64.encodeToString(data, Base64.NO_WRAP)
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val result = onUiThread {
                awaitJs(view, "hgDecrypt('$dataB64','$ivB64')", timeoutMs = 20000)
            }.getOrThrow()
            val plain = Base64.decode(unquote(result), Base64.NO_WRAP)
            if (plain.size != data.size) throw IOException("解密输出长度异常")
            return plain
        }
    }

    /** 释放 WebView。只在播放会话彻底结束时调用。 */
    fun close() {
        synchronized(lock) {
            ready = false
            accessKey = null
            val view = webView ?: return
            webView = null
            runCatching { view.post { view.destroy() } }
        }
    }

    private fun <T> onUiThread(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val holder = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { holder.set(block()) } catch (t: Throwable) { failure.set(t) } finally { latch.countDown() }
        }
        if (!latch.await(30, TimeUnit.SECONDS)) throw IOException("等待主线程超时")
        failure.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return holder.get() as T
    }
}
