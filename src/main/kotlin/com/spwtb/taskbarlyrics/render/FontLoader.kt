package com.spwtb.taskbarlyrics.render

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 按**文件**加载字体，兜住"文件在、但没注册"的情况。
 *
 * 为什么需要它：原版 TaskbarLyrics 自带的就是思源黑体
 * （`Assets/Fonts/SourceHanSansSC-Bold.otf`，字重 700）。
 * 本机字体目录里也躺着 `SourceHanSansSC-Bold-2.otf`，但它**没有注册进系统字体表**——
 * `GraphicsEnvironment.getAvailableFontFamilyNames()` 里既没有「思源黑体」
 * 也没有 "Source Han Sans SC"，光靠家族名永远找不到，会静默退到别的字体，
 * 字重就差一档（实测 Noto Sans SC Bold 的墨量比思源 Bold 低约 1.4%）。
 *
 * 做法：在标准字体目录里按**文件名**匹配，用 [Font.createFont] 加载，
 * 再 [GraphicsEnvironment.registerFont] 注册回去，之后按家族名也能取到。
 * 只在家族名查不到时才会走到这里，结果有缓存。
 */
internal object FontLoader {

    private val searchDirs: List<File> by lazy {
        listOfNotNull(
            System.getenv("WINDIR")?.let { File(it, "Fonts") },
            System.getenv("LOCALAPPDATA")?.let { File(it, "Microsoft\\Windows\\Fonts") },
            File("C:\\Windows\\Fonts")
        ).filter { it.isDirectory }.distinctBy { it.absolutePath }
    }

    /** 归一化文件名 -> 文件。目录只扫一次。 */
    private val index: List<Pair<String, File>> by lazy {
        searchDirs.flatMap { dir ->
            dir.listFiles { f ->
                f.isFile && f.name.lowercase().let {
                    it.endsWith(".ttf") || it.endsWith(".otf") || it.endsWith(".ttc")
                }
            }?.map { normalize(it.name.substringBeforeLast('.')) to it } ?: emptyList()
        }
    }

    private val cache = ConcurrentHashMap<String, Font>()

    /**
     * 按家族名找同名字体文件并加载。
     *
     * @param family 家族名，例如 `Source Han Sans SC`
     * @param bold   需要粗体时优先带 `Bold` 的文件
     * @return 已加载（必要时已注册）的字体；找不到返回 null
     */
    fun loadByName(family: String, bold: Boolean): Font? {
        val key = "$family|$bold"
        cache[key]?.let { return it }

        val want = normalize(family)
        // 太短的名字容易误匹配（例如 "SC"、"CN"）
        if (want.length < 5) return null

        val matches = index.filter { it.first.startsWith(want) }
        if (matches.isEmpty()) return null

        for ((stem, file) in matches.sortedBy { score(it.first, want, bold) }) {
            val loaded = try {
                Font.createFont(Font.TRUETYPE_FONT, file)
            } catch (_: Throwable) {
                continue
            }
            // createFont 出来的字重已经跟着文件走了，这里只做一次保险
            val font = if (bold && !loaded.isBold) loaded.deriveFont(Font.BOLD) else loaded
            runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)
            }
            cache[key] = font
            return font
        }
        return null
    }

    /** 越小越优先。 */
    private fun score(stem: String, want: String, bold: Boolean): Int {
        // 名字越接近越好（"-2" 之类的去重后缀会被算成一点小惩罚）
        var s = stem.length - want.length
        val hasBold = stem.contains("bold")
        if (bold) {
            if (!hasBold) s += 1000
            if (stem.contains("semibold") || stem.contains("extrabold")) s += 20
        } else {
            if (hasBold) s += 1000
            if (stem.contains("regular")) s -= 5
        }
        // 避开明显不对的字重
        for (w in EXTRA_WEIGHT_WORDS) {
            if (stem.contains(w)) s += 400
        }
        return s
    }

    /** 只保留字母、数字与 CJK，便于跨命名风格比较。 */
    private fun normalize(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    private val EXTRA_WEIGHT_WORDS =
        listOf("light", "thin", "black", "heavy", "ultra", "hairline")
}
