package com.example.ui.screens.captchalab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.captcha.lab.BatchBenchmarkProgressState
import com.example.data.captcha.lab.CaptchaBenchmarkTargetStatus
import com.example.data.captcha.lab.CaptchaLabRecord
import com.example.data.captcha.lab.CaptchaStageBenchmarkResult
import com.example.data.captcha.lab.CaptchaStageOcrTargetResult
import com.example.data.captcha.lab.StageCompatibilityMetric
import com.example.data.captcha.lab.StageCompatibilitySummary
import com.example.data.captcha.solver.CaptchaDigitValidator
import com.example.ui.theme.TealPrimary
import java.util.Locale

@Composable
internal fun BatchBenchmarkProgressCard(
    progress: BatchBenchmarkProgressState,
    onCancel: () -> Unit
) {
    val total = progress.totalCount.coerceAtLeast(1)
    val fraction = (progress.completedCount.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = TealPrimary.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, TealPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("captcha_lab_batch_progress_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "اجرای انبوه OCR (${progress.scopeLabel})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TealPrimary
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        val idHint = progress.currentCaptchaId?.take(12)?.let { " • $it" } ?: ""
                        Text(
                            text = "${progress.completedCount} / ${progress.totalCount}$idHint",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(
                    onClick = onCancel,
                    border = BorderStroke(1.dp, Color(0xFFC62828)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("captcha_lab_batch_cancel_button")
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        tint = Color(0xFFC62828),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "توقف",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC62828)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { fraction },
                color = TealPrimary,
                trackColor = TealPrimary.copy(alpha = 0.2f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StageCompatibilityLeaderboardCard(
    summary: StageCompatibilitySummary,
    isBatchRunning: Boolean,
    onRunBatchClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (summary.benchmarkedCaptchasCount > 0) {
                TealPrimary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stage_compatibility_leaderboard_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = TealPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "تحلیل سازگاری مراحل با مدل ML Kit",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (summary.benchmarkedCaptchasCount > 0) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("stage_leaderboard_toggle_expand_button")
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "جمع‌کردن رتبه‌بندی" else "بازکردن رتبه‌بندی"
                        )
                    }
                }
            }

            if (summary.benchmarkedCaptchasCount == 0) {
                Text(
                    text = "برای مقایسه دقت مدل ML Kit روی تصویر خام و ۸ مرحله پیش‌پردازش، دکمه OCR روی هر کپچا یا «اجرای انبوه OCR» را بزنید. برچسب دستی ۴ رقمی اختیاری، امتیازدهی انطباق دقیق را نیز فعال می‌کند.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onRunBatchClick,
                    enabled = !isBatchRunning,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("stage_leaderboard_quick_batch_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("اجرای انبوه OCR روی مراحل ۹گانه", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                val bestMetric = summary.metrics.firstOrNull { it.isRecommendedBest }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MetaTag("benchmarked=${summary.benchmarkedCaptchasCount}")
                        MetaTag("labeled=${summary.labeledCaptchasCount}")
                        if (summary.bestStageKey != null) {
                            MetaTag("best=${summary.bestStageKey}")
                        }
                    }
                }

                bestMetric?.let { best ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF2E7D32).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stage_leaderboard_best_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "بهترین مرحله برای مدل: ${best.targetTitleFa}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1B5E20)
                                    )
                                }
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    val exactStr = best.exactMatchRatePercent?.let { " | Exact $it%" } ?: ""
                                    val confStr = best.averageConfidence?.let { String.format(Locale.US, " | Conf %.2f", it) } ?: ""
                                    Text(
                                        text = "4-Digit ${best.valid4DigitRatePercent}%$exactStr$confStr | ${best.averageOcrDurationMs}ms",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = String.format(Locale.US, "%.1f", best.compatibilityScore),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF1B5E20)
                                )
                            }
                        }
                    }
                }

                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        summary.metrics.forEach { metric ->
                            CompatibilityMetricRow(metric = metric)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompatibilityMetricRow(metric: StageCompatibilityMetric) {
    val accent = if (metric.isRecommendedBest) Color(0xFF2E7D32) else TealPrimary
    val fraction = (metric.compatibilityScore / 100.0).toFloat().coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (metric.isRecommendedBest) {
            Color(0xFF2E7D32).copy(alpha = 0.06f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stage_leaderboard_row_${metric.targetKey}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${metric.rank}  ${metric.targetTitleFa}",
                    fontSize = 11.sp,
                    fontWeight = if (metric.isRecommendedBest) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = if (metric.evaluatedCount > 0) {
                            "${String.format(Locale.US, "%.1f", metric.compatibilityScore)} / 100"
                        } else {
                            "—"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (metric.evaluatedCount > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LinearProgressIndicator(
                progress = { if (metric.evaluatedCount > 0) fraction else 0f },
                color = accent,
                trackColor = accent.copy(alpha = 0.14f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val exactPart = metric.exactMatchRatePercent?.let { "Exact:$it%(${metric.exactMatchCount}/${metric.labeledEvaluatedCount}) " } ?: ""
                val confPart = metric.averageConfidence?.let { String.format(Locale.US, "Conf:%.2f ", it) } ?: "Conf:null "
                Text(
                    text = "n=${metric.evaluatedCount} | 4d:${metric.valid4DigitRatePercent}% | ${exactPart}${confPart}| NoTxt:${metric.noTextRatePercent}% | ${metric.averageOcrDurationMs}ms",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StageBenchmarkSectionCard(
    record: CaptchaLabRecord,
    selectedAttemptNumber: Int,
    isBenchmarkRunning: Boolean,
    isOcrModuleReady: Boolean = true,
    onRunStageOcr: () -> Unit,
    onSaveExpectedDigits: (String) -> Unit,
    onClearExpectedDigits: () -> Unit
) {
    var digitsInput by remember(record.captchaId, record.expectedDigits) {
        mutableStateOf(record.expectedDigits.orEmpty())
    }
    val normalizedInput = remember(digitsInput) {
        CaptchaDigitValidator.extractNormalizedDigits(digitsInput).take(CaptchaDigitValidator.REQUIRED_DIGIT_COUNT)
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, TealPrimary.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("captcha_detail_benchmark_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "آزمایش OCR روی تصویر خام + مراحل ۸گانه",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "با زدن این دکمه، مدل ML Kit به‌طور جداگانه روی تصویر خام و تمام ۸ مرحله تلاش #$selectedAttemptNumber اجرا و مقایسه می‌شود.",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onRunStageOcr,
                enabled = isOcrModuleReady && !isBenchmarkRunning,
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("captcha_detail_run_stage_ocr_button")
            ) {
                if (isBenchmarkRunning) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("در حال اجرای OCR مراحل ۹گانه...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isOcrModuleReady) {
                            "اجرای دستی OCR روی خام + ۸ مرحله"
                        } else {
                            "ابتدا موتور OCR را از گوگل‌پلی دانلود کنید"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider()

            // Optional manual ground-truth 4-digit label
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "برچسب صحیح ۴ رقمی (اختیاری برای دقت واقعی):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    record.expectedDigits?.let { saved ->
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = TealPrimary.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "ثبت‌شده: $saved",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = TealPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = digitsInput,
                            onValueChange = { raw ->
                                digitsInput = raw.filter { it.isDigit() }.take(CaptchaDigitValidator.REQUIRED_DIGIT_COUNT)
                            },
                            label = { Text("مثال: 4829", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("captcha_detail_expected_digits_input")
                        )
                    }
                    OutlinedButton(
                        onClick = { onSaveExpectedDigits(normalizedInput) },
                        enabled = normalizedInput.length == CaptchaDigitValidator.REQUIRED_DIGIT_COUNT &&
                            normalizedInput != record.expectedDigits,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("captcha_detail_save_expected_digits_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "ذخیره برچسب", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ثبت", fontSize = 11.sp)
                    }
                    if (record.expectedDigits != null || digitsInput.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                digitsInput = ""
                                onClearExpectedDigits()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("captcha_detail_clear_expected_digits_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "پاک‌کردن برچسب", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            val benchmark = record.stageBenchmark
            if (benchmark != null) {
                HorizontalDivider()
                BenchmarkResultsSection(benchmark = benchmark)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BenchmarkResultsSection(benchmark: CaptchaStageBenchmarkResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MetaTag("attempt=#${benchmark.attemptNumber}")
                MetaTag("totalBench=${benchmark.totalDurationMs}ms")
                benchmark.bestTargetKey?.let { MetaTag("bestStage=$it") }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            benchmark.targets.forEach { target ->
                StageOcrTargetResultCard(
                    target = target,
                    isBestForRecord = target.targetKey == benchmark.bestTargetKey
                )
            }
        }
    }
}

@Composable
private fun StageOcrTargetResultCard(
    target: CaptchaStageOcrTargetResult,
    isBestForRecord: Boolean
) {
    val borderColor = when {
        isBestForRecord -> Color(0xFF2E7D32)
        target.validationPassed -> TealPrimary.copy(alpha = 0.45f)
        target.status == CaptchaBenchmarkTargetStatus.FAILED -> Color(0xFFC62828).copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isBestForRecord) {
            Color(0xFF2E7D32).copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
        },
        border = BorderStroke(if (isBestForRecord) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stage_benchmark_target_${target.targetKey}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = target.targetTitleFa,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isBestForRecord) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF2E7D32)
                        ) {
                            Text(
                                text = "بهترین روی این کپچا",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    when (target.matchesExpectedDigits) {
                        true -> Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF2E7D32).copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "تطابق دقیق ✓",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B5E20),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        false -> Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFC62828).copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = "عدم تطابق ✕",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC62828),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        null -> Unit
                    }
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                if (target.status == CaptchaBenchmarkTargetStatus.COMPLETED) {
                    val dim = if (target.width != null && target.height != null) "${target.width}×${target.height}" else "-"
                    val conf = target.averageConfidence?.let { String.format(Locale.US, "%.3f", it) } ?: "null"
                    val digits = target.normalizedDigits ?: "null"
                    val validLabel = if (target.validationPassed) "VALID" else (target.rejectionCode ?: "INVALID")
                    Text(
                        text = "digits=$digits ($validLabel) | conf=$conf | ocr=${target.ocrDurationMs ?: "-"}ms | dim=$dim",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (target.validationPassed) TealPrimary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "raw=\"${target.rawOcrFullText.orEmpty()}\" | elements=${target.elementCount ?: 0}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "${target.status.labelFa}: ${target.errorMessage ?: "-"}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (target.status == CaptchaBenchmarkTargetStatus.FAILED) {
                            Color(0xFFC62828)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}
