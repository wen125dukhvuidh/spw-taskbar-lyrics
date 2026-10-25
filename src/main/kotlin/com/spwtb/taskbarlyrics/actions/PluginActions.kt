package com.spwtb.taskbarlyrics.actions

import com.spwtb.taskbarlyrics.PluginRuntime
import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * 配置界面按钮的入口。
 *
 * preference_config.json 的 `on_click` 只能指向「公开、静态、无参」的方法，
 * 所以这里用伴生对象配合 @JvmStatic 暴露真正的静态方法。
 */
class PluginActions {
    companion object {

        @JvmStatic
        fun restartRenderer() {
            if (PluginRuntime.restart()) {
                WorkshopApi.ui.toast(
                    "任务栏歌词已重启；${PluginRuntime.describe()}",
                    WorkshopApi.Ui.ToastType.Success
                )
            } else {
                WorkshopApi.ui.toast(
                    "重启失败：${PluginRuntime.describe()}",
                    WorkshopApi.Ui.ToastType.Error
                )
            }
        }

        @JvmStatic
        fun showStatus() {
            WorkshopApi.ui.toast(PluginRuntime.describe(), WorkshopApi.Ui.ToastType.Success)
        }

        /**
         * 还原默认设置。直接把五组配置写回默认值，然后重建窗口。
         *
         * 没有二次确认的入口可用（宿主的按钮点击没有再弹窗的能力），
         * 所以界面上那句说明里写了「立即生效、不可撤销」。
         *
         * ⚠️ 宿主配置页不会自动刷新：它在打开时把值读进自己的状态，之后既不订阅
         * 配置变更（全宿主只有 ConfigManager 接口/实现引用 addConfigChangeListener），
         * 也不会重新 reload（JsonConfigHelper 只在构造时 reload 一次）。
         * 所以这里**必须**在提示里让用户关掉再打开，否则看起来像"点了没反应"。
         */
        @JvmStatic
        fun resetToDefaults() {
            val ok = PluginRuntime.resetSettings()
            if (ok) {
                WorkshopApi.ui.toast(
                    "已还原默认设置（${PluginRuntime.defaultsSummary()}）。" +
                        "本页仍显示打开时的旧值，关闭后重新打开即可看到",
                    WorkshopApi.Ui.ToastType.Success
                )
            } else {
                WorkshopApi.ui.toast(
                    "还原默认设置时部分项写入失败，请检查配置目录权限",
                    WorkshopApi.Ui.ToastType.Error
                )
            }
        }
    }
}
