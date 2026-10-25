import com.spwtb.taskbarlyrics.config.PluginSettings;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 验证「数值设置吸附回整数」。
 *
 * 宿主的 seekbar 拖出来是浮点（实测存成 "fontSize": 13.025173），
 * 插件的做法是趁它落盘把整数写回去。宿主的类型处理有两种可能：
 *   - 宽松：Int 也能被当成 Float 读出来（写整数最省事，界面显示 130）
 *   - 严格：只有浮点才认（那得退回去写 Float，界面显示 130.0）
 * 两种都要能落地，且不能因为 save() 触发配置监听而绕成死循环。
 *
 * 用法：SettingsSnapProbe
 */
public class SettingsSnapProbe {

    static int passed = 0;
    static int failed = 0;

    // ── 假的宿主配置系统 ────────────────────────────────────────────────

    static class FakeManager implements ConfigManager {

        final Map<String, FakeHelper> helpers = new LinkedHashMap<>();
        final List<Consumer<ConfigHelper>> listeners = new ArrayList<>();
        final boolean strict;
        int fireBudget = 64;
        int fireCount = 0;

        FakeManager(boolean strict) {
            this.strict = strict;
        }

        @Override
        public ConfigHelper getConfig() {
            return getConfig("general.json");
        }

        @Override
        public ConfigHelper getConfig(String file) {
            return helpers.computeIfAbsent(file, f -> new FakeHelper(this, f));
        }

        @Override
        public void addConfigChangeListener(Consumer<ConfigHelper> l) {
            listeners.add(l);
        }

        @Override
        public void addConfigChangeListener(String file, Consumer<ConfigHelper> l) {
            listeners.add(l);
        }

        @Override
        public void removeConfigChangeListener(Consumer<ConfigHelper> l) {
            listeners.remove(l);
        }

        /** save() 之后宿主会回调所有监听器——正好用来验证会不会绕成死循环。 */
        void fire(FakeHelper h) {
            fireCount++;
            if (fireBudget-- <= 0) {
                throw new IllegalStateException("配置监听被反复触发，可能是写回和自我回调打起来了");
            }
            for (Consumer<ConfigHelper> l : new ArrayList<>(listeners)) {
                l.accept(h);
            }
        }
    }

    static class FakeHelper implements ConfigHelper {

        final FakeManager mgr;
        final String file;
        final Map<String, Object> data = new LinkedHashMap<>();

        FakeHelper(FakeManager mgr, String file) {
            this.mgr = mgr;
            this.file = file;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T get(String key, T def) {
            Object v = data.get(key);
            if (v == null) {
                return def;   // 键不存在：返回默认值，插件不许写
            }
            if (def instanceof Float) {
                if (!(v instanceof Number n)) {
                    return def;
                }
                // 严格模式：只认浮点，Int 会被当成类型不符 → 返回默认值
                boolean isFloat = (v instanceof Float) || (v instanceof Double);
                if (mgr.strict && !isFloat) {
                    return def;
                }
                return (T) (Float) n.floatValue();
            }
            if (def instanceof Integer && v instanceof Number n) {
                return (T) (Integer) n.intValue();
            }
            return def.getClass().isInstance(v) ? (T) v : def;
        }

        @Override
        public void set(String key, Object value) {
            data.put(key, value);
        }

        @Override
        public boolean save() {
            mgr.fire(this);
            return true;
        }

        @Override
        public boolean reload() {
            return true;
        }

        @Override
        public Path getConfigPath() {
            return Path.of("build/probe-config", file);
        }
    }

    // ── 数值期望表：键后缀 → {原始值, 期望整数} ─────────────────────────

    /** 故意写得接近手势：小数、超范围、负数、已经是整数各来一份。 */
    static Map<String, float[]> cases() {
        Map<String, float[]> m = new LinkedHashMap<>();
        m.put("general.xOffset", new float[]{-37.4182f, -37f});
        m.put("general.yOffset", new float[]{12.5001f, 13f});
        m.put("display.widthPercent", new float[]{99.6f, 100f});
        m.put("display.fixedWidth", new float[]{512.0f, 512f});       // 已经是整数：不许写
        m.put("display.layoutScalePercent", new float[]{124.98f, 125f});
        m.put("typography.fontSize", new float[]{13.025173f, 13f});
        m.put("typography.textGammaPercent", new float[]{130.121f, 130f});
        m.put("typography.lyricOffsetMs", new float[]{-1500.2f, -1500f});
        m.put("appearance.backgroundOpacity", new float[]{55.4f, 55f});
        m.put("appearance.cornerRadius", new float[]{500f, 40f});     // 超上限：夹到 40
        m.put("cover.size", new float[]{33.7f, 34f});
        m.put("cover.gap", new float[]{2f, 2f});                      // 已经是整数：不许写
        m.put("cover.cornerRadius", new float[]{-8f, 0f});            // 超下限：夹到 0
        return m;
    }

    static String fileOf(String key) {
        return key.substring(0, key.indexOf('.')) + ".json";
    }

    public static void main(String[] args) throws Exception {
        run("宽松宿主（Int 也能当 Float 读出来）", false);
        run("严格宿主（只认浮点）", true);

        System.out.println();
        System.out.printf("%d 项通过，%d 项失败%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    static PluginSettings build(FakeManager mgr) throws Exception {
        Constructor<PluginSettings> ctor = PluginSettings.class.getDeclaredConstructor(ConfigManager.class);
        ctor.setAccessible(true);
        return ctor.newInstance(mgr);
    }

    static void run(String label, boolean strict) throws Exception {
        System.out.println("── " + label + " ──");
        FakeManager mgr = new FakeManager(strict);
        PluginSettings ps = build(mgr);

        Map<String, Float> before = new LinkedHashMap<>();
        List<String> untouched = new ArrayList<>();
        for (Map.Entry<String, float[]> e : cases().entrySet()) {
            FakeHelper h = (FakeHelper) mgr.getConfig(fileOf(e.getKey()));
            h.data.put(e.getKey(), e.getValue()[0]);
            before.put(e.getKey(), e.getValue()[0]);
        }
        // 一个插件写たことのない键：吸附完必须仍然不存在，不能被凭空创建
        FakeHelper display = (FakeHelper) mgr.getConfig("display.json");
        untouched.add(display.data.containsKey("display.unknownKey") ? "?" : "display.unknownKey");
        int sizeBefore = totalSize(mgr);

        // 模拟 PluginRuntime 的监听器：配置一变就调一次吸附
        ps.observe(() -> {
            int n = ps.snapNumericSettings();
            return null;
        });

        Method snap = PluginSettings.class.getDeclaredMethod("snapNumericSettings");
        snap.setAccessible(true);

        int written = (int) snap.invoke(ps);
        check("写回了全部带小数的键（实际 " + written + " 个）", written >= 11, "写回数偏少");

        for (Map.Entry<String, float[]> e : cases().entrySet()) {
            FakeHelper h = (FakeHelper) mgr.getConfig(fileOf(e.getKey()));
            float want = e.getValue()[1];
            // 用宿主的方式读：期待 float，读不到就是 NaN
            float got = h.get(e.getKey(), Float.NaN);
            boolean ok = Math.abs(got - want) < 1e-4f;
            check(String.format("%-32s → %s（期望 %s）", e.getKey(), fmt(h.data.get(e.getKey())), (int) want),
                    ok, "读到 " + got);
        }

        int again = (int) snap.invoke(ps);
        check("再跑一遍是 0 次写入（幂等，不会反复落盘）", again == 0, "又写了 " + again + " 个");

        check("没有凭空创建缺失的键", totalSize(mgr) == sizeBefore,
                "键数从 " + sizeBefore + " 变成 " + totalSize(mgr));

        boolean alreadyInt = false;
        for (Map.Entry<String, float[]> e : cases().entrySet()) {
            if (Math.abs(before.get(e.getKey()) - e.getValue()[1]) < 1e-4f) {
                Object stored = mgr.getConfig(fileOf(e.getKey()));
                alreadyInt = true;
            }
        }
        check("本来就是整数的值没被改动", alreadyInt, "用例表缺少「已是整数」的样本");

        System.out.printf("   监听器被触发 %d 次（含自己写回引起的一次）%n", mgr.fireCount);
        System.out.println();
    }

    static int totalSize(FakeManager mgr) {
        int n = 0;
        for (FakeHelper h : mgr.helpers.values()) {
            n += h.data.size();
        }
        return n;
    }

    static String fmt(Object v) {
        if (v == null) {
            return "(缺失)";
        }
        return v.getClass().getSimpleName() + " " + v;
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + "  → " + detail);
        }
    }
}
