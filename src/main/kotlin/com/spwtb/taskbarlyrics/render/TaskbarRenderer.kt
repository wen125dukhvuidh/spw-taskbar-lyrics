package com.spwtb.taskbarlyrics.render

import com.spwtb.taskbarlyrics.lyrics.CoverArt
import com.spwtb.taskbarlyrics.lyrics.LyricsStore
import com.sun.jna.Pointer
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/** `buildFrame` 的结果：这一帧的内容 + 它的身份（用来判断要不要起过渡）。 */
private data class FrameUpdate(val frame: LyricsFrame?, val key: String?)

/**
 * 任务栏歌词渲染器：**全部在插件进程内完成，不依赖任何外部程序**。
 *
 * 结构：
 * ```
 * 渲染线程（唯一线程，所有 Win32 调用都在它上面）
 *   ├─ 找任务栏 → 算子窗口求空闲横条 → 决定窗口几何
 *   ├─ 创建分层窗口（嵌进任务栏 / 独立置顶）
 *   └─ 循环：处理消息 → 取一帧歌词 → Java2D 绘制 → 推送到窗口
 * ```
 *
 * Win32 要求窗口的消息循环必须在创建它的线程上跑，所以这里用一个专用线程，
 * 也顺带把绘制与宿主线程隔开，避免拖慢播放器。
 *
 * 几何相关的设置一旦变化（锚点、宽度、缩放、偏移、任务栏换了）就重建窗口；
 * 只改颜色字号这类不影响窗口大小的设置，下一帧就会自然生效。
 */
class TaskbarRenderer(private val settingsProvider: () -> RenderSettings) {

    @Volatile
    private var running = false

    /**
     * 每次 [start] 自增。旧线程发现自己的 generation 过期就立刻退出。
     *
     * 只靠 `running` 不够：`stop()` 等 join 有 2.5 秒上限，超时后 [restart]
     * 会把 `running` 重新置真；旧线程若正卡在封面解码/JNA 调用里，醒来会
     * 看到 `running == true` 并继续跑，于是两条渲染线程、两个窗口。
     */
    @Volatile
    private var generation = 0

    private var thread: Thread? = null

    /** `timeBeginPeriod(1)` 是否已生效。只有渲染线程读写，不需要同步。 */
    private var hiResTimer = false

    @Volatile
    private var statusText: String = "未启动"

    @Volatile
    private var lastError: String? = null

    /** 上一次「显示中」的帧与它的身份。渲染线程专用，不需要同步。 */
    private var displayedFrame: LyricsFrame? = null
    private var displayedKey: String? = null

    /** 正在进行的行切换过渡。 */
    private var transition: LineTransition? = null

    /** 窗口刚建成、还没经历过第一次「有内容」的帧。见帧循环里的出处。 */
    private var introPending = false

    /**
     * 整块歌词区的显隐过渡（暂停淡出 / 播放淡入）。见 [SurfaceFade]。
     *
     * 它必须横跨「没有内容」的期间继续存在：**隐藏的那 300ms 里画布上还得有东西**，
     * 否则淡出第一帧就已经是全透明了，等于没过渡。
     *
     * 窗口「整块出现 / 整块消失」也走同一条曲线：暂停时 window 还活着，
     * 只是内容不见了；退出时连 window 本身都要退场。
     */
    private val surfaceFade = SurfaceFade()

    /** 上一次真正显示出来的内容，淡出期间靠它把最后一帧画完。 */
    private var lastContent: LyricsFrame? = null

    // ── 透明度留档（自检用） ─────────────────────────────────────────────
    // `UpdateLayeredWindow` 的 alpha 写进去就取不出来，PrintWindow 读到的又是
    // 合成**前**的内容（每像素恒 255），所以想证明淡入淡出真的发生过，
    // 只能在这里留一份记录。只在数值真的变了才写：静止时一次都不写，
    // 一次 300ms 的淡变最多也就几十次。
    private val traceLock = Any()
    private val traceAlpha = IntArray(TRACE_CAPACITY)
    private val traceAt = LongArray(TRACE_CAPACITY)
    private var traceCount = 0

    /**
     * @param force true 时无条件记一条。淡变进行中要逐帧留档：
     *     那样才能连**起点那一帧**一起读到，从而在探针里和理论曲线对表。
     *     平时去重是为了静止时不产生无意义的写入。
     */
    private fun recordAlpha(alpha: Int, nowNanos: Long, force: Boolean = false) {
        synchronized(traceLock) {
            val last = if (traceCount == 0) -1 else traceAlpha[(traceCount - 1) % TRACE_CAPACITY]
            if (force || last != alpha) {
                val slot = traceCount % TRACE_CAPACITY
                traceAlpha[slot] = alpha
                traceAt[slot] = nowNanos
                traceCount++
            }
        }
    }

    /** 自检/探针用：最近记录的窗口整体透明度变化，按时间升序。 */
    fun alphaTrace(): List<AlphaSample> = synchronized(traceLock) {
        val available = minOf(traceCount, TRACE_CAPACITY)
        val out = ArrayList<AlphaSample>(available)
        for (i in 0 until available) {
            // 已写满时，下一个要被覆盖的位置就是最老的那条
            val slot = if (traceCount <= TRACE_CAPACITY) i else (traceCount + i) % TRACE_CAPACITY
            out.add(AlphaSample(traceAt[slot], traceAlpha[slot]))
        }
        out
    }

    /** 见 [alphaTrace]。时刻取自 `System.nanoTime()`，只用于求差。 */
    data class AlphaSample(val nanos: Long, val alpha: Int)

    /**
     * 帧节拍：用条件变量而不是 `Thread.sleep`，这样歌词一变就能立刻醒过来画一帧，
     * 不用干等当前这一拍走完。
     *
     * 换行动画的**起始延迟**全靠它：原来最坏要等 33ms 才检测到新行，
     * 动画看起来就是「先顿一下再动」。
     */
    private val tickLock = ReentrantLock()
    private val tick: Condition = tickLock.newCondition()

    /**
     * 唤醒计数。用计数而不是布尔标志：布尔标志在「画完一帧」到「进入 waitUntil」
     * 之间到达的唤醒会被清掉，等于白丢一次；计数只要变了就不会丢。
     */
    private var wakeCounter = 0L

    @Synchronized
    fun start() {
        if (running) {
            return
        }
        // 不在调用线程上碰 Win32：它的静态初始化要解压并加载 JNA 原生库
        // （本机实测冷启动 196ms），而 start() 可能是宿主的 UI 线程调用的。
        // 检查挪到 loop() 的第一行，也就是渲染线程自己身上。
        running = true
        lastError = null
        val gen = ++generation
        thread = Thread({ loop(gen) }, "spw-taskbar-lyrics-render").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        // 让旧线程的 generation 立即过期：即使之后 start() 又把 running 置真，
        // 旧线程也不会跟着一起复活。
        generation++
        // 叫醒可能正卡在 awaitNanos 上的渲染线程，别让它干等当前这一拍
        tickLock.withLock { tick.signalAll() }
        val t = thread
        thread = null
        if (t != null && t != Thread.currentThread()) {
            try {
                // 这个 join 有可能发生在宿主 UI 线程上（停用插件），所以
                // 任何"重启"类的入口都不能在 UI 线程上直接调它——
                // 见 PluginRuntime.restart()。超时值本身要保持够长：
                // 它得容得下退出淡出，见 STOP_TIMEOUT_MS 的说明。
                t.join(STOP_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * 系统时钟粒度（`timeBeginPeriod(1)`）的开关。
     *
     * 这是个**整机**设置：一旦生效，全系统的定时器粒度从默认 15.6ms 变成 1ms，
     * 功耗上去、别的程序的 sleep 也变密。所以只在画面上真有东西动时持有，
     * 一旦完全隐藏（比如刚启动还没开始播放）就立刻还回去。
     */
    private fun setHiResTimer(on: Boolean) {
        if (on == hiResTimer) {
            return
        }
        hiResTimer = on
        if (on) {
            Win32.beginHighResolutionTimer()
        } else {
            Win32.endHighResolutionTimer()
        }
    }

    fun restart() {
        stop()
        start()
    }

    fun status(): String = statusText

    fun error(): String? = lastError

    /**
     * 歌词或播放状态刚变过，让渲染线程立刻画一帧。
     *
     * 只提前唤醒，**不**改变帧预算，所以不会把帧率拉上去。
     * 由播放扩展点在 `onLyricsLineUpdated` / `onSeekTo` / 播放状态变化时调用。
     */
    fun wake() {
        tickLock.withLock {
            wakeCounter++
            tick.signalAll()
        }
    }

    // ── 渲染线程 ────────────────────────────────────────────────────────

    private fun loop(gen: Int) {
        // ── 原生接口检查 ─────────────────────────────────────────────────
        // JNA 原生库在这里（渲染线程）才第一次加载，见 start() 的说明。
        if (!Win32.isAvailable()) {
            lastError = "原生接口不可用：${Win32.loadError()}"
            statusText = lastError!!
            return
        }

        // ── 启动让路 ─────────────────────────────────────────────────────
        // 宿主刚打开那几秒正在忙着加载类、读曲库、建界面。我们这边的预热
        // （字体子系统 + 首次 Java2D 绘制，本机实测 636ms）会跟它抢同一个
        // JIT 编译线程池和 CPU，表现就是"每次打开 SFW 都卡一阵，过一会才好"。
        // 两件事缓解它：
        //   1) 先让出一小段时间，等宿主把首屏交出来再干活；
        //   2) 预热期间把自己降到最低优先级，宿主有活干时我们让路。
        // 代价：歌词比之前晚约 INIT_GRACE_MS 出现。想更早看到就调小这个常量。
        Thread.currentThread().priority = Thread.MIN_PRIORITY
        var yielding = INIT_GRACE_MS
        while (active(gen) && yielding > 0L) {
            // 分小片睡：stop() 只需要等最多 50ms，而不是整段宽限期
            Thread.sleep(minOf(50L, yielding))
            yielding -= 50L
        }
        if (!active(gen)) {
            return
        }

        val painter = LyricsPainter()
        // 把字体冷启动那几百毫秒（本机实测 636ms）在建窗**之前**付掉。
        // 落在建窗之后的话它正好卡在第一帧与第二帧中间，淡入会被整段吞掉——
        // 见 LyricsPainter.preload。
        LyricsPainter.preload(settingsProvider())
        // 预热付完了，恢复正常优先级：后面每一帧都要跟动画的时间线对齐，不能再让路
        Thread.currentThread().priority = Thread.NORM_PRIORITY
        var window: TaskbarLayerWindow? = null
        var image: BufferedImage? = null
        // 几何相关的设置快照。变了才去动窗口；像字号、颜色这类下一帧自然生效。
        var geometryKey = ""
        // 上一次扫描任务栏的时刻：任务栏改动不必逐帧去查，但也不能不查
        var lastScanAt = 0L
        // 上一轮窗口用过的 dpi 缩放。收尾淡出时可能还没拿到新的任务栏信息，
        // 但旧画布是按旧 dpi 画的，必须用旧值
        var lastDpiScale = 1.0
        // 系统时钟粒度不在这里压——它是整机生效的，只在画面上真有东西动时才持有，
        // 见 setHiResTimer 与帧循环里的调用点。
        // 刷新率只查一次：换显示器/改刷新率要重启插件才生效，不值得每帧去问
        val refreshHz = Win32.displayRefreshHz()
        try {
            while (active(gen)) {
                val settings = settingsProvider()

                if (!settings.enabled) {
                    val stale = window
                    if (stale != null) {
                        // 在设置里把插件关掉：画面本来是看得见的，一样要淡出去，
                        // 不能因为它走的是这条分支就差别对待
                        dismissWindow(
                            stale, image, painter, lastDpiScale, settings, refreshHz,
                            animate = stale.isAlive()
                        )
                        window = null
                    }
                    statusText = "已在设置中关闭"
                    sleep(500, gen)
                    continue
                }

                val taskbars = Win32.findTaskbars()
                if (taskbars.isEmpty()) {
                    statusText = "找不到任务栏"
                    sleep(1000, gen)
                    continue
                }
                // 目前只处理主任务栏；多屏支持是后续工作
                val taskbar = taskbars[0]
                val handle = Pointer.nativeValue(taskbar.hwnd)
                val taskbarHeight = taskbar.height()
                val dpiScale = taskbar.dpi / 96.0
                lastDpiScale = dpiScale

                // 回到外层说明刚换过任务栏（Explorer 重启之类），
                // 上一轮占住的那块空闲区未必还在，重新评估一次。
                slotChooser.reset()

                val initial = Geometry.of(taskbar, settings, slotChooser)
                if (initial == null) {
                    statusText = "任务栏上没有足够宽的空闲区（需要 ${MIN_SLOT_WIDTH}px）"
                    sleep(1000, gen)
                    continue
                }

                // 唯一需要真正重建窗口的情况：父窗口变了（嵌入 ↔ 浮动条）。
                // 父窗口是 CreateWindowEx 时定死的，改不了。
                val existing = window
                if (existing == null || existing.embed() != settings.embed) {
                    if (existing != null) {
                        dismissWindow(
                            existing, image, painter, dpiScale, settings, refreshHz,
                            animate = Win32.isAlive(taskbar.hwnd) && existing.isAlive()
                        )
                    }
                    window = null
                    val created = try {
                        TaskbarLayerWindow(
                            taskbar.hwnd, initial.x, initial.y,
                            initial.width, initial.height, settings.embed
                        )
                    } catch (t: Throwable) {
                        statusText = "创建窗口失败：${t.message}"
                        lastError = t.toString()
                        null
                    }
                    if (created == null) {
                        sleep(1500, gen)
                        continue
                    }
                    window = created
                    image = null
                    // 新窗口从「完全隐藏」起步：内容一出现，第一帧就从 0 开始用同一条
                    // 300ms 曲线淡进来。少了这一步，首帧就是满亮度——窗口啪一下弹出来。
                    surfaceFade.beginHidden()
                    introPending = true
                    // 旧画面不能沿用：这是另一个窗口，它自己还没显示过任何东西
                    lastContent = null
                }

                // 走到这里窗口一定已经在上面建好了（创建失败会 continue）
                val win = requireNotNull(window)
                val sized = ensureCanvas(image, initial.width, initial.height)
                if (sized == null) {
                    continue
                }
                image = sized
                // 显式写类型：局部变量后面会被非空的值重新赋值多次
                var canvas: BufferedImage = sized
                win.setBounds(initial.x, initial.y, initial.width, initial.height)

                geometryKey = settings.geometryKey(handle, taskbar.dpi, taskbarHeight)
                lastScanAt = System.currentTimeMillis()
                statusText = describeState(taskbar, initial, settings)

                // 帧时间线：记录**下一帧应该在什么时候画**，而不是"画完再睡多久"。
                // 后者会把每帧的绘制耗时累加进去，560ms 的动画实际会被拖成 700ms
                // 并且越拖越慢。0 表示"下一次立刻画"。
                var nextFrameAt = 0L

                while (active(gen) && win.isAlive() && Win32.isAlive(taskbar.hwnd)) {
                    val current = settingsProvider()
                    if (!current.enabled) {
                        break
                    }
                    // 父子关系变了，只能回去重建
                    if (current.embed != win.embed()) {
                        break
                    }

                    win.pumpMessages()

                    // 几何：设置一变就立刻重算（这里只是 Move/Resize，画面不中断），
                    // 另外每 SCAN_INTERVAL_MS 重扫一次任务栏——图标增减会改变空闲区。
                    val nowMs = System.currentTimeMillis()
                    val due = nowMs - lastScanAt >= SCAN_INTERVAL_MS
                    val key = current.geometryKey(handle, taskbar.dpi, taskbarHeight)
                    if (key != geometryKey || due) {
                        lastScanAt = nowMs
                        geometryKey = key
                        val g = Geometry.of(taskbar, current, slotChooser)
                        if (g == null) {
                            // 暂时没有空闲区：画面保持原样，等下次重扫就好，
                            // 绝不能把窗口拆了——那样一有通知弹出来歌词就闪一下
                            statusText = "任务栏上没有足够宽的空闲区（需要 ${MIN_SLOT_WIDTH}px）"
                        } else {
                            val resized = ensureCanvas(image, g.width, g.height)
                            if (resized == null) {
                                break
                            }
                            image = resized
                            canvas = resized
                            win.setBounds(g.x, g.y, g.width, g.height)
                            statusText = describeState(taskbar, g, current)
                        }
                    }

                    // ── 显隐过渡 ──────────────────────────────────────────
                    // 暂停（或这首歌没有歌词）时画面不是「咔」地一下消失。
                    // 照原版 `.lyrics-layer` 的做法：淡到透明并上移 2px，300ms；
                    // 恢复播放时从当前程度原路淡回来——关键是**内容要先还在画布上**，
                    // 否则淡出的第一帧就已经是全透明，等于没有过渡。
                    val nowNanos = System.nanoTime()
                    val update = buildFrame(current, dpiScale, taskbarHeight)
                    val frame = update.frame
                    surfaceFade.retarget(frame != null, nowNanos)
                    if (!current.animateAutoHide) {
                        surfaceFade.snapTo(frame != null)
                    }
                    val level = surfaceFade.level(nowNanos)
                    val surfaceAnimating = surfaceFade.animating(nowNanos)
                    val windowAlpha = (level * 255.0).roundToInt().coerceIn(0, 255)
                    val layoutScale = LyricsLayout.scale(dpiScale, current)
                    val fadeDy = if (level >= 1.0) 0.0 else SurfaceFade.offsetPx(level, layoutScale)

                    val active = advance(update, current)
                    if (frame != null) {
                        lastContent = frame
                        painter.paint(canvas, frame, current, dpiScale, active, fadeDy)
                    } else {
                        // 淡出期间继续画最后一帧；淡出走完就没什么可画的了
                        val tail = lastContent
                        if (tail != null) {
                            painter.paint(canvas, tail, current, dpiScale, null, fadeDy)
                        } else {
                            clearFrame(canvas)
                        }
                    }
                    if (surfaceFade.hidden(nowNanos)) {
                        // 内容放掉：封面位图没必要为了一个看不见的画面继续占着
                        lastContent = null
                    }

                    // 整体透明度交给分层窗口自己的 SourceConstantAlpha：由 DWM 合成，
                    // 不必为了淡出去重画任何像素
                    win.pushFrame(pixelsOf(canvas), windowAlpha)
                    recordAlpha(windowAlpha, nowNanos, surfaceAnimating)

                    if (introPending && frame != null) {
                        // 时间线的原点挪到"画面刚落地"这一刻。
                        // 第一帧的绘制本身是个未知数（冷启动、封面首次解码都可能几百毫秒），
                        // 而这一帧推上屏时是全透明的——那段时间不该从 300ms 里扣。
                        // 否则用户看到的还是"啪一下出现"，只是晚了几百毫秒。
                        introPending = false
                        surfaceFade.begin(level, 1.0, System.nanoTime())
                    }

                    // 过渡进行中按动画帧率走；完全看不见之后退回最省的节拍，
                    // 显示中但没有动画时走中间的省电节拍。
                    val animating = active != null || surfaceAnimating
                    // 有东西在动（或者画面还亮着）才占用整机 1ms 定时器。
                    // 完全隐藏时还回去：刚启动还没播放、以及暂停淡出结束之后，
                    // 都不该继续把全系统的定时器粒度按在 1000Hz。
                    setHiResTimer(animating || !surfaceFade.hidden(nowNanos))
                    val budget = when {
                        animating -> 1_000_000_000L / current.animationFrameRate.fps(refreshHz)
                        surfaceFade.hidden(nowNanos) -> HIDDEN_FRAME_NANOS
                        else -> IDLE_FRAME_NANOS
                    }
                    val woken = waitUntil(nextFrameAt, gen)
                    val now = System.nanoTime()
                    nextFrameAt = if (woken || nextFrameAt <= 0L) {
                        // 被歌词事件叫醒：从现在重新起一拍。
                        // 沿用旧时间线的话，这一拍会短于预算，动画头几帧就挤在一起了。
                        now + budget
                    } else if (now - nextFrameAt >= budget) {
                        // 落后超过一整拍（绘制太慢、或刚从长等待里回来）：
                        // 重新起一条时间线，不追赶——追赶会连续画两帧，反而更抖。
                        now + budget
                    } else {
                        nextFrameAt + budget
                    }
                }


                val stale: TaskbarLayerWindow? = window
                if (stale != null) {
                    // 退出内环只有四种理由，其中两种画面本来就看不见了，
                    // 淡出毫无意义（还会白等 300ms）：
                    //   · 任务栏句柄失效（Explorer 重启）——窗口的宿主都没了
                    //   · 窗口被外力销毁——已经不是我们的窗了
                    // 剩下两种（插件停用 / 换父子关系）看得见，就得淡。
                    val stillVisible = Win32.isAlive(taskbar.hwnd) && stale.isAlive()
                    dismissWindow(
                        stale, image, painter, dpiScale, settingsProvider(), refreshHz,
                        animate = stillVisible
                    )
                }
                window = null
                geometryKey = ""
                if (active(gen)) {
                    // 走到这里都是真正需要重建的场合：任务栏句柄失效、窗口被外力销毁、
                    // 或父子关系要换。给一点缓冲，别贴着 Explorer 的脸重建。
                    sleep(REBUILD_DELAY_MS, gen)
                }
            }
        } catch (t: Throwable) {
            lastError = t.stackTraceToString()
            statusText = "渲染线程异常：${t.message}"
            println("[TaskbarLyrics] 渲染线程异常：${t.stackTraceToString()}")
        } finally {
            window?.destroy()
            // 兜底归还：整机定时器粒度不能因为异常退出留在 1ms
            setHiResTimer(false)
        }
    }

    /**
     * 组装这一帧要画的内容；[FrameUpdate.frame] 为 null 表示「什么都不显示」。
     *
     * [windowHeight] 是窗口的实际像素高度（等于任务栏物理高度）。
     *
     * [FrameUpdate.key] 是「这一帧的身份」：同一首歌、同一行、同一句文本。
     * 只有它变了才需要起一次行切换过渡——原版也是这么判的
     * （`presentation.js` 的 `sameLyricsIdentity`：行号 + trackId + 文本都没变就走
     * `PROGRESS_PATCH`，不做动画）。
     */
    private fun buildFrame(s: RenderSettings, dpiScale: Double, windowHeight: Int): FrameUpdate {
        val song = LyricsStore.song ?: return FrameUpdate(null, null)
        val lines = LyricsStore.lines
        if (lines.isEmpty()) {
            return FrameUpdate(null, null)
        }
        if (s.autoHideWhenNoPlayback && !LyricsStore.playing) {
            return FrameUpdate(null, null)
        }

        val position = LyricsStore.estimatedPositionMs() + s.lyricOffsetMs
        // 选行逻辑抽在 LyricLineSelector 里，这样探针能把「解析 → 选行 → 绘制」
        // 这条真实链路整条跑通来验证，而不是只看解析结果。
        val selection = LyricLineSelector.pick(
            lines, position, s.secondLine, LyricsStore.hasTranslation
        ) ?: return FrameUpdate(null, null)
        val line = lines[selection.index]

        val cover = if (s.showCover) {
            // 用与绘制端完全相同的公式算目标像素，保证贴图是 1:1。
            // 这里多算错 1 像素，drawImage 就会多做一次隐式缩放，封面立刻发虚。
            val layout = LyricsLayout.scale(dpiScale, s)
            val boxHeight = LyricsLayout.boxHeight(windowHeight, layout)
            val target = LyricsLayout.coverSizePx(s, layout, boxHeight)
            val radius = LyricsLayout.coverRadiusPx(s, layout)
            // 同步解码：保证换歌后第一帧歌词和封面一起切换
            CoverArt.forSong(song.key, song.path, target, radius)
        } else {
            null
        }

        val frame = LyricsFrame(
            mainText = selection.mainText,
            secondText = selection.secondText,
            cover = cover,
            cells = line.cells,
            positionMs = position,
            lineStartMs = line.startMs,
            lineEndMs = line.endMs,
            secondKind = selection.secondKind
        )
        return FrameUpdate(frame, "${song.key}#${selection.index}#${selection.mainText}")
    }

    /**
     * 推进过渡状态机，返回这一帧该用的过渡（没有就返回 null）。
     *
     * 触发条件只有一条：**帧身份变了**。歌词进度推进、已唱高亮前移都不算，
     * 那些在原版里是 `PROGRESS_PATCH`，直接原地更新。
     */
    private fun advance(update: FrameUpdate, s: RenderSettings): LineTransition? {
        val now = System.nanoTime()
        val frame = update.frame
        if (frame == null) {
            // 没有歌词 / 暂停隐藏：连当前显示的帧一起清掉，下次出现时重新滚入
            displayedFrame = null
            displayedKey = null
            transition = null
            return null
        }

        val previous = displayedFrame
        val key = update.key
        if (key != displayedKey) {
            displayedKey = key
            transition = if (!s.animateLineChange) {
                null
            } else {
                val mode = when (frame.secondKind) {
                    // 原版 `translationMode = ShowLyricTranslation && HasTrackTranslation`
                    // 决定用哪种布局：有翻译（含「…」占位）就是翻译对，滚两行
                    SecondLineKind.TRANSLATION, SecondLineKind.PLACEHOLDER ->
                        TransitionMode.TRANSLATION_PAIR
                    SecondLineKind.NEXT -> TransitionMode.SINGLE
                }
                // 没有上一帧时给个空帧：等于「只滚入、不滚出」，效果和原版从
                // SEARCHING 场景滚进歌词一致
                LineTransition(mode, previous ?: LineTransition.emptyFrame())
            }
        }
        displayedFrame = frame

        val active = transition ?: return null
        if (active.finished(now)) {
            transition = null
            return null
        }
        return active
    }

    /**
     * 收窗口：**先把 [SurfaceFade] 跑到 0，再拆**。
     *
     * 这是「软件关闭时窗口直接消失」的根因所在。以前调用方一到 `destroy()`
     * 就当场把窗口拆了，中间根本不存在"过渡"这回事——暂停隐藏那套淡出
     * 只管「内容」，管不到「窗口本身」。所以这里把窗口的生命终点也接到
     * 同一条曲线上，启动、停用、退出 SPW 走的都是它。
     *
     * 要点：
     * - **阻塞式**。调用方就是渲染线程自己，等待用 [park] 而不是 [waitUntil]——
     *   [waitUntil] 的循环条件里带着 running 与 generation，退出时两者都已失效，
     *   它会立刻返回，等于一帧也不等。
     * - **帧的内容和透明度一起变**。只降 alpha 不上移，看着就是"原地变淡"，
     *   和原版 `.lyrics-layer` 的 `opacity + translateY` 组合不一样。
     * - **有硬上限** [EXIT_FADE_MAX_NANOS]。宿主可能在关窗流程里，
     *   插件没有资格把它拖住。
     *
     * @param animate false 时直接拆（画面本来就不存在的场合）
     */
    private fun dismissWindow(
        win: TaskbarLayerWindow,
        canvas: BufferedImage?,
        painter: LyricsPainter,
        dpiScale: Double,
        s: RenderSettings,
        refreshHz: Int,
        animate: Boolean
    ) {
        val content = lastContent
        if (!animate || canvas == null || content == null || !s.animateAutoHide) {
            // 没有画面可以在上面淡的内容（从来没显示过、或用户关了动画），
            // 谈淡出没有意义
            win.destroy()
            lastContent = null
            return
        }

        surfaceFade.retarget(false, System.nanoTime())
        val layoutScale = LyricsLayout.scale(dpiScale, s)
        val stepNanos = 1_000_000_000L / s.animationFrameRate.fps(refreshHz)
        val deadline = System.nanoTime() + EXIT_FADE_MAX_NANOS
        // 淡出也是逐帧动画：粒度不压到 1ms 的话，16ms 的步长会被取整成 31ms，
        // 看着是"顿"的。这段只有几百毫秒，用完立刻还回去。
        setHiResTimer(true)
        while (!surfaceFade.hidden() && System.nanoTime() < deadline) {
            val level = surfaceFade.level(System.nanoTime())
            val alpha = (level * 255.0).roundToInt().coerceIn(0, 255)
            val dy = if (level >= 1.0) 0.0 else SurfaceFade.offsetPx(level, layoutScale)
            painter.paint(canvas, content, s, dpiScale, null, dy)
            win.pushFrame(pixelsOf(canvas), alpha)
            // 拆除期间也把消息泵着：系统随时可能发 WM_DESTROY 之类的过来
            win.pumpMessages()
            recordAlpha(alpha, System.nanoTime(), force = true)
            park(stepNanos)
        }
        // 落到 0 之后再补一帧，保证屏幕上的实际状态是"完全没了"，
        // 而不是停在一个肉眼看不出但也不彻底的中间值上
        clearFrame(canvas)
        win.pushFrame(pixelsOf(canvas), 0)
        recordAlpha(0, System.nanoTime(), force = true)
        win.destroy()
        lastContent = null
        // 内容已经彻底消失，整机定时器粒度还回去
        setHiResTimer(false)
    }

    /**
     * 纯等待，不看 running / generation。
     *
     * 退出淡出期间这两者都已失效，[waitUntil] 会瞬间返回——
     * 那样整个淡出会在一个循环里把所有帧挤完，屏幕上一帧都看不到。
     */
    private fun park(delayNanos: Long) {
        if (delayNanos <= 0) {
            return
        }
        val millis = delayNanos / 1_000_000L
        val extra = (delayNanos % 1_000_000L).toInt()
        try {
            Thread.sleep(millis, extra)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun clearFrame(image: BufferedImage) {
        val g: Graphics2D = image.createGraphics()
        try {
            g.composite = AlphaComposite.Src
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, image.width, image.height)
        } finally {
            g.dispose()
        }
    }

    private fun pixelsOf(image: BufferedImage): IntArray =
        (image.raster.dataBuffer as DataBufferInt).data

    /** 空闲区选择器，带迟滞；见 [SlotChooser]。 */
    private val slotChooser = SlotChooser(MIN_SLOT_WIDTH)

    /**
     * 窗口在这一帧该待的位置（屏幕坐标 + 物理像素）。
     *
     * 抽成一个量是因为它**每帧都可能变**：任务栏上的图标增减、用户拖偏移量、
     * 改宽度占比都会让它变。以前的做法是变了就把窗口拆了重建，代价是几百毫秒黑屏。
     */
    private data class Geometry(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val slot: Win32.Slot
    ) {
        companion object {
            fun of(
                taskbar: Win32.Taskbar, s: RenderSettings, chooser: SlotChooser
            ): Geometry? {
                val slots = Win32.freeSlots(taskbar)
                val slot = chooser.choose(slots, s.anchor, taskbar) ?: return null
                val scale = taskbar.scale()
                // 宽度：固定逻辑像素优先（对齐原版的 WindowWidth），否则按空闲区百分比
                val width = if (s.fixedWidthDp > 0) {
                    (s.fixedWidthDp * scale).toInt().coerceIn(MIN_WINDOW_WIDTH, slot.width)
                } else {
                    (slot.width * s.widthPercent / 100.0).toInt().coerceAtLeast(MIN_WINDOW_WIDTH)
                }
                val height = taskbar.height()
                val x = slot.start + (slot.width - width) / 2 + (s.xOffset * scale).toInt()
                val y = taskbar.top + (s.yOffset * scale).toInt()
                return Geometry(x, y, width, height, slot)
            }
        }
    }

    /** 画布尺寸跟着窗口走；没变就复用，不要在每一帧新建。 */
    private fun ensureCanvas(
        image: BufferedImage?, width: Int, height: Int
    ): BufferedImage? {
        if (image != null && image.width == width && image.height == height) {
            return image
        }
        if (width <= 0 || height <= 0) {
            return image
        }
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
    }

    private fun describeState(
        taskbar: Win32.Taskbar, g: Geometry, s: RenderSettings
    ): String {
        val mode = if (s.embed) "嵌入任务栏" else "浮动条"
        val origin = LyricsStore.lyricsOrigin.name.lowercase()
        return "$mode @ x=${g.slot.start} 段宽=${g.slot.width} 窗口=${g.width}×${g.height} " +
            "dpi=${taskbar.dpi} 歌词来源=$origin"
    }

    /**
     * 等到 [targetNanos]；返回 true 表示是被 [wake] 提前叫醒的。
     *
     * 用条件变量的 `awaitNanos` 而不是 `Thread.sleep`，有两个好处：
     * 1. 歌词一变就能立刻醒（换行动画的起始延迟从最坏 33ms 降到接近 0）；
     * 2. `stop()` 不用干等当前这一拍走完。
     */
    private fun waitUntil(targetNanos: Long, gen: Int): Boolean = tickLock.withLock {
            val start = wakeCounter
            var woken = false
            while (active(gen)) {
                val remaining = targetNanos - System.nanoTime()
                if (remaining <= 0) {
                    break
                }
                if (wakeCounter != start) {
                    woken = true
                    break
                }
                if (remaining > SPIN_WINDOW_NANOS) {
                    try {
                        tick.awaitNanos(remaining - SPIN_WINDOW_NANOS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                } else {
                    // 最后一段自旋收尾。等待唤醒的实际时刻有 ±1ms 的浮动，
                    // 用自旋把帧间隔钉死：实测抖动从 1.31ms 降到 0.65ms。
                    // 只在滚动动画的 560ms 里烧这点 CPU，静止时是 33ms 一拍，可忽略。
                    Thread.onSpinWait()
                }
            }
            woken
        }

    private fun sleep(ms: Long, gen: Int) {
        waitUntil(System.nanoTime() + ms * 1_000_000L, gen)
    }

    /** 当前线程是否还是「现在这一代」的渲染线程。 */
    private fun active(gen: Int): Boolean = running && gen == generation

    private companion object {
        /**
         * 静止时的节拍：30fps。
         *
         * 没有滚动动画要播时，画面里动的只有逐字扫描高亮（约 100px/s，
         * 30fps 下每帧走 3px，看不出台阶），没必要跟着刷新率烧 CPU——
         * 每帧都要 `UpdateLayeredWindow`，是会让 DWM 一直忙的。
         */
        const val IDLE_FRAME_NANOS = 1_000_000_000L / 30

        /**
         * 完全隐藏之后（淡出已走完）的节拍：10fps。
         *
         * 这时候画面是空的，继续画只是白烧 CPU；但循环本身不能停——
         * 消息泵还要跑，而且恢复播放时要在下一拍就发现。
         * 歌词事件会直接 [wake] 渲染线程，所以恢复不会因此慢半拍。
         */
        const val HIDDEN_FRAME_NANOS = 1_000_000_000L / 10

        /** 帧末尾的自旋窗口，1ms。见 [waitUntil]。 */
        const val SPIN_WINDOW_NANOS = 1_000_000L

        /**
         * 启动时主动让给宿主的时间，毫秒。
         *
         * 宿主刚打开那几秒自己也在抢 CPU（类加载、读曲库、首屏）。我们这边的预热
         * 是字体子系统 + 首次 Java2D 绘制，本机实测 636ms，落在这一刻正好跟它撞车，
         * 结果就是"每次打开 SFW 都卡一阵，过一会才好"。先让出这段时间再预热。
         *
         * 代价：歌词比之前晚这么久出现。嫌慢就调小（0 = 完全不让，回到旧行为）。
         */
        const val INIT_GRACE_MS = 1200L

        /**
         * `stop()` 等待渲染线程收摊的上限。
         *
         * 必须容得下「最长的一次外环等待 500ms」+「退出淡出 [EXIT_FADE_MAX_NANOS]」，
         * 否则淡出会被从中间掐断，画面就又变回一刀切了。
         */
        const val STOP_TIMEOUT_MS = 2500L

        /**
         * 退出淡出的硬上限。
         *
         * 宿主可能在关窗流程里（SPW 正在退出），插件没有资格把它拖住：
         * 正常淡出是 300ms + 一两帧余量，这里给到两倍多，够用了。
         */
        const val EXIT_FADE_MAX_NANOS = 700_000_000L

        /** 透明度留档的环形容量。够装下一次完整的淡变再富余一倍。 */
        const val TRACE_CAPACITY = 128
        const val REBUILD_DELAY_MS = 400L

        /**
         * 重扫任务栏空闲区的间隔。
         *
         * 图标增减会让空闲段变化，得看一眼；但 `findTaskbars` + `freeSlots` 要枚举窗口，
         * 逐帧做太贵，500ms 足够（原版 WPF 也是绑 Rect 变化事件，不是逐帧算）。
         */
        const val SCAN_INTERVAL_MS = 500L

        /** 空闲区至少要这么宽才值得放歌词。 */
        const val MIN_SLOT_WIDTH = 140

        const val MIN_WINDOW_WIDTH = 120

    }
}
