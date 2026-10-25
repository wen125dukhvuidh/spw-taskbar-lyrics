import com.spwtb.taskbarlyrics.render.ControlPanelMetrics;
import com.spwtb.taskbarlyrics.render.ControlPanelMetrics.Hit;
import com.spwtb.taskbarlyrics.render.ControlPanelMetrics.Rects;
import com.spwtb.taskbarlyrics.render.ControlPanelState;
import com.spwtb.taskbarlyrics.render.ControlPanelWindow;
import com.spwtb.taskbarlyrics.render.PlaybackActions;
import com.spwtb.taskbarlyrics.render.Win32;

import com.sun.jna.Pointer;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 控制面板的**交互**验证：拖进度条、hover 不被冲掉、关闭后窗口真的没了。
 *
 * 用 `SendMessageW` 投递，是因为它同步、可复现：`SendMessage` 直接调用窗口过程，
 * 不进消息队列，所以断言可以在 send 之后立刻做，不用 pump、不用真的动鼠标。
 *
 * 三个失败各对应一个已经修掉的真 bug：
 *
 * 1. **拖进度条没反应**：按下后不 `SetCapture`，鼠标一移出那 14px 高的条子
 *    就再也收不到 `WM_MOUSEMOVE`/`WM_LBUTTONUP`——上层永远等不到 `onRelease`，
 *    拖动状态永久卡住，进度条看着就是「死的」。这里用 `GetCapture()` 把
 *    「按下即抓」钉死，再用**越界坐标**的 move+up 验证拖出窗口仍然跟手。
 * 2. **hover/pressed 被每帧冲掉**：渲染循环每帧拿全新的 `ControlPanelState`
 *    调 `update()`，覆盖掉鼠标状态，按下的视觉反馈只剩一帧。
 * 3. **关不掉**：`SetForegroundWindow` 受系统前台锁约束，失败是常态。收不到
 *    `WM_ACTIVATE(WA_INACTIVE)` 就永远没有「失焦」这条路，面板会一直挂在屏幕上。
 *    兜底是 `WM_MOUSELEAVE`。
 *
 * 用法：PanelInteractionProbe
 */
public class PanelInteractionProbe {

    static int passed = 0;
    static int failed = 0;

    /** 记录下来的播放动作。 */
    static final List<String> calls = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (!Win32.isAvailable()) {
            System.out.println("跳过：原生接口不可用 —— " + Win32.loadError());
            return;
        }

        PlaybackActions actions = new PlaybackActions() {
            public void play() { calls.add("play"); }
            public void pause() { calls.add("pause"); }
            public void previous() { calls.add("previous"); }
            public void next() { calls.add("next"); }
            public void seekTo(long positionMs) { calls.add("seekTo:" + positionMs); }
        };

        double scale = 1.0;
        Rects r = new Rects(scale);
        ControlPanelWindow panel = new ControlPanelWindow(actions);

        int before = countPanelWindows();

        // 摆在屏幕左下角上方一点，别压住任务栏，也别跑出工作区
        Rectangle2D work = new Rectangle2D.Double(0, 0, 1920, 1040);
        Rectangle2D anchor = new Rectangle2D.Double(200, 1040, 48, 48);
        panel.show(anchor, work, scale, false, System.nanoTime());
        Pointer hwnd = panel.windowHandle();
        check("面板窗口建起来了", hwnd != null && Win32.isAlive(hwnd), String.valueOf(hwnd));
        check("桌面上多了一个面板窗口", countPanelWindows() == before + 1,
                before + " → " + countPanelWindows());

        // 喂一帧有内容的状态：有歌、有总时长，否则进度条是禁用的
        panel.update(new ControlPanelState("T", "A", "", null, true, 0, 245000, true,
                false, Hit.NONE, null));

        System.out.println();
        System.out.println("== 1. 按下就该抓住鼠标（GetCapture）==");
        check("按之前没人抓着鼠标", Win32.U.GetCapture() == null, str(Win32.U.GetCapture()));

        int sx = (int) Math.round(r.getSeek().getCenterX());
        int sy = (int) Math.round(r.getSeek().getCenterY());
        send(hwnd, Win32.WM_LBUTTONDOWN, 0, sx, sy);
        check("按下进度条 → pressed = SEEK",
                panel.snapshot().getPressed() == Hit.SEEK, str(panel.snapshot().getPressed()));
        Pointer captured = Win32.U.GetCapture();
        check("按下进度条 → 抓住了鼠标（拖出窗口也不会丢）",
                captured != null && captured.equals(hwnd),
                "抓到的是 " + str(captured) + "，期望 " + str(hwnd));

        System.out.println();
        System.out.println("== 2. hover/pressed 不被每帧的 update 冲掉 ==");
        panel.update(new ControlPanelState("T", "A", "", null, true, 1000, 245000, true,
                false, Hit.NONE, null));
        check("update 之后 pressed 仍然是 SEEK",
                panel.snapshot().getPressed() == Hit.SEEK, str(panel.snapshot().getPressed()));
        check("update 之后位置确实被刷新了（说明 update 没被整个忽略）",
                panel.snapshot().getPositionMs() == 1000,
                String.valueOf(panel.snapshot().getPositionMs()));

        System.out.println();
        System.out.println("== 3. 拖到窗口外仍然跟手并正常松手 ==");
        // y = 9999 是**窗口外**的坐标。没抓鼠标的话这条消息根本不会来。
        int dragX = (int) Math.round(r.getSeek().getMinX() + r.getSeek().getWidth() * 0.75);
        send(hwnd, Win32.WM_MOUSEMOVE, 0, dragX, 9999);
        long dragged = panel.snapshot().getPositionMs();
        long expect = Math.round(245000 * 0.75);
        check("拖到窗口外，进度仍然跟上（≈ 3/4 处）",
                Math.abs(dragged - expect) <= 2000, dragged + " vs " + expect);
        check("拖出边界之后鼠标还被抓着",
                Win32.U.GetCapture() != null, str(Win32.U.GetCapture()));

        send(hwnd, Win32.WM_LBUTTONUP, 0, dragX, 9999);
        check("松手后放掉捕获", Win32.U.GetCapture() == null, str(Win32.U.GetCapture()));
        check("松手后 pressed 清空", panel.snapshot().getPressed() == null,
                str(panel.snapshot().getPressed()));
        boolean seeked = calls.stream().anyMatch(c -> c.startsWith("seekTo:"));
        check("松手真的发起了跳转（这是「进度条没反应」的根因）", seeked, String.valueOf(calls));
        if (seeked) {
            long target = Long.parseLong(
                    calls.stream().filter(c -> c.startsWith("seekTo:")).findFirst().get()
                            .substring("seekTo:".length()));
            check("跳转的位置就是拖到的位置", Math.abs(target - expect) <= 2000,
                    target + " vs " + expect);
        }

        System.out.println();
        System.out.println("== 4. 关不掉时的兜底：鼠标移出（等过激活宽限之后）==");
        // 探针里没有真实的前台窗口去抢焦点，所以这里本来就拿不到 WM_ACTIVATE。
        // 等过 400ms 的宽限再发 WM_MOUSELEAVE，面板必须自己开始退场。
        Thread.sleep(450);
        send(hwnd, Win32.WM_MOUSELEAVE, 0, 0, 0);
        check("鼠标移出后进入退场（不会永远挂在屏幕上）",
                !tickUntilSettled(panel), "仍在动画中");
        check("退场跑完窗口就没了", !panel.getVisible(), String.valueOf(panel.getVisible()));
        check("桌面上不再多出窗口", countPanelWindows() == before,
                before + " → " + countPanelWindows());
        check("退场之后句柄已失效",
                hwnd != null && !Win32.isAlive(hwnd), String.valueOf(hwnd));

        System.out.printf("%n结果：%d 通过 / %d 失败%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 一直喂 tick 直到它不再说自己在动；返回 true 表示「卡住没停」。 */
    static boolean tickUntilSettled(ControlPanelWindow panel) {
        long start = System.nanoTime();
        boolean animating = true;
        while (System.nanoTime() - start < 3_000_000_000L) {
            panel.update(new ControlPanelState("T", "A", "", null, true, 0, 245000, true,
                    false, Hit.NONE, null));
            animating = panel.tick(System.nanoTime());
            if (!animating) {
                return false;
            }
            try {
                Thread.sleep(8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return animating;
    }

    static void send(Pointer hwnd, int msg, long wParam, int x, int y) {
        Win32.U.SendMessageW(hwnd, msg, Pointer.createConstant(wParam),
                Pointer.createConstant(((long) (y & 0xFFFF) << 16) | (x & 0xFFFF)));
    }

    /** 桌面上属于我们的窗口数量（同一个窗口类）。 */
    static int countPanelWindows() {
        int n = 0;
        Pointer h = null;
        char[] cls = new char[64];
        while (true) {
            h = Win32.U.FindWindowExW(null, h,
                    new com.sun.jna.WString(Win32.LYRICS_WINDOW_CLASS), null);
            if (h == null) {
                break;
            }
            Win32.U.GetClassNameW(h, cls, cls.length);
            n++;
            if (n > 64) {
                break;
            }
        }
        return n;
    }

    static String str(Object o) {
        return o == null ? "null" : o.toString();
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + " —— " + detail);
        }
    }
}
