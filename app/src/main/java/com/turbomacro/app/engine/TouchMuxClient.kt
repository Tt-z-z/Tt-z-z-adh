package com.turbomacro.app.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import android.view.WindowManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 常驻 TouchMux 进程客户端（经 Shizuku 以 shell 身份 exec libtouchmux.so）。
 *
 * ENGAGE 抢占真屏并合并多指；TAP 走同一虚拟设备额外槽；PING 保活（不与 TAP 死锁）。
 */
class TouchMuxClient(private val appContext: Context) {

    companion object {
        private const val TAG = "TouchMuxClient"
        private const val LIB_NAME = "libtouchmux.so"
        private const val SESSION_LIMIT_SECONDS = 30 * 60
    }

    private val lock = ReentrantLock()
    private val merging = AtomicBoolean(false)
    private val lastError = AtomicReference<String?>(null)

    private var remoteProcess: Any? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var errorReader: BufferedReader? = null
    private var processError: AtomicReference<String?> = AtomicReference(null)
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "touchmux-io").apply { isDaemon = true }
    }
    private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "touchmux-hb").apply { isDaemon = true }
    }

    @Volatile private var diagnosticStartedAt = 0L
    private var engagedGeometry: Triple<Int, Int, Int>? = null

    fun diagnosticRemainingSeconds(): Int {
        if (diagnosticStartedAt == 0L) return 0
        val remaining = SESSION_LIMIT_SECONDS * 1000L - (android.os.SystemClock.elapsedRealtime() - diagnosticStartedAt)
        return ((remaining.coerceAtLeast(0L) + 999L) / 1000L).toInt()
    }

    fun diagnosticStatus(): String = when {
        diagnosticStartedAt != 0L && diagnosticRemainingSeconds() == 0 -> "已到期"
        merging.get() -> "就绪"
        lastError() != null -> "失败"
        else -> "未就绪"
    }

    private fun trace(event: String) {
        Log.i(TAG, "t=${android.os.SystemClock.uptimeMillis()} $event")
    }

    fun isMerging(): Boolean = merging.get() && diagnosticRemainingSeconds() > 0

    fun lastError(): String? = lastError.get() ?: processError.get()

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /** 启动 helper（若未启动）并 ENGAGE。需要用户短暂抬起所有手指。 */
    fun ensureMerging(): Boolean {
        lock.withLock {
            if (!isShizukuReady()) {
                lastError.set("shizuku_not_ready")
                return false
            }
            if (merging.get() && remoteProcess != null) return true
            if (!startProcessLocked()) return false
            val geometry = displayGeometry()
            engagedGeometry = geometry
            val (w, h, rotation) = geometry
            val sideways = rotation == 1 || rotation == 3
            val naturalW = if (sideways) h else w
            val naturalH = if (sideways) w else h
            val resp = sendAndWaitLocked("ENGAGE $naturalW $naturalH", timeoutMs = 6000L) ?: run {
                merging.set(false)
                lastError.set("engage_timeout")
                destroyProcessLocked()
                return false
            }
            if (resp.startsWith("OK MERGING") || resp.startsWith("OK ALREADY")) {
                merging.set(true)
                trace("MERGING response=$resp remainingSeconds=${diagnosticRemainingSeconds()}")
                startHeartbeatLocked()
                lastError.set(null)
                return true
            }
            lastError.set(resp)
            Log.w(TAG, "ENGAGE failed: $resp")
            merging.set(false)
            destroyProcessLocked()
            return false
        }
    }

    private val cancelExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "touchmux-cancel").apply { isDaemon = true }
    }
    private val pendingCancels = java.util.concurrent.atomic.AtomicInteger(0)

    fun isCancelPending(): Boolean = pendingCancels.get() != 0

    fun cancelTapAsync() {
        pendingCancels.incrementAndGet()
        cancelExecutor.execute {
            try {
                lock.withLock {
                    if (merging.get()) {
                        val response = sendAndWaitLocked("CANCEL_TAP", 350L)
                        trace("CANCEL_ACK response=$response")
                        if (response != "OK CANCEL_TAP") destroyProcessLocked()
                    }
                }
            } finally {
                pendingCancels.decrementAndGet()
            }
        }
    }

    fun tap(x: Int, y: Int, holdMs: Int, allowed: () -> Boolean = { true }): Boolean {
        lock.withLock {
            if (!isMerging() || pendingCancels.get() != 0 || !allowed()) {
                trace("TAP_REJECT status=${diagnosticStatus()} pendingCancels=${pendingCancels.get()}")
                return false
            }
            // The overlay is commonly engaged in portrait and used after the game
            // rotates to landscape. Do not tear down the mux on rotation; the
            // helper uses natural panel axes and we transform this tap below.
            val geometry = displayGeometry()
            val (w, h, rotation) = geometry
            if (x !in 0 until w || y !in 0 until h) {
                lastError.set("target_out_of_bounds")
                return false
            }
            // Invert InputReader's viewport rotation: uinput uses natural panel axes.
            val (nativeX, nativeY) = when (rotation) {
                1 -> (h - 1 - y) to x
                2 -> (w - 1 - x) to (h - 1 - y)
                3 -> y to (w - 1 - x)
                else -> x to y
            }
            trace("TAP_REQUEST x=$x y=$y nativeX=$nativeX nativeY=$nativeY rotation=$rotation holdMs=$holdMs")
            val resp = sendAndWaitLocked("TAP $nativeX $nativeY $holdMs", timeoutMs = 350L)
            trace("TAP_ACK response=$resp")
            if (resp == null) return false
            if (resp.startsWith("OK")) return true
            lastError.set(resp)
            destroyProcessLocked()
            return false
        }
    }

    fun release() {
        lock.withLock {
            merging.set(false)
            // 终止 helper 释放 grab，不向可能已堵塞的管道同步写 RELEASE。
            destroyProcessLocked()
        }
    }

    private fun displayGeometry(): Triple<Int, Int, Int> {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), wm.defaultDisplay.rotation)
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, wm.defaultDisplay.rotation)
        }
    }

    private fun findBinary(): File? {
        // Prefer a writable copy we can chmod +x
        val cached = File(appContext.filesDir, LIB_NAME)
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val direct = File(nativeDir, LIB_NAME)

        val source = when {
            direct.exists() && direct.length() > 8_000L -> direct
            else -> {
                val ai: ApplicationInfo = appContext.applicationInfo
                val candidates = listOfNotNull(
                    ai.nativeLibraryDir,
                    ai.sourceDir?.let { File(it).parent },
                    ai.publicSourceDir?.let { File(it).parent }
                )
                var found: File? = null
                for (c in candidates) {
                    val f = File(c, LIB_NAME)
                    if (f.exists() && f.length() > 8_000L) {
                        found = f
                        break
                    }
                    val abi = File(c, "arm64-v8a/$LIB_NAME")
                    if (abi.exists() && abi.length() > 8_000L) {
                        found = abi
                        break
                    }
                }
                found
            }
        } ?: return null

        // 重要：不要把可执行文件放到 app 私有 filesDir 后再交给 Shizuku shell。
        // shell uid 通常无法穿过 /data/user/0/<package> 的目录权限，表现为 start:null。
        // 直接执行 nativeLibraryDir 中由系统解压、可被 shell 读取的副本。
        return source.apply {
            setReadable(true, false)
            setExecutable(true, false)
        }
    }

    private fun startProcessLocked(): Boolean {
        destroyProcessLocked()
        val bin = findBinary()
        if (bin == null) {
            lastError.set("missing_libtouchmux")
            Log.e(TAG, "libtouchmux.so not found")
            return false
        }
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            diagnosticStartedAt = android.os.SystemClock.elapsedRealtime()
            trace("HELPER_START watchdogMs=${SESSION_LIMIT_SECONDS * 1000L}")
            val remote = method.invoke(null, arrayOf(bin.absolutePath, "--diagnostic-seconds", SESSION_LIMIT_SECONDS.toString()), null, null)
                ?: return false.also { lastError.set("newProcess_null") }

            remoteProcess = remote
            lastError.set(null)
            processError.set(null)
            val getInput = remote.javaClass.methods.first {
                it.name == "getInputStream" && it.parameterTypes.isEmpty()
            }
            val getOutput = remote.javaClass.methods.first {
                it.name == "getOutputStream" && it.parameterTypes.isEmpty()
            }
            val input = getInput.invoke(remote) as java.io.InputStream
            val output = getOutput.invoke(remote) as java.io.OutputStream

            reader = BufferedReader(InputStreamReader(input))
            writer = BufferedWriter(OutputStreamWriter(output))
            val errorMethod = remote.javaClass.methods.firstOrNull {
                it.name == "getErrorStream" && it.parameterTypes.isEmpty()
            }
            errorReader = (errorMethod?.invoke(remote) as? java.io.InputStream)?.let {
                BufferedReader(InputStreamReader(it))
            }
            val readyLine = readLineLimited(5000L)
            if (readyLine == null || !readyLine.startsWith("OK READY")) {
                val detail = processError.get() ?: readErrorSnapshot()
                lastError.set(detail ?: readyLine ?: lastError.get() ?: "helper_no_ready")
                destroyProcessLocked()
                return false
            }
            true
        } catch (e: Throwable) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            Log.e(TAG, "startProcess failed", cause)
            lastError.set("start:${cause.message ?: cause.javaClass.simpleName}")
            destroyProcessLocked()
            false
        }
    }

    private fun readErrorSnapshot(): String? {
        val r = errorReader ?: return null
        return try {
            // ready 只保证下一字符可读，不能据此调用可能等待换行的 readLine。
            val chars = StringBuilder()
            var remaining = 2048
            while (remaining-- > 0 && r.ready()) {
                val ch = r.read()
                if (ch < 0) break
                chars.append(ch.toChar())
            }
            chars.toString().trim().takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun sendAndWaitLocked(cmd: String, timeoutMs: Long): String? {
        val w = writer ?: return null
        return try {
            w.write(cmd)
            w.write("\n")
            w.flush()
            readLineLimited(timeoutMs)
        } catch (e: Throwable) {
            Log.w(TAG, "send failed: ${e.message}")
            lastError.set("io:${e.message ?: e.javaClass.simpleName}")
            destroyProcessLocked()
            null
        }
    }

    private fun readLineLimited(timeoutMs: Long): String? {
        val r = reader ?: return null
        val future = ioExecutor.submit<String?> { r.readLine() }
        val response = try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            lastError.set("read:${e.javaClass.simpleName}")
            if (e is InterruptedException) Thread.currentThread().interrupt()
            future.cancel(true)
            null
        }
        if (response == null) {
            // 中断不保证解除 readLine；失效状态先发布，再终止进程解除管道读取。
            merging.set(false)
            processError.set(readErrorSnapshot())
            destroyProcessLocked()
        }
        return response
    }

    private fun startHeartbeatLocked() {
        stopHeartbeatLocked()
        heartbeat = scheduler.scheduleAtFixedRate({
            // 不与 TAP 死等：抢不到锁就跳过本轮
            if (!lock.tryLock()) return@scheduleAtFixedRate
            try {
                if (!merging.get()) return@scheduleAtFixedRate
                val resp = sendAndWaitLocked("PING", timeoutMs = 400L)
                if (resp == null || !resp.startsWith("OK")) {
                    Log.w(TAG, "heartbeat lost: $resp")
                    if (resp != null) lastError.set(resp)
                    destroyProcessLocked()
                }
            } finally {
                lock.unlock()
            }
        }, 500L, 800L, TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeatLocked() {
        heartbeat?.cancel(false)
        heartbeat = null
    }

    private fun destroyProcessLocked() {
        stopHeartbeatLocked()
        // 先礼貌请求 helper 释放 EVIOCGRAB/uinput；随后无论结果如何都销毁进程。
        // 这里不调用 sendAndWaitLocked，避免清理路径等待自身的 IO 任务。
        try {
            writer?.write("QUIT\n")
            writer?.flush()
        } catch (_: Throwable) {
        }
        val remote = remoteProcess
        remoteProcess = null
        merging.set(false)
        // destroy 必须先于 close：关闭 BufferedReader 可能等待仍在 readLine 的任务，
        // 而远端进程只有先退出才能可靠地产生 EOF，避免 release 死锁。
        if (remote != null) {
            try {
                remote.javaClass.methods.firstOrNull {
                    it.name == "destroy" && it.parameterTypes.isEmpty()
                }?.invoke(remote)
            } catch (_: Throwable) {
            }
        }
        try { writer?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { errorReader?.close() } catch (_: Throwable) {}
        writer = null
        reader = null
        errorReader = null
    }
}
