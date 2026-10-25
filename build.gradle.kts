import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// 必须显式 import：脚本里的 `java` 会被解析成 Gradle 的 java {} 扩展，
// 直接写 java.util.zip.ZipFile 会报 Unresolved reference: util
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("java-library")
    // 注意：kapt 在单模块工程里必须显式带版本（官方示例是多模块，版本由根工程提供）。
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.kapt)
}

group = "com.spwtb"
// 版本号的唯一来源。Manifest、文件名、包内元数据都从这里取，
// 不要再单独写一份 pluginVersion，否则改一处漏一处。
version = "1.4.15"

val jnaVersion = "5.15.0"

// ─────────────────────────────────────────────────────────────────────────────
// 构建目标说明：
//
// 本插件仍编译到 0.1.0-dev20（SPW 1.18.x API），并手工打包成 .spmod。
// 它只用到播放扩展点、配置管理等稳定接口，已在 SPW 1.19.2 宿主上实测可用。
//
// 官方文档当前推荐 0.1.0-dev22 + Kotlin 2.4.20 + JDK 25；dev21/dev22 存在
// API 变化（library、hookRegistrar、onLyricsLinesUpdated、MediaItem.id 等），
// 升级需要重新回归测试。迁移计划放到 1.5.0，避免在本次发布前引入构建风险。
//
// 手工打包的目录结构（classes/ + lib/）与 Manifest 属性沿用官方
// WorkshopPluginTask 的结构，并内置校验（见下方 verifySpmod）。
// ─────────────────────────────────────────────────────────────────────────────

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        // dev20 的 API 是 Java 21 字节码，宿主运行时是 Java 25，输出 21 两边都能跑。
        // 不使用 jvmToolchain：避免在缺对应 JDK 时触发 Gradle 联网下载工具链。
        jvmTarget = JvmTarget.JVM_21
        // 不设置 jvm-default 相关参数：本插件没有声明带默认实现的 Kotlin 接口，
        // 且该参数在 Kotlin 2.2 前后拼写与取值都变过，徒增版本耦合。
    }
}

tasks.test {
    // 字体家族注册是 JVM 全局状态：StartupBoldOrderTest 需要全新 JVM 才能复现
    // 「只注册了粗体面」的历史 bug，否则别的测试先注册常规面会掩盖它。
    forkEvery = 1
}

tasks.processResources {
    // MIT 许可证与第三方声明随包分发：源码 jar 和最终 .spmod 的
    // classes/META-INF/ 下都会出现。
    from("LICENSE") { into("META-INF") }
    from("THIRD-PARTY-NOTICES.md") {
        into("META-INF")
        rename { "THIRD-PARTY-NOTICES.txt" }
    }
}

dependencies {
    // Kotlin 标准库与 SPW API 都由宿主提供，用 compileOnly 避免重复打进插件包
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.spw.workshop.api)
    kapt(libs.spw.workshop.api)

    // JNA：插件自己调 Win32 把歌词画到任务栏上。
    // 必须用 implementation（会进 runtimeClasspath → 打进 lib/），
    // 宿主进程里没有 JNA。它自带各平台原生库，不需要用户另外装东西。
    implementation("net.java.dev.jna:jna:$jnaVersion")

    // 纯逻辑单测（LRC 解析 / 选行 / 逐字进度）。只在本机构建时用，不进插件包。
    testImplementation(kotlin("test-junit"))
    // 单测要伪造 ConfigManager / ConfigHelper，需要 API 类型在测试类路径上
    testImplementation(libs.spw.workshop.api)
}

val pluginId = "com.spwtb.spw-taskbar-lyrics"
val pluginVersion = version.toString()
val pluginClass = "com.spwtb.taskbarlyrics.TaskbarLyricsPlugin"

/**
 * 裁剪 JNA：只保留 Windows 原生库。
 *
 * 完整的 jna-5.15.0.jar 约 2.0MB，其中九成是 aix / linux / darwin / sunos 等
 * 平台的原生库；Windows x64 只需要 win32-x86-64（约 267KB）。
 * 宿主是 Windows，保留 win32-x86-64 与 win32-aarch64 覆盖 x64 / ARM64。
 *
 * 注意：JNA 是 Apache-2.0 / LGPL 双许可，META-INF/LICENSE 必须保留。
 */
val slimJnaJar = layout.buildDirectory.file("slim/jna-$jnaVersion-win.jar")

val slimJna = tasks.register("slimJna") {
    description = "裁剪 JNA，只保留 Windows 原生库"
    group = "build"

    val source = configurations.runtimeClasspath.map { files ->
        files.firstOrNull { it.name == "jna-$jnaVersion.jar" }
            ?: throw GradleException("runtimeClasspath 里找不到 jna-$jnaVersion.jar")
    }
    inputs.files(source)
    outputs.file(slimJnaJar)

    doLast {
        val src = source.get()
        val out = slimJnaJar.get().asFile
        out.parentFile.mkdirs()

        val keepPlatforms = setOf("win32-x86-64", "win32-aarch64")
        ZipFile(src).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { os ->
                for (entry in zip.entries()) {
                    val platform = platformDirOf(entry.name)
                    if (platform != null && platform !in keepPlatforms) {
                        continue
                    }
                    os.putNextEntry(ZipEntry(entry.name).apply { if (entry.time >= 0) time = entry.time })
                    zip.getInputStream(entry).use { it.copyTo(os) }
                    os.closeEntry()
                }
            }
        }

        ZipFile(out).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            if ("com/sun/jna/win32-x86-64/jnidispatch.dll" !in names) {
                throw GradleException("裁剪后的 JNA 缺少 win32-x86-64/jnidispatch.dll")
            }
            if (names.none { it.startsWith("META-INF/LICENSE") }) {
                throw GradleException("裁剪后的 JNA 缺少 META-INF/LICENSE")
            }
        }
        logger.lifecycle("JNA 裁剪：${src.length() / 1024} KB → ${out.length() / 1024} KB")
    }
}

/** `com/sun/jna/<platform>/...` 的平台目录名；不是平台目录返回 null。 */
fun platformDirOf(entryName: String): String? {
    val prefix = "com/sun/jna/"
    if (!entryName.startsWith(prefix)) return null
    val rest = entryName.substring(prefix.length)
    val slash = rest.indexOf('/')
    if (slash <= 0) return null
    val dir = rest.substring(0, slash)
    // 平台目录名都带 '-'（win32-x86-64 / linux-aarch64 / darwin-aarch64 ...），
    // 普通包名不会；这样不会误删 com/sun/jna/internal 之类的类路径。
    return dir.takeIf { it.contains('-') }
}

// Manifest 属性说明：
// Gradle 会在第 72 字节处折行（JAR 规范建议行长上限）。折行可能落在一个 UTF-8
// 多字节字符中间，这一点经实测是无害的：java.util.jar.Manifest 会先拼接续行字节
// 再做 UTF-8 解码，中文能正确还原。但仅在"真的超过 72 字节"时才会折行，
// 所以描述保持简短一方面更易读，一方面也避免依赖这个行为。
tasks.named<Jar>("jar") {
    archiveBaseName.set("plugin-classes")
    // 中间 jar 挪出 build/libs，让该目录只放可分发的 .spmod（与官方 Gradle 插件一致）
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/spmod"))
    manifest {
        attributes(
            "Plugin-Class" to pluginClass,
            "Plugin-Id" to pluginId,
            "Plugin-Version" to pluginVersion,
            "Plugin-Name" to "任务栏歌词",
            // 保持短：超过 72 字节会折行，可能切在 UTF-8 多字节字符中间。
            "Plugin-Description" to "从音频文件读取歌词与封面",
            "Plugin-Provider" to "spwtb",
            "Plugin-Has-Config" to "true",
            "Plugin-Open-Source-Url" to "https://github.com/wen125dukhvuidh/spw-taskbar-lyrics"
        )
    }
}

/**
 * 打包 .spmod 分发包。
 *
 * 结构完全对照官方 Gradle 插件 WorkshopPluginTask 的实现（该类是 Zip 的子类）：
 *   - into("classes") { from(zipTree(内层 jar)) } —— 注意是**展开 jar 的内容**，
 *     而不是把 jar 文件本身放进去。放错会导致宿主提示「不是一个有效的模组文件」。
 *   - into("lib") { from(runtimeClasspath 里的 jar) }
 *   - 文件名 plugin-<插件 ID>-<插件版本>.spmod
 */
tasks.register<Zip>("plugin") {
    group = "build"
    description = "打包成可分发的 .spmod"

    dependsOn(tasks.named("jar"))

    archiveBaseName.set("plugin-$pluginId")
    archiveVersion.set(pluginVersion)
    // 扩展名必须是 spmod：Steam 创意工坊的发布器只接受 .spmod 文件
    // （见官网「发布 Mod」页）。播放器的本地导入三种都收（.spmod/.jar/.zip），
    // 所以以前打 .zip 只是为了兼容 1.18.x 的选择框，现在没必要了。
    archiveExtension.set("spmod")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    val innerJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }

    into("classes") {
        from(project.zipTree(innerJar))
    }
    dependsOn(slimJna)
    into("lib") {
        // 完整 JNA 不进包，换成只带 Windows 原生库的裁剪版
        from(configurations.runtimeClasspath.map { files ->
            files.filter { it.name.endsWith(".jar") && it.name != "jna-$jnaVersion.jar" }
        })
        from(slimJnaJar)
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // 打包后立刻校验，见下方说明。放在同一任务里是为了保证「一定会执行」，
    // 避免出现「编译过了、包也生成了，但宿主说不是有效模组」这种情况。
    doLast {
        verifySpmod(archiveFile.get().asFile)
    }
}

/**
 * 校验最终分发包。
 *
 * 有惨痛教训：之前打包时把内层 jar 整个塞进了 classes/，编译完全正常、
 * 产物也看着像模像样，但导入宿主时直接报
 * 「无法导入模组，这似乎不是一个有效的模组文件」。
 * 正确做法（对照官方 WorkshopPluginTask）是把 jar 的**内容展开**到 classes/。
 *
 * 这里打开真正要发布的 .spmod，逐项确认宿主加载所需的东西都在。
 */
fun verifySpmod(spmod: File) {
    ZipFile(spmod).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toList()

        // 1) Manifest 必须在 classes/META-INF/ 下，且能被解析
        val manifestEntry = zip.getEntry("classes/META-INF/MANIFEST.MF")
            ?: throw GradleException("分发包缺少 classes/META-INF/MANIFEST.MF，宿主无法识别插件。实际内容：$names")
        val attributes = zip.getInputStream(manifestEntry).use { Manifest(it) }.mainAttributes

        for (key in listOf("Plugin-Class", "Plugin-Id", "Plugin-Version")) {
            if (attributes.getValue(key).isNullOrBlank()) {
                throw GradleException("Manifest 缺少必填属性 $key")
            }
        }

        // 2) Manifest 声明的插件主类必须真的在包里
        val declared = attributes.getValue("Plugin-Class")
        val classEntry = "classes/" + declared.replace('.', '/') + ".class"
        if (zip.getEntry(classEntry) == null) {
            throw GradleException("Manifest 声明的 Plugin-Class=$declared 在包中不存在：$classEntry")
        }

        // 3) PF4J 扩展索引必须在，否则扩展点方法永远不会被调用
        val idxEntry = zip.getEntry("classes/META-INF/extensions.idx")
            ?: throw GradleException("分发包缺少 classes/META-INF/extensions.idx，PF4J 发现不了任何扩展")
        val extensions = zip.getInputStream(idxEntry).use { it.readBytes().decodeToString() }
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (extensions.isEmpty()) {
            throw GradleException("extensions.idx 为空")
        }

        // 4) 声明了有配置界面，就必须带上配置文件
        if (attributes.getValue("Plugin-Has-Config") == "true" &&
            zip.getEntry("classes/preference_config.json") == null
        ) {
            throw GradleException("声明了 Plugin-Has-Config=true，但包内没有 classes/preference_config.json")
        }

        // 5) lib/ 里的 JNA 必须是裁剪版，且带 Windows 原生库与许可证
        val libJar = names.firstOrNull { it.startsWith("lib/jna-") && it.endsWith(".jar") }
            ?: throw GradleException("分发包缺少 lib/jna-*.jar")
        var hasDll = false
        var hasLicense = false
        zip.getInputStream(zip.getEntry(libJar)).use { input ->
            ZipInputStream(input).use { jar ->
                while (true) {
                    val entry = jar.nextEntry ?: break
                    if (entry.name == "com/sun/jna/win32-x86-64/jnidispatch.dll") hasDll = true
                    if (entry.name.startsWith("META-INF/LICENSE")) hasLicense = true
                }
            }
        }
        if (!hasDll) {
            throw GradleException("$libJar 缺少 win32-x86-64/jnidispatch.dll，运行时加载不了 JNA")
        }
        if (!hasLicense) {
            throw GradleException("$libJar 缺少 META-INF/LICENSE（JNA 双许可要求保留）")
        }

        // 6) 本插件与第三方声明也要随包分发（MIT 要求副本保留版权与许可）
        for (required in listOf(
            "classes/META-INF/LICENSE",
            "classes/META-INF/THIRD-PARTY-NOTICES.txt"
        )) {
            if (zip.getEntry(required) == null) {
                throw GradleException("分发包缺少 $required（MIT 许可与第三方声明要求随包分发）")
            }
        }

        logger.lifecycle(
            "分发包校验通过：${spmod.name}（${spmod.length() / 1024} KB）" +
                " | Plugin-Class=$declared" +
                " | 扩展=${extensions.joinToString()}"
        )
    }
}
