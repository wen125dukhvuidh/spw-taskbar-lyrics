import java.awt.GraphicsEnvironment;

/** 列出与中文字重相关的已注册家族名，用来判断「思源黑体」到底注册成了什么名字。 */
public class Fam {
    public static void main(String[] a) {
        String[] f = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        System.out.println("总家族数: " + f.length);
        System.out.println("--- 含 CJK 字符、或含 han/noto/source/yahei 的家族 ---");
        for (String s : f) {
            String low = s.toLowerCase();
            if (s.matches(".*[\\u4e00-\\u9fff].*") || low.contains("han")
                    || low.contains("noto") || low.contains("source") || low.contains("yahei")) {
                System.out.println("  [" + s + "]");
            }
        }
    }
}
