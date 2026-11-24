package io.github.nuke.oss.app.tiktok.settings

import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinAccountReader
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinContacts
import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.ModulePackage
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.diagnostics.DiagnosticsStore
import io.github.nuke.oss.app.shared.init.SetupStore
import io.github.nuke.oss.app.shared.settings.FeatureActivity
import io.github.nuke.oss.app.shared.settings.McpConsole
import io.github.nuke.oss.app.shared.settings.PetConsole
import io.github.nuke.oss.app.shared.settings.ProtocolConsole
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.shared.settings.HostSettingsController
import io.github.nuke.oss.app.shared.script.ScriptRuntime
import io.github.nuke.oss.app.shared.settings.HostSettingsSession
import io.github.nuke.oss.app.shared.settings.ModuleConfig
import io.github.nuke.oss.app.shared.settings.UiConfigStore
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import io.github.nuke.oss.app.shared.environment.EnvironmentCollector

// The host settings row opens an in-process dialog. No Activity replacement or Mira hook needed.
internal class SettingsEntry(
    private val controller: HostSettingsController,
    private val ui: UiConfigStore,
    private val setup: SetupStore,
    private val diagnostics: DiagnosticsStore,
    private val config: ModuleConfig,
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val build: BuildIdentity,
    private val environment: EnvironmentCollector,
    private val logger: Logger,
    private val scripts: ScriptRuntime? = null,
    private val records: FeatureActivity = FeatureActivity { emptyList() },
    private val protocol: ProtocolConsole? = null,
    private val featureFiles: java.io.File? = null,
    private val pets: PetConsole? = null,
    private val mcp: McpConsole? = null,
) : RuntimeFeature {
    override val id = ID

    private val account = DouyinAccountReader(DouyinAccountReader.service(hostClassLoader)) { error ->
        logger.log("feature=$ID account_unavailable $error")
    }

    override fun install(scope: FeatureScope) {
        val activities = SETTINGS_ACTIVITIES.mapNotNull { name ->
            try { name.toClass(hostClassLoader) } catch (_: ClassNotFoundException) { null }
        }
        check(activities.isNotEmpty()) { "No known Douyin settings Activity: ${SETTINGS_ACTIVITIES.joinToString()}" }
        val contacts = DouyinContacts.open(resolver, hostClassLoader, logger, ID)
        val layout = SettingsLayout(hostClassLoader)
        for (activity in activities) {
            val fields = layout.fieldsOf(activity)
            val onCreate = activity.resolve().firstMethod { name = "onCreate"; parameters(Bundle::class) }.self
            scope.hooks.install(onCreate, HookSpec(
                id = "InjectEntry:${activity.simpleName}",
                after = { call ->
                    val host = call.receiver as? Activity
                    if (call.outcome is CallOutcome.Returned && host != null && !host.isFinishing && !host.isDestroyed) {
                        inject(host, layout, fields, contacts)
                    }
                },
            ))
        }
    }

    private fun inject(
        activity: Activity,
        layout: SettingsLayout,
        fields: SettingsLayout.Fields,
        contacts: HostContacts,
    ) {
        if (layout.isInserted(activity)) return
        val module = moduleContext(activity) ?: return
        val list = layout.findList(activity, fields) ?: error("Douyin settings list not found in ${activity.javaClass.name}")
        // The session outlives this Activity, so it keeps the application context for the report, not the Activity.
        val appContext = activity.applicationContext
        val session = HostSettingsSession(
            activity.packageName, controller, ui, setup, account::read, contacts, diagnostics, config,
            readEnvironment = { version -> environment.collect(appContext, version) },
            scripts = scripts,
            activity = records,
            protocol = protocol,
            featureFiles = featureFiles,
            pets = pets,
            mcp = mcp,
        )
        // A debug build is reinstalled under the same version name, so a host process still running the earlier code can
        // find the installed resources renumbered; the row is skipped then rather than failing the host's screen.
        val (label, icon) = try {
            module.getString(R.string.host_settings_label) to requireNotNull(module.resources.getDrawable(R.drawable.ic_settings_entry, module.theme))
        } catch (error: Resources.NotFoundException) {
            logger.log("feature=$ID entry_skipped module_resources_stale ${error.message}")
            return
        }
        val iconPlaced = layout.insertGroup(list, activity, label, icon) {
            open(activity, session, module)
        }
        logger.log("feature=$ID entry_injected activity=${activity.javaClass.name} icon=$iconPlaced")
        Log.i("NukeApp-OSS/Handoff", "handoff_entry_injected activity=${activity.javaClass.name}")
    }

    private fun moduleContext(activity: Activity): Context? = when (val opened = ModulePackage.open(activity, build)) {
        is ModulePackage.Opened.Ready -> opened.context
        is ModulePackage.Opened.Unavailable -> {
            logger.log("feature=$ID entry_skipped ${opened.reason}")
            null
        }
    }

    // Runs from a host click listener, outside Hook callback protection, so a failed launch must not escape.
    private fun open(activity: Activity, session: HostSettingsSession, moduleContext: Context) {
        // Mira does not route this host Activity through our safe newActivity
        // hook; launching its stub simply opens the Douyin settings again.
        // Display basic controls in the current process instead.
        try {
            Log.i("NukeApp-OSS/Handoff", "quick_settings_open_begin")
            HostSettingsQuickDialog.show(activity, session, moduleContext)
        } catch (error: RuntimeException) {
            logger.log("feature=$ID quick_settings_open_failed ${error.javaClass.name}: ${error.message}")
            Log.e("NukeApp-OSS/Handoff", "quick_settings_open_failed", error)
        } catch (error: LinkageError) {
            logger.log("feature=$ID quick_settings_open_failed ${error.javaClass.name}: ${error.message}")
            Log.e("NukeApp-OSS/Handoff", "quick_settings_linkage_failed", error)
        }
    }

    companion object {
        const val ID = "SettingsEntry"
        private val SETTINGS_ACTIVITIES = listOf(
            "com.ss.android.ugc.aweme.setting.ui.DouYinSettingNewVersionActivity",
            "com.ss.android.ugc.aweme.setting.ui.SettingNewVersionActivity",
        )
    }
}
