package io.github.nuke.oss.app.shared.reflect

import java.util.WeakHashMap

/**
 * Android API 30 compatible replacement for java.lang.ClassValue (API 34+).
 *
 * Preserves per-Class identity, caches missing (null) values, and releases class
 * keys when no longer reachable. computeValue is invoked under the lock once.
 * No Android framework classes are referenced here, so this also runs in JVM tests.
 */
internal abstract class CompatClassValue<T> {
    private val cache = WeakHashMap<Class<*>, Any?>()
    private val missing = Any()

    protected abstract fun computeValue(type: Class<*>): T

    @Suppress("UNCHECKED_CAST")
    fun get(type: Class<*>): T = synchronized(cache) {
        val cached = cache[type]
        if (cached != null) return@synchronized (if (cached === missing) null else cached) as T
        val result = computeValue(type)
        cache[type] = result ?: missing
        result
    }
}
