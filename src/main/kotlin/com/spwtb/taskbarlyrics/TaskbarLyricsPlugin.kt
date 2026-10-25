@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwtb.taskbarlyrics

import com.spwtb.taskbarlyrics.config.PluginSettings
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * 插件主类。完整类名必须与 build.gradle.kts 中 Manifest 的 Plugin-Class 一致。
 *
 * 职责：
 * 1. 读配置；
 * 2. 启动任务栏歌词渲染线程（**完全在插件进程内自绘，不依赖任何外部程序**）；
 * 3. 停用时把渲染线程与窗口收干净。
 *
 * 插件可能被反复启停，所以注册与释放都成对放在 start / stop 里。
 */
class TaskbarLyricsPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {

    override fun start() {
        try {
            val settings = PluginSettings.load()
            PluginRuntime.attach(settings, pluginContext.pluginVersion)
            PluginRuntime.start(notify = true)
        } catch (t: Throwable) {
            // 启动阶段抛异常会导致插件启动失败，这里兜住并给出可读原因
            WorkshopApi.ui.toast("任务栏歌词插件启动异常：${t.message}", WorkshopApi.Ui.ToastType.Error)
            println("[TaskbarLyrics] start 失败：${t.stackTraceToString()}")
        }
    }

    override fun stop() {
        PluginRuntime.stop()
        PluginRuntime.detach()
    }

    override fun delete() {
        PluginRuntime.stop()
        PluginRuntime.detach()
    }

    override fun update() {
        // 更新时先收掉旧实例，用户重新启用后会在 start 中重新拉起
        PluginRuntime.stop()
    }
}
