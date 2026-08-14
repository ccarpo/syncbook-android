package com.ccarpo.syncbook

import android.content.Context
import uniffi.syncbook.SyncDoc

class NotePersistence(context: Context) {
    private val directory = context.filesDir.resolve("notes").also { it.mkdirs() }

    fun load(noteId: String, doc: SyncDoc) {
        val file = directory.resolve("$noteId.yrs")
        if (file.exists()) {
            doc.applyUpdate(file.readBytes().map { it.toUByte() })
        }
    }

    fun save(noteId: String, doc: SyncDoc) {
        directory.resolve("$noteId.yrs").writeBytes(
            doc.encodeStateAsUpdate(null).map { it.toByte() }.toByteArray(),
        )
    }
}
