package ru.isma.next.editor.text.services.contracts

import org.fxmisc.richtext.model.StyleSpans

interface IHighlightingService {
    /** Allocates a unique document id for a new editor instance. */
    fun newDocumentId(): String

    /**
     * Computes style spans for the full document. The document is identified
     * by [documentId]; the first call opens it on the provider, subsequent
     * calls update it.
     */
    suspend fun createHighlightingStyleSpans(documentId: String, source: String): StyleSpans<Collection<String>>?

    /** Releases provider state for the document. */
    fun closeDocument(documentId: String)
}
