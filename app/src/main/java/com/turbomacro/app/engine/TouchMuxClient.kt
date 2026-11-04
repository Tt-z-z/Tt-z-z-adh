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

    fun isMerging(): Boolean = merging.get()

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
            val (w, h) = displaySize()
            val resp = sendAndWaitLocked("ENGAGE $w $h", timeoutMs = 6000L) ?: run {
                lastError.set("engage_timeout")
                return false
            }
            if (resp.startsWith("OK MERGING") || resp.startsWith("OK ALREADY")) {
                merging.set(true)
                startHeartbeatLocked()
                lastError.set(null)
                return true
            }
            lastError.set(resp)
            Log.w(TAG, "ENGAGE failed: $resp")
            return false
        }
    }

    fun tap(x: Int, y: Int, holdMs: Int): Boolean {
        lock.withLock {
            if (!merging.get()) return false
            val resp = sendAndWaitLocked("TAP $x $y $holdMs", timeoutMs = 350L) ?: return false
            return resp.startsWith("OK")
        }
    }

    fun release() {
        lock.withLock {
            stopHeartbeatLocked()
            try {
                sendAndWaitLocked("RELEASE", timeoutMs = 800L)
            } catch (_: Throwable) {
            }
            merging.set(false)
            destroyProcessLocked()
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
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
            val remote = method.invoke(null, arrayOf(bin.absolutePath), null, null)
                ?: return false.also { lastError.set("newProcess_null") }

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
            processError.set(null)
            remoteProcess = remote

            val readyLine = readLineLimited(5000L)
            if (readyLine == null) {
                val detail = readErrorSnapshot()
                lastError.set(detail ?: "start:null")
            }
            if (readyLine == null || !readyLine.startsWith("OK READY")) {
                lastError.set(readErrorSnapshot() ?: readyLine ?: "helper_no_ready")
                destroyProcessLocked()
                return false
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "startProcess failed", e)
            lastError.set("start:${e.message}")
            destroyProcessLocked()
            false
        }
    }

    private fun readErrorSnapshot(): String? {
        val r = errorReader ?: return null
        return try {
            val parts = mutableListOf<String>()
            while (r.ready() && parts.size < 4) {
                r.readLine()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
            }
            parts.joinToString(" | ").takeIf { it.isNotBlank() }
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
            lastError.set("io:${e.message}")
            merging.set(false)
            null
        }
    }

    private fun readLineLimited(timeoutMs: Long): String? {
        val r = reader ?: return null
        val future = ioExecutor.submit<String?> { r.readLine() }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            future.cancel(true)
            null
        }
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
                    merging.set(false)
                }
            } finally {
                lock.unlock()
            }
        }, 500L, 800L, TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeatLocked() {
        heartbeat?.cancel(true)
        heartbeat = null
    }

    private fun destroyProcessLocked() {
        stopHeartbeatLocked()
        try {
            writer?.write("QUIT\n")
            writer?.flush()
        } catch (_: Throwable) {
        }
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        writer = null
        reader = null
        errorReader = null
        val remote = remoteProcess
        remoteProcess = null
        merging.set(false)
        if (remote != null) {
            try {
                val destroy = remote.javaClass.methods.firstOrNull {
                    it.name == "destroy" && it.parameterTypes.isEmpty()
                }
                destroy?.invoke(remote)
            } catch (_: Throwable) {
            }
        }
    }
}
