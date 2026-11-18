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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.captcha.lab.CaptchaSolverSimulationReport
import com.example.data.captcha.lab.SolverSimulationProgressState
import com.example.ui.theme.TealPrimary

@Composable
internal fun SolverSimulationProgressCard(
    progress: SolverSimulationProgressState,
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
            .testTag("captcha_lab_simulation_progress_card")
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
                        text = "شبیه‌سازی حل‌گر جدید (${progress.scopeLabel})",
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
                        .testTag("captcha_lab_simulation_cancel_button")
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
internal fun SolverSimulationComparisonCard(
    report: CaptchaSolverSimulationReport,
    onDismiss: () -> Unit
) {
    val prev = report.previous
    val sim = report.simulated
    val successDelta = sim.successRatePercent - prev.successRatePercent
    val positiveColor = Color(0xFF2E7D32)
    val negativeColor = Color(0xFFC62828)
    val deltaColor = when {
        successDelta > 0 -> positiveColor
        successDelta < 0 -> negativeColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.2.dp, TealPrimary.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("captcha_lab_simulation_report_card")
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
                        Icons.AutoMirrored.Filled.CompareArrows,
                        contentDescription = null,
                        tint = TealPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "مقایسه حل‌گر قبلی با حل‌گر جدید",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "محدوده: ${report.scopeLabel} (${report.evaluatedCount} کپچا${if (report.cancelled) " • متوقف‌شده" else ""})",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("captcha_lab_simulation_dismiss_button")
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "بستن گزارش شبیه‌سازی"
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TealPrimary.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "نرخ موفقیت خودکار",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = "${prev.successRatePercent}%  ->  ${sim.successRatePercent}%",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = TealPrimary
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = deltaColor.copy(alpha = 0.14f)
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            val deltaText = when {
                                successDelta > 0 -> "+$successDelta%"
                                else -> "$successDelta%"
                            }
                            Text(
                                text = deltaText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = deltaColor,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            SimulationMetricRow(
                label = "موفق در تلاش اول (Primary)",
                prevValue = "${prev.primarySuccessCount}",
                simValue = "${sim.primarySuccessCount}",
                delta = sim.primarySuccessCount - prev.primarySuccessCount,
                higherIsBetter = true
            )
            SimulationMetricRow(
                label = "نجات‌یافته در Fallback",
                prevValue = "${prev.fallbackSuccessCount}",
                simValue = "${sim.fallbackSuccessCount}",
                delta = sim.fallbackSuccessCount - prev.fallbackSuccessCount,
                higherIsBetter = true
            )
            SimulationMetricRow(
                label = "بدون متن (NO_TEXT)",
                prevValue = "${prev.noTextCount}",
                simValue = "${sim.noTextCount}",
                delta = sim.noTextCount - prev.noTextCount,
                higherIsBetter = false
            )
            SimulationMetricRow(
                label = "رد یا خطا",
                prevValue = "${prev.rejectedOrErrorCount}",
                simValue = "${sim.rejectedOrErrorCount}",
                delta = sim.rejectedOrErrorCount - prev.rejectedOrErrorCount,
                higherIsBetter = false
            )

            if (sim.labeledEvaluatedCount > 0) {
                val prevExact = prev.exactMatchRatePercent?.let { "$it% (${prev.exactMatchCount}/${prev.labeledEvaluatedCount})" } ?: "—"
                val simExact = sim.exactMatchRatePercent?.let { "$it% (${sim.exactMatchCount}/${sim.labeledEvaluatedCount})" } ?: "—"
                SimulationMetricRow(
                    label = "دقت واقعی روی برچسب‌دارها",
                    prevValue = prevExact,
                    simValue = simExact,
                    delta = sim.exactMatchCount - prev.exactMatchCount,
                    higherIsBetter = true
                )
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MetaTag("prevAvg=${prev.averageDurationMs}ms")
                    MetaTag("newAvg=${sim.averageDurationMs}ms")
                    MetaTag("labeled=${sim.labeledEvaluatedCount}")
                    if (report.skippedMissingRawCount > 0) {
                        MetaTag("missingRaw=${report.skippedMissingRawCount}")
                    }
                }
            }
        }
    }
}

@Composable
private fun SimulationMetricRow(
    label: String,
    prevValue: String,
    simValue: String,
    delta: Int,
    higherIsBetter: Boolean
) {
    val good = if (higherIsBetter) delta > 0 else delta < 0
    val bad = if (higherIsBetter) delta < 0 else delta > 0
    val deltaColor = when {
        good -> Color(0xFF2E7D32)
        bad -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "$prevValue -> $simValue",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                val deltaSign = if (delta > 0) "+$delta" else "$delta"
                Text(
                    text = "($deltaSign)",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = deltaColor
                )
            }
        }
    }
}
