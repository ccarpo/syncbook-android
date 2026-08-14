package com.ccarpo.syncbook

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import uniffi.syncbook.BlockKind
import uniffi.syncbook.SyncDoc

class SyncDocTest {
    @Test
    fun twoDocumentsConvergeThroughFramedMessages() {
        val left = SyncDoc()
        val right = SyncDoc()
        try {
            left.insertText("", 0u, "")
            val blockId = left.blocks().single().id
            left.insertText(blockId, 0u, "hello")

            exchangeSync(left, right)

            assertEquals("hello", right.blocks().single().text)
        } finally {
            left.close()
            right.close()
        }
    }

    @Test
    fun concurrentSameOffsetInsertsBothSurvive() {
        val left = SyncDoc()
        val right = SyncDoc()
        try {
            left.insertText("", 0u, "")
            exchangeSync(left, right)
            val leftId = left.blocks().single().id
            val rightId = right.blocks().single().id

            left.insertText(leftId, 0u, "left")
            right.insertText(rightId, 0u, "right")
            exchangeUpdates(left, right)

            val leftText = left.blocks().single().text
            assertTrue(leftText.contains("left"))
            assertTrue(leftText.contains("right"))
            assertEquals(leftText, right.blocks().single().text)
        } finally {
            left.close()
            right.close()
        }
    }

    @Test
    fun checkedStateRoundTrips() {
        SyncDoc().use { doc ->
            doc.insertText("", 0u, "")
            val paragraphId = doc.blocks().single().id
            doc.toggleTaskList(paragraphId)
            val taskId = doc.blocks().single().id
            doc.setChecked(taskId, true)

            val block = doc.blocks().single()
            assertEquals(BlockKind.TASK_ITEM, block.kind)
            assertTrue(block.checked)
        }
    }

    @Test
    fun snapshotLoadsIntoFreshDocument() {
        SyncDoc().use { original ->
            original.insertText("", 0u, "")
            val blockId = original.blocks().single().id
            original.insertText(blockId, 0u, "snapshot")
            val snapshot = original.encodeStateAsUpdate(null)

            SyncDoc().use { restored ->
                restored.applyUpdate(snapshot)
                assertEquals("snapshot", restored.blocks().single().text)
            }
        }
    }

    private fun exchangeSync(left: SyncDoc, right: SyncDoc) {
        for (reply in right.handleMessage(syncStep1(left))) {
            left.handleMessage(reply)
        }
        for (reply in left.handleMessage(syncStep1(right))) {
            right.handleMessage(reply)
        }
    }

    private fun exchangeUpdates(left: SyncDoc, right: SyncDoc) {
        val leftUpdate = left.encodeStateAsUpdate(right.stateVector())
        right.applyUpdate(leftUpdate)
        val rightUpdate = right.encodeStateAsUpdate(left.stateVector())
        left.applyUpdate(rightUpdate)
    }

    private fun syncStep1(doc: SyncDoc): List<UByte> {
        val vector = doc.stateVector()
        return listOf(0u.toUByte(), 0u.toUByte()) + varUint(vector.size) + vector
    }

    private fun varUint(value: Int): List<UByte> {
        var remaining = value
        val encoded = mutableListOf<UByte>()
        do {
            var byte = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) {
                byte = byte or 0x80
            }
            encoded += byte.toUByte()
        } while (remaining != 0)
        return encoded
    }
}
