package ru.isma.next.app.services.editors

import ru.isma.next.editor.text.services.contracts.ISyntaxHighlighter
import ru.isma.next.editor.text.services.contracts.SyntaxToken
import ru.isma.next.editor.text.services.contracts.SyntaxTokenKind
import ru.isma.next.external.lsp.LspClient

class LspSyntaxHighlighter(
    private val lspClient: LspClient,
) : ISyntaxHighlighter {

    override suspend fun highlight(documentId: String, source: String): List<SyntaxToken> =
        lspClient.semanticTokens(documentId, source).map { token ->
            SyntaxToken(
                line = token.line,
                startChar = token.startChar,
                length = token.length,
                kind = when (token.type) {
                    "keyword" -> SyntaxTokenKind.KEYWORD
                    "comment" -> SyntaxTokenKind.COMMENT
                    "number" -> SyntaxTokenKind.NUMBER
                    else -> SyntaxTokenKind.TEXT
                },
            )
        }

    override fun closeDocument(documentId: String) {
        lspClient.closeDocument(documentId)
    }
}
