package com.spwtb.taskbarlyrics.lyrics

import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** 文件读取只在后台执行；空结果短暂缓存，避免一次读盘失败令封面永久消失。 */
internal class AsyncCoverSource(
    private val decode: (String) -> BufferedImage?,
    private val clock: () -> Long = System::nanoTime
) {
    private data class SourceEntry(val image: BufferedImage?, val retryAt: Long)
    private val lock = Any()
    private val entries = object : LinkedHashMap<String, SourceEntry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SourceEntry>) = size > 2
    }
    private val pending = HashSet<String>()
    private var generation = 0L
    private var enabled = true
    private var onReady: () -> Unit = {}
    private fun newExecutor() = Executors.newSingleThreadExecutor { task ->
        Thread(task, "spw-taskbar-cover-io").apply { isDaemon = true }
    }
    private var executor = newExecutor()

    fun start(onReady: () -> Unit) = synchronized(lock) {
        this.onReady = onReady
        enabled = true
        if (executor.isShutdown) executor = newExecutor()
    }

    fun get(path: String): BufferedImage? = synchronized(lock) {
        if (!enabled) return null
        entries[path]?.let {
            if (it.image != null || clock() < it.retryAt) return it.image
        }
        // 限制快速连续切歌时积压的读取任务数量。
        if (path in pending || pending.size >= 8) return null
        pending.add(path)
        val gen = generation
        try {
            executor.execute {
                val image = runCatching { decode(path) }.getOrNull()
                val notify = synchronized(lock) {
                    // 停用前的任务即使不响应中断，也不能污染重新启用后的缓存。
                    if (gen != generation || !enabled) return@execute
                    pending.remove(path)
                    entries[path] = SourceEntry(image, clock() + RETRY_NANOS)
                    onReady
                }
                notify()
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(path)
        }
        null
    }

    fun clear() = synchronized(lock) {
        enabled = false
        generation++
        entries.clear()
        pending.clear()
        onReady = {}
        executor.shutdownNow()
        Unit
    }

    private companion object {
        const val RETRY_NANOS = 5_000_000_000L
    }
}
