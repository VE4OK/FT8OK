package com.ft8ok.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException

/**
 * Writes ADIF files into a folder the operator picked with the Storage Access
 * Framework (`ACTION_OPEN_DOCUMENT_TREE`). Needs no storage permission (rule 1):
 * the picker grants access to that one folder, and the app must call
 * `takePersistableUriPermission` on [treeUri] when it is picked so the grant
 * survives a restart.
 *
 * A visible folder, not `Android/data`, which file managers on newer Android hide.
 */
class SafAdifSink(private val context: Context, private val treeUri: Uri) : AdifSink {

    override fun write(fileName: String, text: String) {
        val resolver = context.contentResolver
        val uri = find(resolver, fileName)
            ?: DocumentsContract.createDocument(resolver, folder(), MIME, fileName)
            ?: throw IOException("could not create $fileName")
        // "wt", not "w": without truncation some providers leave the tail of a longer
        // previous version on the end of the file.
        (resolver.openOutputStream(uri, "wt") ?: throw IOException("could not open $fileName"))
            .use { it.write(text.toByteArray(Charsets.US_ASCII)) }
    }

    private fun folder(): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun find(resolver: ContentResolver, fileName: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == fileName) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                    }
                }
            }
        return null
    }

    private companion object {
        // Not text/plain: some providers append ".txt" to a text/plain document's name.
        const val MIME = "application/octet-stream"
    }
}
