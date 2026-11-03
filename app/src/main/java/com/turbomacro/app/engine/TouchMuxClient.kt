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

/**
 * 常驻 TouchMux 进程客户端（经 Shizuku 以 shell 身份 exec libtouchmux.so）。
 *
 * 负责：ENGAGE 抢占真屏并合并多指、TAP 走同一虚拟设备的额外槽、PING 保活。
 */
class TouchMuxClient(private val appContext: Context) {

    companion object {
        private const val TAG = "TouchMuxClient"
        private const val LIB_NAME = "libtouchmux.so"
    }

    private val lock = Any()
    private val merging = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val lastError = AtomicReference<String?>(null)

    private var remoteProcess: Any? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "touchmux-io").apply { isDaemon = true }
    }
    private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "touchmux-hb").apply { isDaemon = true }
    }

    fun isMerging(): Boolean = merging.get()

    fun lastError(): String? = lastError.get()

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /** 启动 helper（若未启动）并 ENGAGE。需要用户短暂抬起所有手指。 */
    fun ensureMerging(): Boolean {
        synchronized(lock) {
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
        synchronized(lock) {
            if (!merging.get()) return false
            val resp = sendAndWaitLocked("TAP $x $y $holdMs", timeoutMs = 400L) ?: return false
            return resp.startsWith("OK")
        }
    }

    fun release() {
        synchronized(lock) {
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
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val direct = File(nativeDir, LIB_NAME)
        if (direct.exists() && direct.canRead()) return direct

        // 某些安装路径 / split APK
        val ai: ApplicationInfo = appContext.applicationInfo
        val candidates = listOfNotNull(
            ai.nativeLibraryDir,
            ai.sourceDir?.let { File(it).parent },
            ai.publicSourceDir?.let { File(it).parent }
        )
        for (c in candidates) {
            val f = File(c, LIB_NAME)
            if (f.exists()) return f
            val abi = File(c, "arm64-v8a/$LIB_NAME")
            if (abi.exists()) return abi
            val abi2 = File(c, "lib/arm64-v8a/$LIB_NAME")
            if (abi2.exists()) return abi2
        }
        return null
    }

    private fun startProcessLocked(): Boolean {
        destroyProcessLocked()
        val bin = findBinary()
        if (bin == null) {
            lastError.set("missing_libtouchmux")
            Log.e(TAG, "libtouchmux.so not found in nativeLibraryDir")
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

            val getInput = remote.javaClass.methods.first { it.name == "getInputStream" && it.parameterTypes.isEmpty() }
            val getOutput = remote.javaClass.methods.first { it.name == "getOutputStream" && it.parameterTypes.isEmpty() }
            val input = getInput.invoke(remote) as java.io.InputStream
            val output = getOutput.invoke(remote) as java.io.OutputStream

            reader = BufferedReader(InputStreamReader(input))
            writer = BufferedWriter(OutputStreamWriter(output))
            remoteProcess = remote

            val readyLine = readLineLimited(5000L)
            if (readyLine == null || !readyLine.startsWith("OK READY")) {
                lastError.set(readyLine ?: "helper_no_ready")
                destroyProcessLocked()
                return false
            }
            ready.set(true)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "startProcess failed", e)
            lastError.set("start:${e.message}")
            destroyProcessLocked()
            false
        }
    }

    private fun sendAndWaitLocked(cmd: String, timeoutMs: Long): String? {
        val w = writer ?: return null
        val r = reader ?: return null
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
            synchronized(lock) {
                if (!merging.get()) return@scheduleAtFixedRate
                val resp = sendAndWaitLocked("PING", timeoutMs = 500L)
                if (resp == null || !resp.startsWith("OK")) {
                    Log.w(TAG, "heartbeat lost: $resp")
                    merging.set(false)
                }
            }
        }, 400L, 700L, TimeUnit.MILLISECONDS)
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
        val remote = remoteProcess
        remoteProcess = null
        ready.set(false)
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
