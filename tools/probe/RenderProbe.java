import com.spwtb.taskbarlyrics.lyrics.CoverArt;
import com.spwtb.taskbarlyrics.lyrics.LyricCell;
import com.spwtb.taskbarlyrics.render.LyricsFrame;
import com.spwtb.taskbarlyrics.render.LyricsPainter;
import com.spwtb.taskbarlyrics.render.RenderSettings;
import com.spwtb.taskbarlyrics.render.TaskbarLayerWindow;
import com.spwtb.taskbarlyrics.render.Win32;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 渲染栈的端到端自检。
 *
 * 用的**不是探针代码，而是插件真正编译出来的那几个类**：
 * Win32 / TaskbarLayerWindow / RenderSettings / LyricsPainter / CoverArt。
 * 这样跑通就等于「插件在宿主进程里能显示」。
 *
 * 建议配合 `--limit-modules` 把模块裁成与 SPW 自带运行时完全一致的那 19 个，
 * 模拟真实宿主环境。
 */
public class RenderProbe {

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        int holdSec = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        String musicRoot = args.length > 2 ? args[2] : "D:/Music";

        System.out.println("=== 0) 运行环境自检 ===");
        System.out.println("  classpath = " + System.getProperty("java.class.path"));
        try {
            Class.forName("kotlin.enums.EnumEntriesKt");
            System.out.println("  kotlin-stdlib: 可加载");
        } catch (Throwable t) {
            System.out.println("  kotlin-stdlib: 不可加载 -> " + t);
        }

        System.out.println();
        System.out.println("=== 1) 原生层可用性 ===");
        System.out.println("  Win32.isAvailable = " + Win32.isAvailable()
                + (Win32.isAvailable() ? "" : "  " + Win32.loadError()));
        if (!Win32.isAvailable()) {
            return;
        }

        System.out.println();
        System.out.println("=== 2) 任务栏与空闲区 ===");
        List<Win32.Taskbar> bars = Win32.findTaskbars();
        for (Win32.Taskbar b : bars) {
            System.out.println("  " + b);
        }
        if (bars.isEmpty()) {
            System.out.println("  找不到任务栏，退出");
            return;
        }
        Win32.Taskbar bar = bars.get(0);

        List<Win32.Slot> slots = Win32.freeSlots(bar).stream()
                .filter(s -> s.width >= 140)
                .collect(Collectors.toList());
        for (Win32.Slot s : slots) {
            System.out.println("  空闲段: " + s);
        }
        if (slots.isEmpty()) {
            System.out.println("  没有可用空闲区，退出");
            return;
        }
        Win32.Slot slot = slots.stream().max(Comparator.comparingInt(s -> s.width)).get();

        double scale = bar.scale();
        int margin = (int) Math.round(6 * scale);
        int w = slot.width - margin * 2;
        int h = bar.height();
        int x = slot.start + margin;
        int y = bar.top;
        System.out.printf("  选定: %s；窗口 x=%d y=%d w=%d h=%d scale=%.2f%n", slot, x, y, w, h, scale);

        System.out.println();
        System.out.println("=== 3) 用插件真实类创建分层窗口 ===");
        TaskbarLayerWindow win = new TaskbarLayerWindow(bar.hwnd, x, y, w, h, true);
        System.out.println("  成功 handle=" + win.handle() + "  存活=" + win.isAlive());

        System.out.println();
        System.out.println("=== 4) 读真实歌曲的内嵌封面 ===");
        String flac = findFirstFlac(Paths.get(musicRoot));
        System.out.println("  样本: " + flac);
        RenderSettings s = new RenderSettings();
        int coverTarget = (int) Math.round(s.getCoverSize() * scale * s.getLayoutScalePercent() / 100.0);
        BufferedImage cover = flac == null ? null
                : CoverArt.INSTANCE.forSong("probe", flac, Math.max(8, Math.min(coverTarget, h)));
        System.out.println("  封面=" + (cover == null ? "无" : cover.getWidth() + "x" + cover.getHeight())
                + "  背景=" + s.getShowBackground() + "(不透明度 " + s.getBackgroundOpacity() + "%)"
                + "  逐字扫描=" + s.getWordScanning());

        System.out.println();
        System.out.println("=== 5) 渲染循环 ===");
        LyricsPainter painter = new LyricsPainter();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        String[][] song = {
                {"还没好好的感受", "雪花绽放的气候"},
                {"我们一起颤抖", "会更明白什么是温柔"},
                {"还没跟你牵着手", "走过荒芜的沙丘"},
                {"可能从此以后", "学会珍惜天长和地久"},
        };

        long start = System.currentTimeMillis();
        long until = start + holdSec * 1000L;
        int frames = 0;
        boolean saved = false;

        while (System.currentTimeMillis() < until) {
            win.pumpMessages();

            long elapsed = System.currentTimeMillis() - start;
            int idx = (int) ((elapsed / LINE_MS) % song.length);
            long lineStart = (elapsed / LINE_MS) * LINE_MS;
            long position = lineStart + (elapsed % LINE_MS);

            LyricsFrame frame = new LyricsFrame(
                    song[idx][0], song[idx][1], cover,
                    synthesize(song[idx][0], lineStart, LINE_MS),
                    position, lineStart, lineStart + LINE_MS);

            painter.paint(image, frame, s, bar.dpi / 96.0);
            win.pushFrame(pixels);
            frames++;

            if (!saved && frames > 25) {
                saved = true;
                saveDesign(image, new File(outDir, "plugin-render.png"));
                readback(win.handle(), w, h, new File(outDir, "plugin-readback.png"));
                System.out.println("  已存出 plugin-render.png / plugin-readback.png");
            }
            Thread.sleep(33);
        }

        System.out.println("  共渲染 " + frames + " 帧，全程窗口存活=" + win.isAlive());
        win.destroy();
        System.out.println("=== 完成 ===");
    }

    private static final long LINE_MS = 2600L;

    /** 按字数均分，模拟 SyllableSynthesizer 在没有字级时间时的做法。 */
    private static List<LyricCell> synthesize(String text, long lineStart, long span) {
        List<LyricCell> cells = new ArrayList<>();
        int n = text.length();
        for (int i = 0; i < n; i++) {
            cells.add(new LyricCell(
                    lineStart + span * i / n,
                    lineStart + span * (i + 1) / n,
                    text.substring(i, i + 1)));
        }
        return cells;
    }

    private static void saveDesign(BufferedImage image, File out) throws Exception {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        ImageIO.write(copy, "png", out);
    }

    /** 用 PrintWindow 把窗口真实内容回读成 PNG。 */
    private static void readback(Pointer hwnd, int w, int h, File out) throws Exception {
        Pointer dc = Win32.U.GetDC(null);
        Win32.BITMAPINFO bmi = new Win32.BITMAPINFO();
        bmi.bmiHeader.biSize = bmi.bmiHeader.size();
        bmi.bmiHeader.biWidth = w;
        bmi.bmiHeader.biHeight = h;
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = 0;
        bmi.bmiHeader.biSizeImage = w * h * 4;

        PointerByReference ref = new PointerByReference();
        Pointer bmp = Win32.G.CreateDIBSection(dc, bmi, 0, ref, null, 0);
        Pointer bdc = Win32.G.CreateCompatibleDC(dc);
        Win32.G.SelectObject(bdc, bmp);

        boolean ok = Win32.U.PrintWindow(hwnd, bdc, Win32.PW_RENDERFULLCONTENT);
        System.out.println("  PrintWindow=" + ok);

        byte[] buf = ref.getValue().getByteArray(0, w * h * 4);
        int[] px = new int[w * h];
        for (int row = 0; row < h; row++) {
            int srcRow = (h - 1 - row) * w * 4;
            for (int col = 0; col < w; col++) {
                int o = srcRow + col * 4;
                px[row * w + col] = ((buf[o + 3] & 0xFF) << 24)
                        | ((buf[o + 2] & 0xFF) << 16)
                        | ((buf[o + 1] & 0xFF) << 8)
                        | (buf[o] & 0xFF);
            }
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(img, "png", out);

        int bright = 0;
        for (int v : px) {
            int a = v >>> 24;
            int lum = ((v >> 16 & 0xFF) + (v >> 8 & 0xFF) + (v & 0xFF)) / 3;
            if (a > 128 && lum > 180) {
                bright++;
            }
        }
        System.out.println("  回读亮文字像素=" + bright);

        Win32.G.DeleteObject(bmp);
        Win32.G.DeleteDC(bdc);
        Win32.U.ReleaseDC(null, dc);
    }

    private static String findFirstFlac(Path root) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().toLowerCase().endsWith(".flac"))
                    .findFirst()
                    .map(Path::toString)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
