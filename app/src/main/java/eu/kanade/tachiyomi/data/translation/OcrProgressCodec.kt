package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState

/**
 * Strict, bitmap-free persistence adapter. The engine has no serialization dependency, and the
 * surrounding page journal separately binds this payload to the original image and raw mask.
 * Corruption is an explicit failure; no missing state is guessed as completed or ignored.
 */
internal object OcrProgressCodec {
    // The journal embeds this as a JSON string, which can expand again during its own encoding.
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    fun encode(progress: PageProgress): String {
        progress.validateFor(progress.width, progress.height)
        // Reject oversized OCR/target strings before constructing the encoded JSON allocation.
        var characters = 0L
        progress.regions.forEach { region ->
            characters += region.translatedText.length
            region.lines.forEach { characters += it.text.length }
            require(characters <= MAX_PAYLOAD_BYTES) { "OCR progress payload is too large" }
        }
        return json.encodeToString(Payload.from(progress)).also(::requireSize)
    }

    fun decode(payload: String, width: Int, height: Int): PageProgress {
        requireSize(payload)
        return json.decodeFromString<Payload>(payload).toProgress().also { it.validateFor(width, height) }
    }

    private fun requireSize(payload: String) {
        require(payload.isNotEmpty() && payload.length <= MAX_PAYLOAD_BYTES) { "Invalid OCR progress payload size" }
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "OCR progress payload is too large" }
    }

    @Serializable
    private data class Payload(
        val schemaVersion: Int,
        val width: Int,
        val height: Int,
        val regions: List<Region>,
        val noTextDetected: Boolean = false,
    ) {
        fun toProgress(): PageProgress = PageProgress(
            width,
            height,
            regions.map { it.toRegion() },
            schemaVersion,
            noTextDetected,
        )

        companion object {
            fun from(progress: PageProgress): Payload = Payload(
                progress.schemaVersion,
                progress.width,
                progress.height,
                progress.regions.map { Region.from(it) },
                progress.noTextDetected,
            )
        }
    }

    @Serializable
    private data class Region(
        val id: Int,
        val state: String,
        val lines: List<Line>,
        val direction: String,
        val angle: Float,
        val cx: Float,
        val cy: Float,
        val boxW: Float,
        val boxH: Float,
        val translatedText: String,
        val onArt: Boolean,
    ) {
        fun toRegion(): RegionProgress = RegionProgress(
            id,
            RegionState.valueOf(state),
            lines.map { it.toLine() },
            direction,
            angle,
            cx,
            cy,
            boxW,
            boxH,
            translatedText,
            onArt,
        )

        companion object {
            fun from(region: RegionProgress): Region = Region(
                region.id,
                region.state.name,
                region.lines.map { Line.from(it) },
                region.direction,
                region.angle,
                region.cx,
                region.cy,
                region.boxW,
                region.boxH,
                region.translatedText,
                region.onArt,
            )
        }
    }

    @Serializable
    private data class Line(
        val quad: List<Point>,
        val score: Float,
        val direction: String,
        val text: String,
        val intentionallyKept: Boolean,
    ) {
        fun toLine(): OcrLineProgress = OcrLineProgress(
            quad.map { Pt(it.x, it.y) },
            score,
            direction,
            text,
            intentionallyKept,
        )

        companion object {
            fun from(line: OcrLineProgress): Line = Line(
                line.quad.map { Point(it.x, it.y) },
                line.score,
                line.direction,
                line.text,
                line.intentionallyKept,
            )
        }
    }

    @Serializable
    private data class Point(val x: Float, val y: Float)
}
