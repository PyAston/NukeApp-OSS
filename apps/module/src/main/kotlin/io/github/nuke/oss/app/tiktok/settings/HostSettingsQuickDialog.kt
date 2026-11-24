package io.github.nuke.oss.app.tiktok.settings

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import io.github.nuke.oss.app.shared.settings.HostSettingsSession

/**
 * Safe, in-process settings fallback for Android 11 / Mira.
 *
 * This never starts a second host Activity or replaces Instrumentation's
 * Activity instance. It uses the live session and writes the same switches
 * as HostSettingsActivity. Advanced settings remain in the Compose screen.
 */
internal object HostSettingsQuickDialog {
    private const val TAG = "NukeApp-OSS/Handoff"

    fun show(activity: Activity, session: HostSettingsSession) {
        if (activity.isFinishing || activity.isDestroyed) return
        val entries = session.entries()
        val names = entries.map { it.id }.toTypedArray()
        val selected = entries.map { it.configured }.toBooleanArray()
        AlertDialog.Builder(activity)
            .setTitle("NukeApp-OSS")
            .setMessage("功能开关会保存到模块配置，部分更改需要重启抖音才能生效。高级设置尚未迁移到此兼容界面。")
            .setMultiChoiceItems(names, selected) { _, which, checked ->
                val feature = entries[which]
                try {
                    session.set(feature.id, checked)
                    Log.i(TAG, "quick_setting_changed feature=${feature.id} enabled=$checked")
                } catch (error: RuntimeException) {
                    Log.e(TAG, "quick_setting_failed feature=${feature.id}", error)
                    android.widget.Toast.makeText(activity, "保存失败：${feature.id}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("完成", null)
            .show()
        Log.i(TAG, "quick_settings_shown count=${entries.size}")
    }
}
