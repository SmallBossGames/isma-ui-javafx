package ru.isma.next.editor.text.services

import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
import ru.isma.next.editor.text.services.contracts.IHighlightingService
import ru.isma.next.editor.text.services.contracts.ISyntaxHighlighter
import ru.isma.next.editor.text.services.contracts.SyntaxTokenKind
import java.util.concurrent.atomic.AtomicInteger

class RemoteLismaHighlightingService(
    private val syntaxHighlighter: ISyntaxHighlighter,
) : IHighlightingService {
    private val documentCounter = AtomicInteger(0)

    override fun newDocumentId(): String = "doc-${documentCounter.incrementAndGet()}"

    override suspend fun createHighlightingStyleSpans(
        documentId: String,
        source: String,
    ): StyleSpans<Collection<String>>? {
        val tokens = syntaxHighlighter.highlight(documentId, source)

        val lineStarts = lineStartOffsets(source)
        val spansBuilder = StyleSpansBuilder<Collection<String>>()
        var lastEnd = 0

        tokens.sortedWith(compareBy({ it.line }, { it.startChar })).forEach { token ->
            val lineStart = lineStarts.getOrNull(token.line) ?: return@forEach
            var tokenStart = lineStart + token.startChar
            val tokenEnd = tokenStart + token.length
            if (tokenStart < lastEnd) tokenStart = lastEnd
            if (tokenEnd <= lastEnd) return@forEach

            if (tokenStart > lastEnd) {
                spansBuilder.add(listOf("syntax-default"), tokenStart - lastEnd)
            }

            val styleClass = when (token.kind) {
                SyntaxTokenKind.KEYWORD -> "syntax-keyword"
                SyntaxTokenKind.COMMENT -> "syntax-comment"
                SyntaxTokenKind.NUMBER -> "syntax-decimal"
                else -> "syntax-default"
            }

            spansBuilder.add(listOf(styleClass), tokenEnd - tokenStart)
            lastEnd = tokenEnd
        }

        if (lastEnd < source.length) {
            spansBuilder.add(listOf("syntax-default"), source.length - lastEnd)
        }

        return spansBuilder.create()
    }

    override fun closeDocument(documentId: String) {
        syntaxHighlighter.closeDocument(documentId)
    }

    private fun lineStartOffsets(source: String): List<Int> {
        val offsets = ArrayList<Int>(16)
        offsets.add(0)
        for (i in source.indices) {
            if (source[i] == '\n') {
                offsets.add(i + 1)
            }
        }
        return offsets
    }
}
