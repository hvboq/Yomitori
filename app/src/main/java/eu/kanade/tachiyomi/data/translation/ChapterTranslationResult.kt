package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.model.TranslationFailureCode

/** Per-attempt metadata; translated pages are distinct from chapter completion and partial progress. */
data class ChapterTranslationResult(
    val translatedPages: Int,
    val failureCode: TranslationFailureCode? = null,
)
