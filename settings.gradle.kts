// 依赖来源说明：
// 国内网络对 Maven Central / GitHub 的直连经常不可用，因此把阿里云公共镜像放在最前，
// 官方仓库作为兜底保留。海外环境会自动落到官方仓库，不受影响。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://jitpack.io")
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            // JitPack 的多模块发布物 groupId 与 Gradle 默认插件标记坐标不同，
            // 必须显式映射；版本取自 build.gradle.kts plugins 块里的 requested.version。
            if (requested.id.id == "com.xuncorp.spw.workshop") {
                useModule(
                    "com.github.Moriafly.spw-workshop-api:spw-workshop-gradle-plugin:${requested.version}"
                )
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://jitpack.io")
        google()
        mavenCentral()
    }
}

rootProject.name = "spw-taskbar-lyrics"
