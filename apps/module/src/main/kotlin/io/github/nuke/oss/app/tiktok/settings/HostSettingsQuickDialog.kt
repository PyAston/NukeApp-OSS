package io.github.nuke.oss.app.tiktok.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import io.github.nuke.oss.ui.hostsettings.featureLabels
import android.util.Log
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
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

    fun show(activity: Activity, session: HostSettingsSession, moduleContext: Context) {
        if (activity.isFinishing || activity.isDestroyed) return
        val entries = session.entries()
        val names: Array<CharSequence> = entries.map { feature ->
            val label = featureLabels[feature.id]
            val title = label?.let { safeString(moduleContext, it.title) } ?: feature.id
            val description = label?.let { safeString(moduleContext, it.description) }
            if (description.isNullOrBlank()) title else {
                val text = SpannableString("$title\n$description")
                val start = title.length + 1
                text.setSpan(RelativeSizeSpan(0.82f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text
            }
        }.toTypedArray()
        val selected = entries.map { it.configured }.toBooleanArray()
        AlertDialog.Builder(activity)
            .setTitle("NukeApp-OSS · 更改后重启抖音生效")
            .setMultiChoiceItems(names, selected) { _, which, checked ->
                val feature = entries[which]
                try {
                    session.set(feature.id, checked)
                    Log.i(TAG, "quick_setting_changed feature=${feature.id} enabled=$checked")
                } catch (error: RuntimeException) {
                    (dialog as? AlertDialog)?.listView?.setItemChecked(which, !checked)
                    Log.e(TAG, "quick_setting_failed feature=${feature.id}", error)
                    android.widget.Toast.makeText(activity, "保存失败：${feature.id}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("完成", null)
            .show()
        Log.i(TAG, "quick_settings_shown count=${entries.size}")
    }
    private fun safeString(context: Context, resourceId: Int): String? =
        try {
            context.getString(resourceId)
        } catch (_: android.content.res.Resources.NotFoundException) {
            null
        }
}
