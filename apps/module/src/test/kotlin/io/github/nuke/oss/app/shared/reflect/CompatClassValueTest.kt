package io.github.nuke.oss.app.shared.reflect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CompatClassValueTest {
    @Test fun cachesNonNullByClassIdentity() {
        var calls = 0
        val values = object : CompatClassValue<Any>() {
            override fun computeValue(type: Class<*>): Any {
                calls++
                return Any()
            }
        }
        assertSame(values.get(String::class.java), values.get(String::class.java))
        values.get(Int::class.java)
        assertEquals(2, calls)
    }

    @Test fun cachesNullResults() {
        var calls = 0
        val values = object : CompatClassValue<String?>() {
            override fun computeValue(type: Class<*>): String? {
                calls++
                return null
            }
        }
        assertNull(values.get(String::class.java))
        assertNull(values.get(String::class.java))
        assertEquals(1, calls)
    }
}
