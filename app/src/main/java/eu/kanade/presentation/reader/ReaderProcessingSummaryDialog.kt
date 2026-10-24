package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.translation.PageProcessingDetails
import eu.kanade.tachiyomi.data.translation.PageProcessingReason
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.DateFormat
import java.util.Date

@Composable
fun ReaderProcessingSummaryDialog(
    onDismissRequest: () -> Unit,
    details: PageProcessingDetails?,
    isLoading: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.reader_processing_record)) },
        text = {
            when {
                isLoading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(MR.strings.loading))
                }
                details == null -> Text(stringResource(MR.strings.reader_processing_record_missing))
                else -> Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(MR.strings.reader_processing_record_saved_snapshot),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val recordedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                        .format(Date(details.recordedAtEpochMs))
                    Text(stringResource(MR.strings.reader_processing_record_time, recordedAt))
                    val reason = when (details.outcome.reason) {
                        PageProcessingReason.TRANSLATED -> MR.strings.reader_processing_record_translated
                        PageProcessingReason.PARTIAL -> MR.strings.reader_processing_record_partial
                        PageProcessingReason.NO_TEXT -> MR.strings.reader_processing_record_no_text
                        PageProcessingReason.OCR_POLICY_KEPT -> MR.strings.reader_processing_record_ocr_kept
                        PageProcessingReason.RESPONSE_FILTERED -> MR.strings.reader_processing_record_response_kept
                        PageProcessingReason.MIXED_KEPT -> MR.strings.reader_processing_record_mixed_kept
                        PageProcessingReason.UNKNOWN -> MR.strings.reader_processing_record_unknown
                    }
                    Text(stringResource(reason))
                    details.outcome.counts?.let { counts ->
                        counts.detectedLines?.let { lines ->
                            Text(stringResource(MR.strings.reader_processing_record_detected_lines, lines))
                        }
                        Text(stringResource(MR.strings.reader_processing_record_regions, counts.regions))
                        Text(
                            stringResource(
                                MR.strings.reader_processing_record_translated_regions,
                                counts.translatedRegions,
                            ),
                        )
                        Text(
                            stringResource(
                                MR.strings.reader_processing_record_ocr_kept_regions,
                                counts.ocrPolicyKeptRegions,
                            ),
                        )
                        Text(
                            stringResource(
                                MR.strings.reader_processing_record_ocr_kept_lines,
                                counts.ocrPolicyKeptLines,
                            ),
                        )
                        Text(
                            stringResource(
                                MR.strings.reader_processing_record_response_kept_regions,
                                counts.responseFilteredRegions,
                            ),
                        )
                        Text(stringResource(MR.strings.reader_processing_record_pending_regions, counts.pendingRegions))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
    )
}
