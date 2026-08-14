package com.ccarpo.syncbook

import org.junit.Test
import uniffi.syncbook.SyncDoc
import kotlin.test.assertEquals

class RustPlumbingTest {
    @Test
    fun syncDocCrossesTheUniFfiBoundary() {
        SyncDoc().use { doc ->
            doc.insertText("", 0u, "plumbing")
            assertEquals("plumbing", doc.blocks().single().text)
        }
    }
}
