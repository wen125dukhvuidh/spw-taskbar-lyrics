import com.spwtb.taskbarlyrics.render.TaskbarLayerWindow;
import com.spwtb.taskbarlyrics.render.Win32;
import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.List;

/**
 * 点封面能不能真的被接住——这是「点封面弹控制面板」的入口，必须机械验。
 *
 * 验的是整条链路：Win32 消息进来 → 分发到实例 → 命中测试 → 回调。
 * 用 `SendMessageW` 直接投消息，不需要真的动鼠标，也不依赖屏幕亮着。
 *
 * 两条缺一不可：
 * 1. **命中区内的点击要被接住**（否则点了没反应）；
 * 2. **命中区外的点击必须穿透**（否则歌词窗口会吃掉任务栏上的点击，
 *    托盘、图标就点不动了——这是把 `WS_EX_TRANSPARENT` 去掉之后最大的风险）。
 *
 * 用法：CoverClickProbe
 */
public class CoverClickProbe {

    static int passed = 0;
    static int failed = 0;

    /** 命中区：窗口左上角 40×40 的一块（模拟封面）。 */
    static final int HIT_X = 10;
    static final int HIT_Y = 8;
    static final int HIT_W = 40;
    static final int HIT_H = 40;

    public static void main(String[] args) {
        List<Win32.Taskbar> bars = Win32.findTaskbars();
        if (bars.isEmpty()) {
            System.out.println("找不到任务栏，退出");
            return;
        }
        Win32.Taskbar bar = bars.get(0);
        System.out.println("  " + bar);

        // 建在任务栏的空闲区里，避免盖住已有元素
        List<Win32.Slot> slots = Win32.freeSlots(bar);
        Win32.Slot slot = null;
        for (Win32.Slot s : slots) {
            if (slot == null || s.width > slot.width) {
                slot = s;
            }
        }
        if (slot == null || slot.width < 120) {
            System.out.println("没有足够宽的空闲区，退出");
            return;
        }
        int x = slot.start + 8;
        int y = bar.top + 4;
        int w = Math.min(200, slot.width - 16);
        int h = Math.min(60, bar.height() - 8);
        System.out.printf("  窗口 (%d,%d) %dx%d%n", x, y, w, h);

        // clickable = true：去掉 WS_EX_TRANSPARENT，窗口开始收鼠标
        TaskbarLayerWindow win = new TaskbarLayerWindow(bar.hwnd, x, y, w, h, true, true);

        final List<String> clicks = new ArrayList<>();
        win.setMouseSink(new TaskbarLayerWindow.MouseSink() {
            @Override
            public boolean hitTest(int hx, int hy) {
                return hx >= HIT_X && hx < HIT_X + HIT_W && hy >= HIT_Y && hy < HIT_Y + HIT_H;
            }

            @Override
            public void onClick(int cx, int cy) {
                clicks.add(cx + "," + cy);
            }
        });

        try {
            // ── 1) 命中测试 ──────────────────────────────────────────────
            int inside = hitTest(win, bar, x, y, HIT_X + 5, HIT_Y + 5);
            int outside = hitTest(win, bar, x, y, HIT_X + HIT_W + 30, HIT_Y + 5);
            System.out.println("  命中区内 WM_NCHITTEST = " + inside + "（期望 1 = HTCLIENT）");
            System.out.println("  命中区外 WM_NCHITTEST = " + outside + "（期望 -1 = HTTRANSPARENT）");
            check("命中区内接住点击（HTCLIENT）", inside == Win32.HTCLIENT, "得到 " + inside);
            check("命中区外穿透给任务栏（HTTRANSPARENT）", outside == Win32.HTTRANSPARENT,
                    "得到 " + outside);

            // ── 2) 命中区内的点击要触发回调 ──────────────────────────────
            click(win, HIT_X + 5, HIT_Y + 5);
            win.pumpMessages();
            check("命中区内点击触发了 onClick", clicks.size() == 1,
                    "回调次数 " + clicks.size());
            if (clicks.size() == 1) {
                check("回调带上的是窗口内坐标", clicks.get(0).equals((HIT_X + 5) + "," + (HIT_Y + 5)),
                        "得到 " + clicks.get(0));
            }

            // ── 3) 命中区外的点击不该触发 ────────────────────────────────
            clicks.clear();
            click(win, HIT_X + HIT_W + 30, HIT_Y + 5);
            win.pumpMessages();
            check("命中区外的点击没有被接住（穿透了）", clicks.isEmpty(),
                    "不该有回调，实际 " + clicks.size());

            // ── 4) 按下后拖走再松手不算点击 ──────────────────────────────
            clicks.clear();
            press(win, HIT_X + 5, HIT_Y + 5);
            release(win, HIT_X + 35, HIT_Y + 30);
            win.pumpMessages();
            check("按下后拖走再松手不算点击", clicks.isEmpty(),
                    "不该有回调，实际 " + clicks.size());
        } finally {
            win.destroy();
        }

        System.out.println();
        System.out.printf("%s：%d 项通过，%d 项失败%n",
                failed == 0 ? "全部通过" : "存在失败", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 投一条 WM_NCHITTEST，lParam 是**屏幕**坐标。 */
    static int hitTest(TaskbarLayerWindow win, Win32.Taskbar bar, int wx, int wy, int px, int py) {
        long lParam = ((long) (wy + py) << 16) | ((wx + px) & 0xFFFF);
        Pointer r = Win32.U.SendMessageW(
                hwndOf(win), Win32.WM_NCHITTEST, Pointer.createConstant(0),
                Pointer.createConstant(lParam));
        return (int) Pointer.nativeValue(r);
    }

    static void click(TaskbarLayerWindow win, int px, int py) {
        press(win, px, py);
        release(win, px, py);
    }

    static void press(TaskbarLayerWindow win, int px, int py) {
        send(win, Win32.WM_LBUTTONDOWN, px, py);
    }

    static void release(TaskbarLayerWindow win, int px, int py) {
        send(win, Win32.WM_LBUTTONUP, px, py);
    }

    /** 鼠标消息的 lParam 是**客户区**坐标。 */
    static void send(TaskbarLayerWindow win, int msg, int px, int py) {
        long lParam = ((long) py << 16) | (px & 0xFFFF);
        Win32.U.SendMessageW(hwndOf(win), msg, Pointer.createConstant(1),
                Pointer.createConstant(lParam));
    }

    static Pointer hwndOf(TaskbarLayerWindow win) {
        // 反射取私有字段只为探针：生产代码不该这么拿
        try {
            java.lang.reflect.Field f = TaskbarLayerWindow.class.getDeclaredField("hwnd");
            f.setAccessible(true);
            return (Pointer) f.get(win);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
