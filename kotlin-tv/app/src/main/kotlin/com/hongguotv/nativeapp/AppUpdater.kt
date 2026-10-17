// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.AppUpdateRepository
import com.hongguotv.core.UpdateFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground-only updater: no background service, and no networking or prompts during playback. */
class AppUpdater(private val activity: Activity, private val idle: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val repository = AppUpdateRepository()
    private val preferences = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE)
    private val apk = File(activity.cacheDir, "updates/update.apk")
    private var task: Future<*>? = null
    private var generation = 0
    private var active = false
    private var busy = false
    private var available: AppUpdate? = null
    private var ready = false
    private var permissionRequested = false
    private var dialog: AlertDialog? = null
    val showing get() = dialog?.isShowing == true
    private var statusView: TextView? = null
    private var anchor: View? = null
    private var status = "当前版本 ${BuildConfig.VERSION_NAME}"
    private var retryAfter = 0L
    private var promptedCode = ""
    private var startupChecked = false
    /** 已装版本与本机签名不一致：只能卸载后重装，覆盖安装必被系统拒绝。 */
    private var signingConflict = false
    private val auto get() = preferences.getBoolean("automatic", true)
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (idle() && dialog == null && !busy && auto) {
                val now = System.currentTimeMillis()
                val last = preferences.getLong("last_attempt", 0)
                val update = available
                when {
                    ready && update != null && promptedCode != update.versionName && !deferred(update) -> { promptedCode = update.versionName; show(activity.currentFocus) }
                    update != null && !ready && now >= retryAfter -> download(false)
                    now < last || now - last >= 6 * 60 * 60 * 1000L -> check(false)
                }
            }
            main.postDelayed(this, 15000)
        }
    }
    fun resume() {
        active = true; main.removeCallbacks(poll); main.postDelayed(poll, 8000)
        // 每次冷启动都强制检查一次新版本（首次 resume 时清空节流时间戳）。
        if (!startupChecked) { startupChecked = true; preferences.edit().putLong("last_attempt", 0).apply() }
        if (permissionRequested) {
            permissionRequested = false
            if (activity.packageManager.canRequestPackageInstalls()) install()
            else { status = "安装权限未开启，可在这里再次安装"; show(anchor) }
        }
    }
    fun pause() {
        active = false; main.removeCallbacks(poll); interrupt()
        dialog?.dismiss()
    }
    fun interrupt() {
        generation++; task?.cancel(true); task = null; repository.cancel(); busy = false
    }
    fun destroy() {
        pause(); worker.shutdownNow()
        // TLS socket close may send close_notify; it must not run on Android's main thread.
        Thread({ repository.close() }, "hongguotv-update-cleanup").start()
    }
    private fun updateStatus(text: String) { status = text; statusView?.text = text }
    private fun deferred(update: AppUpdate) = preferences.getString("deferred_version", null) == update.versionName && preferences.getLong("deferred_until", 0) > System.currentTimeMillis()
    private fun renderActions() {
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = when { busy -> "处理中…"; ready -> "安装新版"; available != null -> "下载新版"; else -> "检查更新" }
            // Keep D-pad focus stable while the request runs; action methods reject duplicates.
            isEnabled = true
        }
    }
    fun show(from: View?) {
        if (!active) return
        anchor = from
        dialog?.dismiss()
        val dp = activity.resources.displayMetrics.density
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding((24*dp).toInt(), (12*dp).toInt(), (24*dp).toInt(), 0) }
        statusView = TextView(activity).apply { text = status; textSize = 17f; setTextColor(TvStyle.text) }
        body.addView(statusView)
        body.addView(CheckBox(activity).apply {
            text = "自动检查并下载更新"; isChecked = auto
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("automatic", enabled).apply()
                if (!enabled && busy) { interrupt(); updateStatus("自动更新已关闭，可手动检查"); renderActions() }
            }
        })
        body.addView(TextView(activity).apply {
            text = "只在应用前台浏览时检查，播放中不打扰。下载后需确认系统安装，收藏与观看记录保留。"; textSize = 13f; setTextColor(TvStyle.muted)
            setPadding(0, (8*dp).toInt(), 0, (8*dp).toInt())
        })
        val next = AlertDialog.Builder(activity).setTitle("版本与更新 · ${BuildConfig.VERSION_NAME}").setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("稍后", null).setPositiveButton("检查更新", null).create()
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) { dialog = null; statusView = null }
            if (from?.isAttachedToWindow == true) from.requestFocus()
        }
        next.show()
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { when { ready -> install(); available != null -> download(true); else -> check(true) } }
        next.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            available?.let { preferences.edit().putString("deferred_version", it.versionName).putLong("deferred_until", System.currentTimeMillis()+24*60*60*1000L).apply() }
            next.dismiss()
        }
        renderActions()
        next.getButton(AlertDialog.BUTTON_POSITIVE).post { if (dialog === next) next.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
    }
    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        busy = true; renderActions(); val ticket = ++generation
        task = worker.submit {
            try {
                val result = action()
                main.post { if (active && generation == ticket) { busy = false; done(result); renderActions() } }
            } catch (e: Exception) {
                main.post { if (active && generation == ticket) {
                    busy = false; ready = false; retryAfter = System.currentTimeMillis()+30*60*1000L
                    updateStatus(if (e is IOException) e.message ?: "更新失败，请重试" else "更新校验失败，请重试"); renderActions()
                    if (signingConflict && dialog == null) resolveSigningConflict()
                } }
            }
        }
    }
    private fun check(manual: Boolean) {
        if (busy || !active || !idle()) return
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        updateStatus("正在检查新版…")
        work({ repository.latest() }) { update ->
            available = update.takeIf { it.newerThan(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME) }; ready = false
            if (available == null) {
                updateStatus("已是最新版本 ${BuildConfig.VERSION_NAME}")
                worker.execute { apk.delete() }
            } else {
                updateStatus("发现 ${update.versionName} · ${"%.1f".format(update.size/1048576.0)} MB\n${update.notes}")
                if (manual || auto) download(manual)
            }
        }
    }
    private fun download(manual: Boolean) {
        val update = available ?: return
        if (busy || !active || !idle()) return
        updateStatus("正在下载 ${update.versionName}…")
        val ticket = generation + 1
        work({
            repository.download(update, apk) { percent -> main.post { if (generation == ticket && active) updateStatus("正在下载 ${update.versionName} · $percent%") } }
            validateApk(update)
        }) {
            ready = true; updateStatus("${update.versionName} 已下载并校验\n${update.notes}\n\n确认后由系统覆盖安装，保留本机记录。")
            if (idle() && dialog == null && (manual || !deferred(update))) { promptedCode = update.versionName; show(activity.currentFocus) }
        }
    }
    @Suppress("DEPRECATION")
    private fun validateApk(update: AppUpdate) {
        if (!UpdateFiles.verify(apk, update)) throw IOException("安装包校验未通过，请重新下载")
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("安装包无法识别")
        val installed = pm.getPackageInfo(activity.packageName, flags)
        fun signers(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { it.toCharsString() }?.toSet().orEmpty()
        if (candidate.packageName != activity.packageName || candidate.versionName != update.versionName ||
            AppUpdate.compareSemver(update.versionName, BuildConfig.VERSION_NAME) <= 0 || candidate.applicationInfo?.minSdkVersion != update.minSdk)
            throw IOException("新版包名、版本或最低系统不匹配，已阻止安装")
        if (signers(installed).isEmpty() || signers(installed) != signers(candidate)) {
            // 签名与已装版本不同：系统必然拒绝覆盖安装，只能卸载旧版后重装。
            signingConflict = true
            throw IOException("新版与本机已装版本签名不同，系统无法覆盖安装。需先卸载旧版（收藏与观看记录会清空）再安装。")
        }
        signingConflict = false
    }
    /** Offer the one workable path when the signing key changed: uninstall, then install. */
    private fun resolveSigningConflict() {
        val update = available ?: return
        val dp = activity.resources.displayMetrics.density
        val body = TextView(activity).apply {
            text = "本机安装的是旧签名版本，无法直接覆盖升级。\n\n" +
                "选择「卸载并安装」会先卸载当前应用（收藏、观看记录与设置将被清除），随后打开系统安装器安装 ${update.versionName}。\n" +
                "选择「保留数据」可继续使用当前版本，之后可在系统设置中手动卸载再安装。"
            textSize = 16f; setTextColor(TvStyle.text); setPadding((24*dp).toInt(), (12*dp).toInt(), (24*dp).toInt(), 0)
        }
        val next = AlertDialog.Builder(activity).setTitle("需要先卸载旧版本").setView(body)
            .setNegativeButton("保留数据", null)
            .setPositiveButton("卸载并安装", null).create()
        val restore = anchor
        next.setOnDismissListener { if (restore?.isAttachedToWindow == true) restore.requestFocus() }
        next.show()
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            next.dismiss()
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${activity.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { activity.startActivity(intent) } catch (_: Exception) {
                try { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))) }
                catch (_: Exception) { Toast.makeText(activity, "无法打开系统卸载页，请在电视设置中手动卸载", Toast.LENGTH_LONG).show() }
            }
        }
    }
    private fun install() {
        val update = available ?: return
        if (busy || !active || !ready) return
        work({ validateApk(update) }) {
            try {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    permissionRequested = true
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                } else {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("更新安装包", uri) })
                }
            } catch (_: Exception) { permissionRequested = false; updateStatus("电视未提供安装或授权入口，请通过系统文件管理器安装新版") }
        }
    }
}
