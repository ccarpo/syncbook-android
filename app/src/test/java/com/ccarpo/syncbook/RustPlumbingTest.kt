package com.ccarpo.syncbook

import org.junit.Test
import uniffi.syncbook.Greeting
import kotlin.test.assertEquals

class RustPlumbingTest {
    @Test
    fun greetingCrossesTheUniFfiBoundary() {
        Greeting().use { greeting ->
            assertEquals("Hello, JVM from Rust", greeting.hello("JVM"))
        }
    }
}
