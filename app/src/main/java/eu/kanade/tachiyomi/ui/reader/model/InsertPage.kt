package eu.kanade.tachiyomi.ui.reader.model

class InsertPage(val parent: ReaderPage) : ReaderPage(parent.index, parent.url, parent.imageUrl) {

    override var chapter: ReaderChapter = parent.chapter
    override val displayStream get() = parent.displayStream
    override val isOriginalPreview get() = parent.isOriginalPreview
    override fun showOriginalPreview(open: () -> java.io.InputStream) = parent.showOriginalPreview(open)
    override fun clearOriginalPreview() = parent.clearOriginalPreview()

    init {
        status = State.Ready
        stream = parent.stream
        uri = parent.uri
    }
}
