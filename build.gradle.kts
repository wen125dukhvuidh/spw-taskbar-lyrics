import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.kapt)
    // 官方 Workshop 打包插件：生成 Manifest（含 Plugin-Permissions）并注册 plugin 任务。
    // settings.gradle.kts 里的 useModule 映射把它指向 JitPack 上的实现。
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "com.spwtb"
version = "1.5.0"

val jnaVersion = "5.15.0"

// 构建目标：官方 dev22 / JDK 25（SPW 1.19.x API）。
// 宿主运行时是 Java 25；插件使用 1.19 的 onLyricsLinesUpdated / getCurrentMediaItem，
// 并声明 LIBRARY_READ。1.18.x 宿主不再保证可用（dev22 API 是 Java 25 字节码）。
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

tasks.test {
    // 字体家族注册是 JVM 全局状态：StartupBoldOrderTest 需要全新 JVM 才能复现
    // 「只注册了粗体面」的历史 bug，否则别的测试先注册常规面会掩盖它。
    forkEvery = 1
}

tasks.processResources {
    // MIT 许可证与第三方声明随包分发：最终 .spmod 的 classes/META-INF/ 下都会出现。
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
    // 官方 Workshop 打包任务会把 runtimeClasspath 的 jar 原样放进 lib/。
    implementation("net.java.dev.jna:jna:$jnaVersion")

    // 纯逻辑单测；只在本机构建时用，不进插件包。
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.spw.workshop.api)
}

// 官方 Workshop Gradle 插件读取这个块，生成 Manifest（含 Plugin-Permissions）
// 并注册 plugin 任务，产物：build/libs/plugin-<id>-<version>.spmod。
spmod {
    PluginClass = "com.spwtb.taskbarlyrics.TaskbarLyricsPlugin"
    PluginId = "com.spwtb.spw-taskbar-lyrics"
    PluginVersion = version.toString()
    PluginProvider = "spwtb"
    PluginName = "任务栏歌词"
    PluginDescription = "从音频文件读取歌词与封面"
    PluginHasConfig = true
    PluginOpenSourceUrl = "https://github.com/wen125dukhvuidh/spw-taskbar-lyrics"
    // 只申请曲库读取：用于 1.19 的 getCurrentMediaItem，补首曲的真实路径/封面。
    // LIBRARY_WRITE 不需要，不要申请。
    PluginPermissions = listOf(PluginPermission.LIBRARY_READ)
}
