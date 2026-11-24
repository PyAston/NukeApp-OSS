package io.github.nuke.oss.app.shared.activity

import android.app.Instrumentation
import android.util.Log
import android.content.Intent
import io.github.nuke.oss.app.shared.settings.HostSettingsHandoff
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.lang.reflect.Method

// Runs a module Activity inside a host process without declaring it in the host manifest. The caller starts a
// host Activity (the stub) that the system accepts; when ActivityThread then asks Instrumentation for that stub
// instance, this hook answers with the module Activity instead. The module class keeps the stub's task, window and
// lifecycle, so no ActivityThread, Handler or ActivityManager object has to be replaced.
// If the module Activity cannot be built, the host's own stub Activity opens unchanged and the reason is logged.
internal class ModuleActivityHook(
    private val newActivity: Method,
    private val create: (launchIntent: Any?) -> Any?,
    private val logger: Logger,
    private val featureId: String,
) {
    private val lock = Any()
    private val covered = HashSet<Method>()
    private val tag = "NukeApp-OSS/Handoff"
    private fun trace(message: String) { runCatching { Log.i(tag, message) } }
    private fun traceError(message: String, error: Throwable? = null) {
        runCatching { Log.e(tag, message, error) }
    }
    private var scope: FeatureScope? = null

    fun install(scope: FeatureScope) {
        synchronized(lock) { this.scope = scope }
        // Android 11 / LSPosed: hooking the second Instrumentation.newActivity
        // overload caused process instability. Restrict to the original,
        // previously working three-argument method.
        hook(newActivity)
        trace("handoff_hooks_installed count=${covered.size} mode=safe")
    }

    fun cover(instrumentation: Any) {
        var type: Class<*>? = instrumentation.javaClass
        while (type != null && type != newActivity.declaringClass) {
            // Do not hook another overload on android.app.Instrumentation:
            // this crashed the host on Android 11. Restrict additional hooks
            // to methods actually declared by the live Mira wrapper.
            for (method in type.declaredMethods) {
                if (method.name != "newActivity") continue
                val intentIndex = method.parameterTypes.indexOfFirst { it == Intent::class.java }
                trace("handoff_mira_method owner=${type.name} method=${method.name} argc=${method.parameterCount} intentIndex=$intentIndex returns=${method.returnType.name}")
                if (intentIndex < 0 || !android.app.Activity::class.java.isAssignableFrom(method.returnType)) continue
                try {
                    hook(method)
                } catch (error: RuntimeException) {
                    traceError("handoff_mira_hook_failed owner=${type.name} argc=${method.parameterCount}", error)
                } catch (error: LinkageError) {
                    traceError("handoff_mira_hook_linkage_failed owner=${type.name} argc=${method.parameterCount}", error)
                }
            }
            type = type.superclass
        }
        trace("handoff_cover instrumentation=${instrumentation.javaClass.name} hooks=${covered.size} mode=mira_only")
    }

    private fun hook(target: Method) {
        synchronized(lock) {
            if (target in covered) return
            val scope = checkNotNull(scope) { "ModuleActivityHook is not installed" }
            val intentIndex = target.parameterTypes.indexOfFirst { it == Intent::class.java }
                .takeIf { it >= 0 } ?: INTENT_INDEX
            scope.hooks.install(target, HookSpec(
                id = "ModuleActivity:${target.declaringClass.name}.${target.name}/${target.parameterCount}",
                before = { call ->
                    val launchIntent = call.argument(intentIndex)
                    val isModuleLaunch = HostSettingsHandoff.hasToken(launchIntent as? Intent)
                    if (isModuleLaunch) trace("handoff_intercept target=${target.declaringClass.name}.${target.name}/${target.parameterCount} intentIndex=$intentIndex")
                    val activity = try {
                        create(launchIntent)
                    } catch (error: ReflectiveOperationException) {
                        logger.log("feature=$featureId module_activity_failed $error")
                        if (isModuleLaunch) traceError("handoff_create_reflection_failed", error)
                        null
                    } catch (error: LinkageError) {
                        logger.log("feature=$featureId module_activity_failed $error")
                        if (isModuleLaunch) traceError("handoff_create_linkage_failed", error)
                        null
                    } catch (error: RuntimeException) {
                        if (isModuleLaunch) traceError("handoff_create_runtime_failed", error)
                        null
                    }
                    if (activity != null) {
                        if (isModuleLaunch) trace("handoff_created activity=${activity.javaClass.name}")
                        call.returnEarly(activity)
                    } else if (isModuleLaunch) {
                        traceError("handoff_not_claimed target=${target.declaringClass.name}/${target.parameterCount}")
                    }
                },
            ))
            covered += target
            if (target != newActivity) logger.log("feature=$featureId module_activity_covered class=${target.declaringClass.name}")
        }
    }

    companion object {
        // Instrumentation.newActivity(ClassLoader, String, Intent)
        private const val INTENT_INDEX = 2

        // The instance ActivityThread hands each launch to, after any wrapping the host has done.
        fun currentInstrumentation(): Instrumentation {
            val thread = "android.app.ActivityThread".toClass().resolve()
            val current = thread.firstMethod { name = "currentActivityThread"; emptyParameters() }.invoke()
            return thread.firstField { name = "mInstrumentation" }.of(current).get() as Instrumentation
        }
    }
}
