package ru.isma.next.editor.text

import javafx.beans.property.Property
import javafx.beans.property.SimpleStringProperty
import javafx.scene.layout.BorderPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import ru.isma.next.editor.text.services.contracts.IEditorPlatformService
import ru.isma.next.editor.text.services.contracts.IHighlightingService

class IsmaTextEditor(
    private val textEditorService: IEditorPlatformService,
    private val highlightingService: IHighlightingService,
) : BorderPane() {
    private val area: CodeArea

    private val text = SimpleStringProperty()

    private val fxCoroutineScope = CoroutineScope(Dispatchers.JavaFx)

    private val documentId: String = highlightingService.newDocumentId()

    @Volatile
    private var highlightVersion = 0

    init {
        area = CodeArea().apply {
            style = "-fx-font-family: consolas; -fx-font-size: 12pt;"

            fxCoroutineScope.launch {
                textEditorService.cutEvent.collect{
                    if (isFocused) cut()
                }
            }

            fxCoroutineScope.launch {
                textEditorService.copyEvent.collect{
                    if (isFocused) copy()
                }
            }

            fxCoroutineScope.launch {
                textEditorService.pasteEvent.collect{
                    if (isFocused) paste()
                }
            }

            textProperty().addListener { _, _, newValue ->
                if (paragraphGraphicFactory == null) {
                    paragraphGraphicFactory = LineNumberFactory.get(this)
                }

                val source = newValue ?: ""
                val version = ++highlightVersion
                fxCoroutineScope.launch {
                    val highlighting = highlightingService.createHighlightingStyleSpans(documentId, source)
                    if (version == highlightVersion) {
                        setStyleSpans(0, highlighting)
                    }
                }
            }
        }

        area.textProperty().addListener { _, _, newValue ->
            if (text.value != newValue) {
                text.value = newValue
            }
        }
        text.addListener { _, _, newValue ->
            if (area.text != newValue) {
                area.replaceText(newValue)
            }
        }

        center = area
    }

    fun textProperty(): Property<String> = text

    fun replaceText(text: String) = area.replaceText(text)

    fun dispose() {
        highlightVersion++
        fxCoroutineScope.cancel()

        highlightingService.closeDocument(documentId)
        area.dispose()
    }
}
