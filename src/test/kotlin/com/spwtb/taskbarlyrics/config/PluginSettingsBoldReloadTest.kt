@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spwtb.taskbarlyrics.config

import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 复现「改加粗要重启」：
 * 宿主把 `bold:false` 写进了文件，但插件侧的 ConfigHelper 仍返回旧值 true。
 * 修复后 snapshot() 应直接以文件为准。
 */
class PluginSettingsBoldReloadTest {

    @Test
    fun `bold is read from disk even when the config helper is stale`() {
        val dir = Files.createTempDirectory("spw-bold-test")
        try {
            val typographyFile = dir.resolve("typography.json")
            Files.writeString(
                typographyFile,
                """{"typography":{"bold":false,"fontSize":14.0}}"""
            )

            // helper 里仍是旧值 true，模拟宿主 reload() 不刷新插件侧嵌套值
            val typography = FakeHelper(typographyFile, mapOf(KEY_BOLD to true))
            val manager = FakeManager(
                mapOf(
                    PluginSettings.FILE_GENERAL to FakeHelper(dir.resolve("general.json"), emptyMap()),
                    PluginSettings.FILE_DISPLAY to FakeHelper(dir.resolve("display.json"), emptyMap()),
                    PluginSettings.FILE_TYPOGRAPHY to typography,
                    PluginSettings.FILE_APPEARANCE to FakeHelper(dir.resolve("appearance.json"), emptyMap()),
                    PluginSettings.FILE_COVER to FakeHelper(dir.resolve("cover.json"), emptyMap())
                )
            )

            val ctor = PluginSettings::class.java.getDeclaredConstructor(ConfigManager::class.java)
            ctor.isAccessible = true
            val settings = ctor.newInstance(manager) as PluginSettings

            assertEquals(false, settings.snapshot().bold)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private class FakeManager(
        private val helpers: Map<String, ConfigHelper>
    ) : ConfigManager {
        override fun getConfig(): ConfigHelper = helpers.values.first()

        override fun getConfig(fileName: String): ConfigHelper =
            helpers[fileName] ?: error("未注册的配置：$fileName")

        override fun addConfigChangeListener(listener: Consumer<ConfigHelper>) = Unit

        override fun addConfigChangeListener(
            fileName: String,
            listener: Consumer<ConfigHelper>
        ) = Unit

        override fun removeConfigChangeListener(listener: Consumer<ConfigHelper>) = Unit
    }

    private class FakeHelper(
        private val file: Path,
        private val values: Map<String, Any?>
    ) : ConfigHelper {
        override fun <T> get(key: String, defaultValue: T): T {
            @Suppress("UNCHECKED_CAST")
            return (values[key] as? T) ?: defaultValue
        }

        override fun set(key: String, value: Any) = Unit

        override fun save(): Boolean = true

        /** 故意什么都不做：模拟宿主 reload() 不刷新插件侧嵌套值。 */
        override fun reload(): Boolean = true

        override fun getConfigPath(): Path = file
    }

    private companion object {
        const val KEY_BOLD = "typography.bold"
    }
}
