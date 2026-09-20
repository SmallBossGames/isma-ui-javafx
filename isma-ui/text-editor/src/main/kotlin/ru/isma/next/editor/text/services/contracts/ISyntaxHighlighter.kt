package ru.isma.next.editor.text.services.contracts

enum class SyntaxTokenKind {
    UNSPECIFIED,
    KEYWORD,
    COMMENT,
    NUMBER,
    TEXT,
}

data class SyntaxToken(
    val line: Int,
    val startChar: Int,
    val length: Int,
    val kind: SyntaxTokenKind,
)

interface ISyntaxHighlighter {
    /**
     * Requests semantic tokens for the full document. The document is
     * identified by [documentId]; the first call opens it on the provider,
     * subsequent calls update it.
     */
    suspend fun highlight(documentId: String, source: String): List<SyntaxToken>

    /** Releases server-side state for the document. */
    fun closeDocument(documentId: String)
}
