package com.example.ui.screens.captchalab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GifBox
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.captcha.lab.CaptchaLabAttemptRecord
import com.example.data.captcha.lab.CaptchaLabOutcomeCategory
import com.example.data.captcha.lab.CaptchaLabRecord
import com.example.data.captcha.lab.CaptchaLabRepository
import com.example.data.captcha.lab.CaptchaStageKind
import com.example.data.captcha.lab.CaptchaStageStatus
import com.example.ui.components.PersianRtlLayout
import com.example.ui.theme.TealPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CaptchaLabDetailView(
    repository: CaptchaLabRepository,
    record: CaptchaLabRecord,
    isBenchmarkRunning: Boolean = false,
    isOcrModuleReady: Boolean = true,
    onRunStageBenchmark: (attemptNumber: Int?) -> Unit = {},
    onSaveExpectedDigits: (String?) -> Unit = {},
    onBack: () -> Unit,
    onDeleteRecord: () -> Unit,
    onOpenComparison: (CaptchaComparisonRequest) -> Unit
) {
    val initialAttempt = record.attempts.firstOrNull()?.attemptNumber ?: 1
    var selectedAttemptNumber by remember(record.captchaId) { mutableIntStateOf(initialAttempt) }
    val selectedAttempt = remember(record, selectedAttemptNumber) {
        record.attempts.firstOrNull { it.attemptNumber == selectedAttemptNumber }
            ?: record.attempts.firstOrNull()
    }

    PersianRtlLayout {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "جزئیات کپچا",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = record.captchaId.take(16),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("captcha_detail_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onDeleteRecord,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("captcha_detail_delete_button")
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "حذف این کپچا",
                                tint = Color(0xFFD32F2F)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 14.dp)
                    .testTag("captcha_detail_list"),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Outcome Summary Card
                item {
                    SummaryHeaderCard(record = record)
                }

                // 2. Raw Image + GIF Binary Inspection Card
                item {
                    RawAndGifInspectionCard(
                        repository = repository,
                        record = record,
                        onOpenComparison = {
                            onOpenComparison(
                                CaptchaComparisonRequest(
                                    record = record,
                                    initialAttemptNumber = selectedAttempt?.attemptNumber ?: 1,
                                    initialStageKind = CaptchaStageKind.FINAL_PADDED,
                                    startSideBySide = true
                                )
                            )
                        }
                    )
                }

                // 2.5 Manual Stage OCR Benchmark & Ground Truth Label
                item {
                    val activeAttemptNum = selectedAttempt?.attemptNumber ?: 1
                    StageBenchmarkSectionCard(
                        record = record,
                        selectedAttemptNumber = activeAttemptNum,
                        isBenchmarkRunning = isBenchmarkRunning,
                        isOcrModuleReady = isOcrModuleReady,
                        onRunStageOcr = { onRunStageBenchmark(selectedAttempt?.attemptNumber) },
                        onSaveExpectedDigits = { digits -> onSaveExpectedDigits(digits) },
                        onClearExpectedDigits = { onSaveExpectedDigits(null) }
                    )
                }

                // 3. Attempts Selector & Details
                if (record.attempts.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "هیچ تلاش پیش‌پردازش یا OCR برای این رکورد ثبت نشده است (دلیل: ${record.failureReason ?: record.finalStatus}).",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                } else {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "تلاش‌های ثبت‌شده (${record.attempts.size})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(record.attempts, key = { it.attemptNumber }) { attempt ->
                                    val badge = if (attempt.validationPassed) "✓" else "✕"
                                    FilterChip(
                                        selected = attempt.attemptNumber == selectedAttempt?.attemptNumber,
                                        onClick = { selectedAttemptNumber = attempt.attemptNumber },
                                        label = {
                                            Text(
                                                text = "$badge تلاش ${attempt.attemptNumber} (${attempt.routeType})",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        },
                                        modifier = Modifier.heightIn(min = 44.dp)
                                    )
                                }
                            }
                        }
                    }

                    selectedAttempt?.let { attempt ->
                        item(key = "attempt_${attempt.attemptNumber}") {
                            AttemptDetailCard(
                                repository = repository,
                                record = record,
                                attempt = attempt,
                                onOpenStageComparison = { stageKind ->
                                    onOpenComparison(
                                        CaptchaComparisonRequest(
                                            record = record,
                                            initialAttemptNumber = attempt.attemptNumber,
                                            initialStageKind = stageKind,
                                            startSideBySide = true
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryHeaderCard(record: CaptchaLabRecord) {
    val badgeColors = outcomeColorPair(record.outcomeCategory)
    val localTime = remember(record.capturedAtEpochMs) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(record.capturedAtEpochMs))
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, badgeColors.first.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutcomePill(category = record.outcomeCategory)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "${record.buildType.uppercase(Locale.US)} • v${record.appVersionName} (${record.appVersionCode})",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (!record.finalDigits.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ارقام نهایی:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = record.finalDigits,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = TealPrimary
                        )
                    }
                }
            }

            record.failureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFC62828).copy(alpha = 0.08f)
                ) {
                    Text(
                        text = "دلیل نتیجه: $reason",
                        fontSize = 11.sp,
                        color = Color(0xFFB71C1C),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetaTag("total=${record.totalDurationMs}ms")
                    MetaTag("local=$localTime")
                    MetaTag("utc=${record.capturedAtUtcIso}")
                    MetaTag("decode=${record.decodeStatus}")
                    record.lastRejectionCode?.let { MetaTag("rejection=$it") }
                    record.exceptionType?.let { MetaTag("ex=${it.substringAfterLast('.')}") }
                    record.mlKitErrorCode?.let { MetaTag("mlKitCode=$it") }
                }
            }
        }
    }
}

@Composable
private fun RawAndGifInspectionCard(
    repository: CaptchaLabRepository,
    record: CaptchaLabRecord,
    onOpenComparison: () -> Unit
) {
    val rawBitmap = rememberDecodedStageBitmap(repository, record.rawRelativePath)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "تصویر خام سرور و بازرسی فرمت",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (record.gifInfo.isGif) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (record.gifInfo.isAnimatedGif) Color(0xFFE65100).copy(alpha = 0.16f) else TealPrimary.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.GifBox, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text(
                                text = if (record.gifInfo.isAnimatedGif) {
                                    "GIF متحرک (${record.gifInfo.frameCount} فریم)"
                                } else {
                                    "GIF ایستا (${record.gifInfo.frameCount} فریم)"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (rawBitmap != null && rawBitmap.width > 0 && rawBitmap.height > 0) {
                val painter = remember(rawBitmap) {
                    BitmapPainter(image = rawBitmap, filterQuality = FilterQuality.None)
                }
                val ratio = (rawBitmap.width.toFloat() / rawBitmap.height.toFloat()).coerceIn(0.4f, 6f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .clickable { onOpenComparison() }
                        .padding(8.dp)
                ) {
                    Image(
                        painter = painter,
                        contentDescription = "تصویر خام کپچا",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                    )
                }
            } else {
                Text(
                    text = "امکان رندر مستقیم تصویر خام وجود ندارد (وضعیت Decode: ${record.decodeStatus}). بایت خام در فایل ${record.rawRelativePath ?: "-"} ذخیره شده است.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = buildString {
                        append("Format: ${record.rawFormat} (${record.mimeType}) | Size: ${record.rawByteSize} bytes")
                        if (record.rawWidth != null && record.rawHeight != null) {
                            append(" | Dim: ${record.rawWidth}×${record.rawHeight}px")
                        }
                        if (record.gifInfo.isGif) {
                            append("\nGIF Version: ${record.gifInfo.gifVersion ?: "-"} | Animated: ${record.gifInfo.isAnimatedGif} | Frames: ${record.gifInfo.frameCount} | DecodedFrame: ${record.gifInfo.decodedFrameIndex ?: "null"}")
                            record.gifInfo.note?.let { append("\nGIF Note: $it") }
                        }
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onOpenComparison,
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("captcha_detail_open_compare_button")
            ) {
                Icon(Icons.Default.Compare, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("مقایسه هم‌عرض با خروجی ML Kit (زوم پیکسلی)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttemptDetailCard(
    repository: CaptchaLabRepository,
    record: CaptchaLabRecord,
    attempt: CaptchaLabAttemptRecord,
    onOpenStageComparison: (CaptchaStageKind) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "تلاش #${attempt.attemptNumber} • مسیر ${attempt.routeType}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (attempt.validationPassed) Color(0xFF2E7D32).copy(alpha = 0.14f) else Color(0xFFC62828).copy(alpha = 0.14f)
                ) {
                    Text(
                        text = if (attempt.validationPassed) "اعتبارسنجی موفق" else (attempt.rejectionCode ?: "ردشده"),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (attempt.validationPassed) Color(0xFF2E7D32) else Color(0xFFC62828),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // PreprocessConfig + Timings
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val cfg = attempt.preprocessConfig
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (cfg.useDecodedColorFrame) {
                        MetaTag("colorDecoded=true")
                    }
                    MetaTag(
                        if (cfg.thresholdMode == com.example.data.captcha.preprocess.ThresholdMode.FIXED) {
                            "threshold=FIXED(${cfg.fixedThreshold})"
                        } else {
                            "threshold=${cfg.thresholdMode.name}"
                        }
                    )
                    MetaTag(
                        if (cfg.noiseRemovalMode == com.example.data.captcha.preprocess.NoiseRemovalMode.NONE) {
                            "noise=NONE"
                        } else {
                            "noise=${cfg.noiseRemovalMode.name}(k=${cfg.filterSize})"
                        }
                    )
                    MetaTag("scale=${cfg.scaleFactor}x")
                    MetaTag("pad=${cfg.borderPaddingPx}px")
                    MetaTag("invert=${cfg.invertOutput}")
                    MetaTag("clip=${cfg.contrastClipPercent}")
                    MetaTag("preprocessMs=${attempt.preprocessDurationMs ?: "-"}")
                    MetaTag("ocrMs=${attempt.ocrDurationMs ?: "-"}")
                    MetaTag("totalMs=${attempt.totalDurationMs}")
                    MetaTag("mlKitIn=${attempt.mlKitInputWidth ?: "-"}x${attempt.mlKitInputHeight ?: "-"}")
                }
            }

            HorizontalDivider()

            // OCR Output & Validation Details
            Text("خروجی OCR و اعتبارسنجی", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "rawOcrFullText: ${attempt.rawOcrFullText?.let { "\"$it\"" } ?: "null"}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "normalizedDigits: ${attempt.normalizedDigits ?: "null"} | elementCount: ${attempt.elementCount ?: "null"}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (attempt.elements.isNotEmpty()) {
                                val elemSummary = attempt.elements.joinToString("\n") { elem ->
                                    val confStr = elem.confidence?.let { String.format(Locale.US, "%.4f", it) } ?: "null (unavailable)"
                                    "  • \"${elem.text}\" -> confidence=$confStr"
                                }
                                Text(
                                    text = "elements:\n$elemSummary",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 14.sp
                                )
                            }
                            attempt.failureReason?.let { reason ->
                                Text(
                                    text = "failureReason: $reason",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFB71C1C)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // All 8 Preprocessing Stages
            Text("مراحل ۸گانه پیش‌پردازش (لمس هر تصویر = مقایسه کنارهم و زوم)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                attempt.stages.forEach { stage ->
                    StageArtifactRow(
                        repository = repository,
                        stage = stage,
                        isMlKitInput = stage.stage == CaptchaStageKind.FINAL_PADDED,
                        onClick = { onOpenStageComparison(stage.stage) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StageArtifactRow(
    repository: CaptchaLabRepository,
    stage: com.example.data.captcha.lab.CaptchaLabStageArtifact,
    isMlKitInput: Boolean,
    onClick: () -> Unit
) {
    val bitmap = rememberDecodedStageBitmap(repository, stage.relativePath)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            width = if (isMlKitInput) 1.5.dp else 1.dp,
            color = if (isMlKitInput) TealPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = bitmap != null) { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stage.stage.titleFa,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                StageStatusChip(status = stage.status, width = stage.width, height = stage.height)
            }

            if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                val painter = remember(bitmap) {
                    BitmapPainter(image = bitmap, filterQuality = FilterQuality.None)
                }
                val ratio = (bitmap.width.toFloat() / bitmap.height.toFloat()).coerceIn(0.4f, 6f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .padding(4.dp)
                ) {
                    Image(
                        painter = painter,
                        contentDescription = stage.stage.titleFa,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                    )
                    Icon(
                        Icons.Default.ZoomIn,
                        contentDescription = "بزرگ‌نمایی و مقایسه",
                        tint = TealPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(18.dp)
                    )
                }
            } else {
                Text(
                    text = stage.note ?: "خروجی تصویری برای این مرحله ثبت نشده است.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StageStatusChip(
    status: CaptchaStageStatus,
    width: Int?,
    height: Int?
) {
    val bg = when (status) {
        CaptchaStageStatus.COMPLETED -> Color(0xFF2E7D32).copy(alpha = 0.14f)
        CaptchaStageStatus.FAILED -> Color(0xFFC62828).copy(alpha = 0.14f)
        CaptchaStageStatus.SKIPPED -> Color(0xFFEF6C00).copy(alpha = 0.14f)
        CaptchaStageStatus.NOT_RUN -> Color(0xFF607D8B).copy(alpha = 0.14f)
    }
    val fg = when (status) {
        CaptchaStageStatus.COMPLETED -> Color(0xFF2E7D32)
        CaptchaStageStatus.FAILED -> Color(0xFFC62828)
        CaptchaStageStatus.SKIPPED -> Color(0xFFE65100)
        CaptchaStageStatus.NOT_RUN -> Color(0xFF546E7A)
    }
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        val dimText = if (width != null && height != null) " (${width}×${height})" else ""
        Text(
            text = "${status.labelFa}$dimText",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun MetaTag(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

internal fun outcomeColorPair(category: CaptchaLabOutcomeCategory): Pair<Color, Color> = when (category) {
    CaptchaLabOutcomeCategory.SUCCESS_PRIMARY -> Color(0xFF2E7D32) to Color.White
    CaptchaLabOutcomeCategory.SUCCESS_FALLBACK -> TealPrimary to Color.White
    CaptchaLabOutcomeCategory.NO_TEXT_DETECTED -> Color(0xFFE65100) to Color.White
    CaptchaLabOutcomeCategory.VALIDATION_REJECTED -> Color(0xFFF57C00) to Color.White
    CaptchaLabOutcomeCategory.DECODE_ERROR,
    CaptchaLabOutcomeCategory.PREPROCESS_ERROR,
    CaptchaLabOutcomeCategory.MLKIT_ERROR,
    CaptchaLabOutcomeCategory.TIMEOUT,
    CaptchaLabOutcomeCategory.EMPTY_INPUT -> Color(0xFFC62828) to Color.White
}

@Composable
internal fun OutcomePill(category: CaptchaLabOutcomeCategory) {
    val (bg, fg) = outcomeColorPair(category)
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(
            text = category.labelFa,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
