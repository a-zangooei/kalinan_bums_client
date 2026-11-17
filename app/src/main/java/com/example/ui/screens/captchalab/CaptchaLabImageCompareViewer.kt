package com.example.ui.screens.captchalab

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.captcha.lab.CaptchaLabAttemptRecord
import com.example.data.captcha.lab.CaptchaLabRecord
import com.example.data.captcha.lab.CaptchaLabRepository
import com.example.data.captcha.lab.CaptchaStageKind
import com.example.data.captcha.lab.CaptchaStageStatus
import com.example.ui.components.PersianRtlLayout
import com.example.ui.theme.TealPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal data class CaptchaComparisonRequest(
    val record: CaptchaLabRecord,
    val initialAttemptNumber: Int,
    val initialStageKind: CaptchaStageKind = CaptchaStageKind.FINAL_PADDED,
    val startSideBySide: Boolean = true
)

@Composable
internal fun rememberDecodedStageBitmap(
    repository: CaptchaLabRepository,
    relativePath: String?
): ImageBitmap? {
    val file: File? = remember(repository, relativePath) {
        repository.resolveFile(relativePath)
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = file?.absolutePath, key2 = file?.lastModified()) {
        value = withContext(Dispatchers.IO) {
            if (file == null || !file.exists()) return@withContext null
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/**
 * نمایشگر تمام‌صفحه با بزرگ‌نمایی هم‌عرض، فیلتر پیکسلی واضح ([FilterQuality.None])
 * و زوم لمسی دو انگشتی / دو بار لمس برای مقایسه تصویر خام با خروجی مراحل پیش‌پردازش و ورودی ML Kit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptchaLabImageCompareViewer(
    repository: CaptchaLabRepository,
    request: CaptchaComparisonRequest,
    onBack: () -> Unit
) {
    var selectedAttemptNumber by remember(request) { mutableStateOf(request.initialAttemptNumber) }
    var selectedStageKind by remember(request) { mutableStateOf(request.initialStageKind) }
    var isSideBySide by remember(request) { mutableStateOf(request.startSideBySide) }

    var scale by remember(request) { mutableFloatStateOf(1f) }
    var panOffset by remember(request) { mutableStateOf(Offset.Zero) }

    val selectedAttempt: CaptchaLabAttemptRecord? = remember(request.record, selectedAttemptNumber) {
        request.record.attempts.firstOrNull { it.attemptNumber == selectedAttemptNumber }
            ?: request.record.attempts.firstOrNull()
    }
    val selectedStageArtifact = remember(selectedAttempt, selectedStageKind) {
        selectedAttempt?.stages?.firstOrNull { it.stage == selectedStageKind }
    }

    val rawBitmap = rememberDecodedStageBitmap(repository, request.record.rawRelativePath)
    val stageBitmap = rememberDecodedStageBitmap(repository, selectedStageArtifact?.relativePath)

    PersianRtlLayout {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = if (isSideBySide) "مقایسه هم‌عرض پیکسلی" else "نمایشگر زوم پیکسلی",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = String.format(Locale.US, "Zoom: %.1fx (FilterQuality.None)", scale),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("captcha_compare_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { isSideBySide = !isSideBySide },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("captcha_compare_toggle_mode_button")
                        ) {
                            Icon(
                                Icons.Default.Compare,
                                contentDescription = if (isSideBySide) "نمایش تک‌تصویر" else "مقایسه کنارهم",
                                tint = if (isSideBySide) TealPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = { scale = (scale - 0.75f).coerceIn(1f, 8f); if (scale <= 1.01f) panOffset = Offset.Zero },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "کوچک‌نمایی")
                        }
                        IconButton(
                            onClick = { scale = (scale + 0.75f).coerceIn(1f, 8f) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "بزرگ‌نمایی")
                        }
                        IconButton(
                            onClick = {
                                scale = 1f
                                panOffset = Offset.Zero
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = "بازنشانی زوم")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Attempt selector if multiple attempts exist
                if (request.record.attempts.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(request.record.attempts, key = { it.attemptNumber }) { attempt ->
                            FilterChip(
                                selected = attempt.attemptNumber == selectedAttempt?.attemptNumber,
                                onClick = { selectedAttemptNumber = attempt.attemptNumber },
                                label = {
                                    Text(
                                        text = "تلاش ${attempt.attemptNumber} (${attempt.routeType})",
                                        fontSize = 11.sp
                                    )
                                },
                                modifier = Modifier.heightIn(min = 40.dp)
                            )
                        }
                    }
                }

                // Stage selector chips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(CaptchaStageKind.entries, key = { it.name }) { stageKind ->
                        val status = selectedAttempt?.stages?.firstOrNull { it.stage == stageKind }?.status
                        val suffix = when (status) {
                            CaptchaStageStatus.COMPLETED, null -> ""
                            CaptchaStageStatus.SKIPPED -> " (رد)"
                            CaptchaStageStatus.FAILED -> " (خطا)"
                            CaptchaStageStatus.NOT_RUN -> " (خالی)"
                        }
                        FilterChip(
                            selected = stageKind == selectedStageKind,
                            onClick = { selectedStageKind = stageKind },
                            label = {
                                Text(
                                    text = "${stageKind.titleFa}$suffix",
                                    fontSize = 11.sp
                                )
                            },
                            modifier = Modifier.heightIn(min = 40.dp)
                        )
                    }
                }

                // Interactive synchronized touch zoom & pan surface
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clipToBounds()
                        .background(Color(0xFF16191D), RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 8f)
                                scale = nextScale
                                if (nextScale <= 1.01f) {
                                    panOffset = Offset.Zero
                                } else {
                                    val maxX = size.width * (nextScale - 1f) / 2f
                                    val maxY = size.height * (nextScale - 1f) / 2f
                                    panOffset = Offset(
                                        x = (panOffset.x + pan.x).coerceIn(-maxX, maxX),
                                        y = (panOffset.y + pan.y).coerceIn(-maxY, maxY)
                                    )
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scale = when {
                                        scale < 2.2f -> 3f
                                        scale < 5f -> 6f
                                        else -> 1f
                                    }
                                    if (scale <= 1.01f) panOffset = Offset.Zero
                                }
                            )
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = panOffset.x
                                translationY = panOffset.y
                            },
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (isSideBySide) {
                            PixelComparisonPane(
                                title = "تصویر خام سرور (Raw)",
                                subtitle = buildString {
                                    append(request.record.rawFormat)
                                    if (request.record.rawWidth != null && request.record.rawHeight != null) {
                                        append(" • ${request.record.rawWidth}×${request.record.rawHeight}px")
                                    }
                                    if (request.record.gifInfo.isGif) {
                                        append(" • ${request.record.gifInfo.frameCount} فریم")
                                    }
                                },
                                bitmap = rawBitmap,
                                emptyMessage = "تصویر خام موجود نیست"
                            )
                        }

                        PixelComparisonPane(
                            title = selectedStageKind.titleFa,
                            subtitle = buildString {
                                append("وضعیت: ${selectedStageArtifact?.status?.labelFa ?: "نامشخص"}")
                                if (selectedStageArtifact?.width != null && selectedStageArtifact.height != null) {
                                    append(" • ${selectedStageArtifact.width}×${selectedStageArtifact.height}px")
                                }
                                selectedStageArtifact?.note?.takeIf { it.isNotBlank() }?.let { note ->
                                    append(" • $note")
                                }
                            },
                            bitmap = stageBitmap,
                            emptyMessage = selectedStageArtifact?.note ?: "تصویر این مرحله موجود نیست"
                        )
                    }
                }

                // Helpful hint footer
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "نکته: هر دو تصویر به‌صورت هم‌عرض و با فیلتر پیکسلی بدون محوشدگی رندر می‌شوند. برای زوم دقیق از دو انگشت یا دو بار لمس استفاده کنید.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PixelComparisonPane(
    title: String,
    subtitle: String,
    bitmap: ImageBitmap?,
    emptyMessage: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF23272E), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = subtitle,
                color = Color(0xFFB0BEC5),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
            val painter = remember(bitmap) {
                BitmapPainter(image = bitmap, filterQuality = FilterQuality.None)
            }
            val ratio = (bitmap.width.toFloat() / bitmap.height.toFloat()).coerceIn(0.25f, 8f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .border(1.dp, TealPrimary.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(4.dp)
            ) {
                Image(
                    painter = painter,
                    contentDescription = title,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(Color(0xFF2D323B), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = emptyMessage,
                    color = Color(0xFFFFAB91),
                    fontSize = 11.sp
                )
            }
        }
    }
}
