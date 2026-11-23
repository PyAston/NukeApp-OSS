package io.github.nuke.oss.app.shared.settings

import io.github.nuke.oss.app.shared.script.ScriptRuntime

import android.content.Intent
import java.io.File
import io.github.nuke.oss.app.shared.diagnostics.DiagnosticsStore
import io.github.nuke.oss.app.shared.environment.EnvironmentInfo
import io.github.nuke.oss.app.shared.environment.ModuleVersion
import io.github.nuke.oss.app.shared.init.SetupStore

// What a host adapter hands to the settings Activity that runs inside the same host process.
internal class HostSettingsSession(
    val host: String,
    private val controller: HostSettingsController,
    val ui: UiConfigStore,
    val setup: SetupStore,
    private val readAccount: () -> HostAccount,
    val contacts: HostContacts,
    val diagnostics: DiagnosticsStore,
    val config: ModuleConfig,
    private val readEnvironment: (ModuleVersion) -> EnvironmentInfo,
    // Null when the host's script store could not be opened; the scripts page says so.
    val scripts: ScriptRuntime? = null,
    val activity: FeatureActivity = FeatureActivity { emptyList() },
    // Null when the host has no protocol sender.
    val protocol: ProtocolConsole? = null,
    // Where a feature's settings keep files, one subdirectory per feature ID; null when the host keeps none.
    val featureFiles: File? = null,
    // Null when the host has no pet feeding.
    val pets: PetConsole? = null,
    // Null when the host has no MCP server.
    val mcp: McpConsole? = null,
) {
    fun environment(version: ModuleVersion): EnvironmentInfo = readEnvironment(version)
    fun account(): HostAccount = readAccount()
    fun entries(): List<HostFeature> = controller.entries()
    fun set(featureId: String, enabled: Boolean): List<HostFeature> = controller.set(featureId, enabled)
    fun setOption(featureId: String, key: String, value: Any): List<HostFeature> = controller.setOption(featureId, key, value)
}

// A Binder extra may be proxied or changed when an Intent is parcelled through
// ActivityTaskManager. Keep the actual session in the injected process, and only
// send an unguessable token as a String extra. A token from another process is
// not in this map. Re-creation after process death deliberately gets no session.
internal object HostSettingsHandoff {
    const val ACTIVITY = "io.github.nuke.oss.ui.hostsettings.HostSettingsActivity"
    private const val KEY_SESSION = "io.github.nuke.oss.app.shared.settings.SESSION_TOKEN"
    private const val MAX_SESSIONS = 8
    private val sessions = LinkedHashMap<String, HostSettingsSession>()

    fun intent(stub: Intent, session: HostSettingsSession): Intent {
        val token = java.util.UUID.randomUUID().toString()
        synchronized(sessions) {
            sessions[token] = session
            while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
        }
        return stub.putExtra(KEY_SESSION, token)
    }

    fun sessionOf(intent: Intent?): HostSettingsSession? {
        val token = intent?.getStringExtra(KEY_SESSION) ?: return null
        return synchronized(sessions) { sessions[token] }
    }

    fun hasToken(intent: Intent?): Boolean = intent?.hasExtra(KEY_SESSION) == true
}
