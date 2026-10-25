// 依赖来源说明：
// 国内网络对 Maven Central / GitHub 的直连经常不可用，因此把阿里云公共镜像放在最前，
// 官方仓库作为兜底保留。海外环境会自动落到官方仓库，不受影响。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://jitpack.io")
        gradlePluginPortal()
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
