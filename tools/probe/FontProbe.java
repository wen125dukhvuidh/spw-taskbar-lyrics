import java.awt.GraphicsEnvironment;

/** 检查原版用的字体在本机是否存在，决定要不要自带字体文件。 */
public class FontProbe {
    public static void main(String[] args) {
        String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        System.out.println("系统字体家族总数: " + names.length);
        System.out.println();
        String[] wanted = {
                "Source Han Sans SC", "Source Han Sans CN", "Source Han Sans",
                "Noto Sans SC", "Noto Sans CJK SC",
                "思源黑体", "思源黑体 CN", "微软雅黑",
                "Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "Segoe UI"
        };
        for (String want : wanted) {
            boolean found = false;
            for (String name : names) {
                if (name.equalsIgnoreCase(want)) {
                    found = true;
                    break;
                }
            }
            System.out.printf("  %-22s %s%n", want, found ? "有 ✓" : "无");
        }
        System.out.println();
        System.out.println("含 \"Han\" 或 \"Source\" 或 \"Noto\" 的家族：");
        for (String name : names) {
            String lower = name.toLowerCase();
            if (lower.contains("han") || lower.contains("source") || lower.contains("noto")) {
                System.out.println("  " + name);
            }
        }
    }
}
