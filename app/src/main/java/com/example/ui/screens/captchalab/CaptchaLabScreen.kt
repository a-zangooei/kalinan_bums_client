package com.example.ui.screens.captchalab

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.captcha.lab.CaptchaLabConstants
import com.example.data.captcha.lab.CaptchaLabController
import com.example.data.captcha.lab.CaptchaLabFilter
import com.example.data.captcha.lab.CaptchaLabRecord
import com.example.data.captcha.lab.CaptchaLabRepository
import com.example.data.captcha.lab.CaptchaLabSort
import com.example.data.captcha.lab.CaptchaLabSummaryStats
import com.example.data.captcha.lab.CaptchaLabZipExporter
import com.example.data.captcha.lab.CaptchaStageBenchmarkEngine
import com.example.data.captcha.solver.OcrModuleController
import com.example.data.captcha.solver.OcrModuleState
import com.example.ui.components.PersianRtlLayout
import com.example.ui.screens.settings.OcrModuleStatusPanel
import com.example.ui.theme.TealPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * صفحه اختصاصی تمام‌صفحه آزمایشگاه کپچا (در دسترس از بخش توسعه‌دهنده در تنظیمات، در هر دو بیلد Debug و Release).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptchaLabScreen(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { CaptchaLabController.getRepository(context) }
    val records by repository.records.collectAsStateWithLifecycle()
    val isArchivingEnabled by CaptchaLabController.isArchivingEnabled.collectAsStateWithLifecycle()
    val batchProgress by CaptchaLabController.batchProgress.collectAsStateWithLifecycle()
    val activeSingleIds by CaptchaLabController.activeSingleBenchmarkIds.collectAsStateWithLifecycle()
    val simulationProgress by CaptchaLabController.simulationProgress.collectAsStateWithLifecycle()
    val simulationReport by CaptchaLabController.simulationReport.collectAsStateWithLifecycle()
    val ocrModuleState by OcrModuleController.state.collectAsStateWithLifecycle()

    LaunchedEffect(repository, context) {
        repository.ensureLoaded()
        OcrModuleController.refreshStatus(context)
    }

    var activeFilter by remember { mutableStateOf(CaptchaLabFilter.ALL) }
    var activeSort by remember { mutableStateOf(CaptchaLabSort.NEWEST_FIRST) }
    var selectedRecordId by remember { mutableStateOf<String?>(null) }
    var comparisonRequest by remember { mutableStateOf<CaptchaComparisonRequest?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var batchScopePurpose by remember { mutableStateOf<String?>(null) }

    val selectedRecord = remember(records, selectedRecordId) {
        selectedRecordId?.let { id -> records.firstOrNull { it.captchaId == id } }
    }

    val scope = rememberCoroutineScope()
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "در حال ساخت و ذخیره آرشیو ZIP...", Toast.LENGTH_SHORT).show()
                }
                CaptchaLabZipExporter.saveZipToUri(context, uri, repository, records)
            }
        }
    }

    Dialog(
        onDismissRequest = {
            when {
                comparisonRequest != null -> comparisonRequest = null
                selectedRecord != null -> selectedRecordId = null
                else -> onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when {
                comparisonRequest != null -> {
                    CaptchaLabImageCompareViewer(
                        repository = repository,
                        request = comparisonRequest!!,
                        onBack = { comparisonRequest = null }
                    )
                }
                selectedRecord != null -> {
                    val isSingleRunning = selectedRecord.captchaId in activeSingleIds
                    CaptchaLabDetailView(
                        repository = repository,
                        record = selectedRecord,
                        isBenchmarkRunning = isSingleRunning || batchProgress.isRunning || simulationProgress.isRunning,
                        isOcrModuleReady = ocrModuleState.isReady,
                        onRunStageBenchmark = { attemptNum ->
                            CaptchaLabController.runSingleBenchmarkAsync(
                                context = context,
                                captchaId = selectedRecord.captchaId,
                                attemptNumber = attemptNum
                            ) { result ->
                                if (result != null) {
                                    Toast.makeText(context, "OCR تصویر خام و ۸ مرحله تکمیل شد.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onSaveExpectedDigits = { digits ->
                            CaptchaLabController.updateExpectedDigitsAsync(
                                context = context,
                                captchaId = selectedRecord.captchaId,
                                expectedDigits = digits
                            ) {
                                Toast.makeText(
                                    context,
                                    if (digits != null) "برچسب صحیح $digits ثبت شد." else "برچسب دستی پاک شد.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onBack = { selectedRecordId = null },
                        onDeleteRecord = {
                            repository.deleteRecordAsync(selectedRecord.captchaId)
                            selectedRecordId = null
                            Toast.makeText(context, "رکورد کپچا حذف شد.", Toast.LENGTH_SHORT).show()
                        },
                        onOpenComparison = { req -> comparisonRequest = req }
                    )
                }
                else -> {
                    val stats = remember(records) { CaptchaLabRepository.computeSummaryStats(records) }
                    val displayedRecords = remember(records, activeFilter, activeSort) {
                        CaptchaLabRepository.filterAndSort(records, activeFilter, activeSort)
                    }
                    val compatibilitySummary = remember(records) {
                        CaptchaStageBenchmarkEngine.analyzeCompatibility(records)
                    }
                    val labeledCount = remember(records) {
                        records.count { !it.expectedDigits.isNullOrBlank() }
                    }
                    val startBatchOn: (List<CaptchaLabRecord>, String) -> Unit = { list, label ->
                        CaptchaLabController.startBatchBenchmark(context, list, label) { count, cancelled ->
                            val msg = if (cancelled) {
                                "اجرای انبوه متوقف شد ($count کپچا ثبت شد)."
                            } else {
                                "اجرای انبوه OCR روی $count کپچا تکمیل شد."
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    val startSimulationOn: (List<CaptchaLabRecord>, String) -> Unit = { list, label ->
                        CaptchaLabController.startSolverSimulation(context, list, label) { report ->
                            if (report != null) {
                                val msg = if (report.cancelled) {
                                    "شبیه‌سازی متوقف شد (${report.evaluatedCount} کپچا ارزیابی شد)."
                                } else {
                                    "شبیه‌سازی تکمیل شد: نرخ موفقیت ${report.simulated.successRatePercent}%"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    val triggerBatchClick: () -> Unit = {
                        if (activeFilter == CaptchaLabFilter.ALL) {
                            startBatchOn(records, "همه کپچاها")
                        } else {
                            batchScopePurpose = "BENCHMARK"
                        }
                    }
                    val triggerSimulateClick: () -> Unit = {
                        if (activeFilter == CaptchaLabFilter.ALL) {
                            startSimulationOn(records, "همه کپچاها")
                        } else {
                            batchScopePurpose = "SIMULATION"
                        }
                    }

                    PersianRtlLayout {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Science,
                                                contentDescription = null,
                                                tint = TealPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = "آزمایشگاه کپچا",
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    },
                                    navigationIcon = {
                                        IconButton(
                                            onClick = onDismiss,
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("captcha_lab_back_button")
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "بازگشت"
                                            )
                                        }
                                    },
                                    actions = {
                                        IconButton(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    withContext(Dispatchers.Main) {
                                                        CaptchaLabZipExporter.shareZip(context, repository, records)
                                                    }
                                                }
                                            },
                                            enabled = records.isNotEmpty(),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("captcha_lab_share_zip_button")
                                        ) {
                                            Icon(Icons.Default.FolderZip, contentDescription = "ارسال خروجی ZIP")
                                        }
                                        IconButton(
                                            onClick = {
                                                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                                createDocumentLauncher.launch("kalinan_captcha_lab_$stamp.zip")
                                            },
                                            enabled = records.isNotEmpty(),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("captcha_lab_save_zip_button")
                                        ) {
                                            Icon(Icons.Default.SaveAlt, contentDescription = "ذخیره فایل ZIP")
                                        }
                                        IconButton(
                                            onClick = { showClearConfirm = true },
                                            enabled = records.isNotEmpty(),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("captcha_lab_clear_all_button")
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteSweep,
                                                contentDescription = "پاک‌سازی کامل",
                                                tint = if (records.isNotEmpty()) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
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
                                    .testTag("captcha_lab_main_list"),
                                contentPadding = PaddingValues(vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item {
                                    ArchiveToggleBanner(
                                        enabled = isArchivingEnabled,
                                        onToggle = { enable ->
                                            CaptchaLabController.setArchivingEnabled(enable)
                                        }
                                    )
                                }

                                item {
                                    OcrModuleLabStatusCard(
                                        ocrModuleState = ocrModuleState,
                                        onDownloadOcrModule = {
                                            OcrModuleController.startManualDownload(context)
                                        }
                                    )
                                }

                                item {
                                    KpiSummaryRow(stats = stats)
                                }

                                if (batchProgress.isRunning) {
                                    item {
                                        BatchBenchmarkProgressCard(
                                            progress = batchProgress,
                                            onCancel = { CaptchaLabController.cancelBatchBenchmark() }
                                        )
                                    }
                                }

                                if (simulationProgress.isRunning) {
                                    item {
                                        SolverSimulationProgressCard(
                                            progress = simulationProgress,
                                            onCancel = { CaptchaLabController.cancelSolverSimulation() }
                                        )
                                    }
                                }

                                simulationReport?.let { simReport ->
                                    item {
                                        SolverSimulationComparisonCard(
                                            report = simReport,
                                            onDismiss = { CaptchaLabController.clearSolverSimulationReport() }
                                        )
                                    }
                                }

                                if (records.isNotEmpty()) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = triggerSimulateClick,
                                                enabled = ocrModuleState.isReady && !batchProgress.isRunning && !simulationProgress.isRunning,
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (ocrModuleState.isReady) TealPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                                    .testTag("captcha_lab_simulate_solver_button")
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.CompareArrows,
                                                    contentDescription = null,
                                                    tint = if (ocrModuleState.isReady) TealPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (activeFilter == CaptchaLabFilter.ALL) {
                                                        "شبیه‌سازی حل‌گر جدید روی آرشیو (${records.size} کپچا)"
                                                    } else {
                                                        "شبیه‌سازی حل‌گر جدید (انتخاب محدوده)"
                                                    },
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (ocrModuleState.isReady) TealPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            OutlinedButton(
                                                onClick = triggerBatchClick,
                                                enabled = ocrModuleState.isReady && !batchProgress.isRunning && !simulationProgress.isRunning,
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                                    .testTag("captcha_lab_batch_ocr_button")
                                            ) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (activeFilter == CaptchaLabFilter.ALL) {
                                                        "اجرای انبوه OCR خام + ۸ مرحله (${records.size} کپچا)"
                                                    } else {
                                                        "اجرای انبوه OCR (انتخاب محدوده)"
                                                    },
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }

                                    item {
                                        StageCompatibilityLeaderboardCard(
                                            summary = compatibilitySummary,
                                            isBatchRunning = batchProgress.isRunning || simulationProgress.isRunning,
                                            onRunBatchClick = triggerBatchClick
                                        )
                                    }
                                }

                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (records.isNotEmpty()) {
                                            Text(
                                                text = "برچسب صحیح ثبت‌شده: $labeledCount از ${records.size} • بدون برچسب: ${records.size - labeledCount}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.testTag("captcha_lab_labeled_counter")
                                            )
                                        }
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            items(CaptchaLabFilter.entries, key = { it.name }) { filter ->
                                                FilterChip(
                                                    selected = activeFilter == filter,
                                                    onClick = { activeFilter = filter },
                                                    label = { Text(filter.labelFa, fontSize = 11.sp) },
                                                    modifier = Modifier
                                                        .heightIn(min = 42.dp)
                                                        .testTag("captcha_lab_filter_${filter.name}")
                                                )
                                            }
                                        }
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            items(CaptchaLabSort.entries, key = { it.name }) { sort ->
                                                FilterChip(
                                                    selected = activeSort == sort,
                                                    onClick = { activeSort = sort },
                                                    label = { Text("مرتب‌سازی: ${sort.labelFa}", fontSize = 10.sp) },
                                                    modifier = Modifier.heightIn(min = 38.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                if (displayedRecords.isEmpty()) {
                                    item {
                                        EmptyArchiveCard(
                                            totalRecords = records.size,
                                            isArchivingEnabled = isArchivingEnabled
                                        )
                                    }
                                } else {
                                    items(displayedRecords, key = { it.captchaId }) { record ->
                                        val isSingleRunning = record.captchaId in activeSingleIds
                                        CaptchaRecordListCard(
                                            repository = repository,
                                            record = record,
                                            isBenchmarkRunning = isSingleRunning,
                                            batchRunning = batchProgress.isRunning || simulationProgress.isRunning,
                                            isOcrModuleReady = ocrModuleState.isReady,
                                            onRunSingleOcr = {
                                                CaptchaLabController.runSingleBenchmarkAsync(context, record.captchaId) { result ->
                                                    if (result != null) {
                                                        Toast.makeText(context, "OCR مراحل ${record.captchaId.take(8)} ثبت شد.", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            onClick = { selectedRecordId = record.captchaId },
                                            onDelete = {
                                                repository.deleteRecordAsync(record.captchaId)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    batchScopePurpose?.let { purpose ->
                        val isSim = purpose == "SIMULATION"
                        AlertDialog(
                            onDismissRequest = { batchScopePurpose = null },
                            title = {
                                Text(
                                    text = if (isSim) "انتخاب محدوده شبیه‌سازی حل‌گر" else "انتخاب محدوده اجرای انبوه OCR",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            },
                            text = {
                                Text(
                                    text = if (isSim) {
                                        "شبیه‌سازی حل‌گر جدید روی بایت‌های خام فهرست فیلترشده (${displayedRecords.size} کپچا) اجرا شود یا برای کل آرشیو (${records.size} کپچا)؟"
                                    } else {
                                        "آیا OCR روی تصویر خام + ۸ مرحله پیش‌پردازش برای فهرست فیلترشده (${displayedRecords.size} کپچا) اجرا شود یا برای کل آرشیو (${records.size} کپچا)؟"
                                    },
                                    fontSize = 12.sp
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        batchScopePurpose = null
                                        if (isSim) {
                                            startSimulationOn(displayedRecords, activeFilter.labelFa)
                                        } else {
                                            startBatchOn(displayedRecords, activeFilter.labelFa)
                                        }
                                    },
                                    enabled = displayedRecords.isNotEmpty(),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("captcha_lab_batch_filtered_button")
                                ) {
                                    Text("لیست فیلترشده (${displayedRecords.size})", fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = { batchScopePurpose = null },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("انصراف")
                                    }
                                    TextButton(
                                        onClick = {
                                            batchScopePurpose = null
                                            if (isSim) {
                                                startSimulationOn(records, "همه کپچاها")
                                            } else {
                                                startBatchOn(records, "همه کپچاها")
                                            }
                                        },
                                        enabled = records.isNotEmpty(),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("captcha_lab_batch_all_button")
                                    ) {
                                        Text("کل آرشیو (${records.size})", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        )
                    }

                    if (showClearConfirm) {
                        AlertDialog(
                            onDismissRequest = { showClearConfirm = false },
                            title = { Text("پاک‌سازی کامل آرشیو کپچا", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                            text = {
                                Text(
                                    text = "تمامی ${records.size} رکورد و تصاویر ذخیره‌شده آزمایشگاه کپچا از حافظه داخلی حذف خواهند شد. این عملیات قابل بازگشت نیست.",
                                    fontSize = 12.sp
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showClearConfirm = false
                                        CaptchaLabController.cancelBatchBenchmark()
                                        CaptchaLabController.cancelSolverSimulation()
                                        CaptchaLabController.clearSolverSimulationReport()
                                        repository.clearAllAsync()
                                        Toast.makeText(context, "بایگانی کپچا به‌طور کامل پاک‌سازی شد.", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("captcha_lab_confirm_clear_button")
                                ) {
                                    Text("حذف کامل", color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showClearConfirm = false },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Text("انصراف")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveToggleBanner(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                TealPrimary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) TealPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (enabled) "بایگانی آزمایشگاه کپچا فعال است" else "بایگانی آزمایشگاه کپچا خاموش است",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) TealPrimary else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (enabled) {
                        "تمام تلاش‌های جدید (اصلی، Fallback، پیش‌پردازش، خطا و Timeout) تا سقف ${CaptchaLabConstants.MAX_RETAINED_CAPTCHAS} کپچای اخیر با حذف خودکار قدیمی‌ترین‌ها ثبت می‌شوند. با بسته شدن برنامه یا خروج از بخش توسعه‌دهنده خاموش می‌شود."
                    } else {
                        "بایگانی در هر اجرای برنامه به‌صورت پیش‌فرض خاموش است. هیچ تصویر یا خروجی میانی تا زمان فعال‌سازی ثبت نمی‌شود و با خاموش کردن، ثبت فوراً متوقف می‌گردد."
                    },
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = TealPrimary),
                modifier = Modifier.testTag("captcha_lab_archive_switch")
            )
        }
    }
}

@Composable
private fun OcrModuleLabStatusCard(
    ocrModuleState: OcrModuleState,
    onDownloadOcrModule: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (ocrModuleState.isReady) {
                Color(0xFF2E7D32).copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("captcha_lab_ocr_module_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "موتور OCR گوگل‌پلی (نسخه Unbundled)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            OcrModuleStatusPanel(
                ocrModuleState = ocrModuleState,
                onDownloadOcrModule = onDownloadOcrModule,
                statusBadgeTag = "captcha_lab_ocr_module_status",
                downloadButtonTag = "captcha_lab_download_ocr_button",
                progressTag = "captcha_lab_ocr_download_progress"
            )
        }
    }
}

@Composable
private fun KpiSummaryRow(stats: CaptchaLabSummaryStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        KpiCard(
            title = "کل (سقف ${CaptchaLabConstants.MAX_RETAINED_CAPTCHAS})",
            value = stats.totalCount.toString(),
            accent = TealPrimary,
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            title = "موفق (${stats.successRatePercent}%)",
            value = stats.successCount.toString(),
            accent = Color(0xFF2E7D32),
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            title = "ناموفق",
            value = stats.failedCount.toString(),
            accent = Color(0xFFC62828),
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            title = "بدون متن",
            value = stats.noTextDetectedCount.toString(),
            accent = Color(0xFFE65100),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun KpiCard(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = title,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CaptchaRecordListCard(
    repository: CaptchaLabRepository,
    record: CaptchaLabRecord,
    isBenchmarkRunning: Boolean,
    batchRunning: Boolean,
    isOcrModuleReady: Boolean,
    onRunSingleOcr: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val rawBitmap = rememberDecodedStageBitmap(repository, record.rawRelativePath)
    val timeText = remember(record.capturedAtEpochMs) {
        SimpleDateFormat("HH:mm:ss • MM/dd", Locale.US).format(Date(record.capturedAtEpochMs))
    }
    val rawOcrPreview = remember(record) {
        record.attempts.lastOrNull { !it.rawOcrFullText.isNullOrBlank() }?.rawOcrFullText
    }
    val bestBenchmarkTarget = remember(record.stageBenchmark) {
        val bench = record.stageBenchmark ?: return@remember null
        bench.bestTargetKey?.let { bestKey ->
            bench.targets.firstOrNull { it.targetKey == bestKey }
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("captcha_lab_record_${record.captchaId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Raw thumbnail preview
            Box(
                modifier = Modifier
                    .size(width = 76.dp, height = 48.dp)
                    .background(Color.White, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (rawBitmap != null) {
                    val painter = remember(rawBitmap) {
                        BitmapPainter(image = rawBitmap, filterQuality = FilterQuality.None)
                    }
                    Image(
                        painter = painter,
                        contentDescription = "پیش‌نمایش کپچا",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = "بدون تصویر",
                        tint = Color.Gray,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutcomePill(category = record.outcomeCategory)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${record.attempts.size} تلاش",
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (record.gifInfo.isGif) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (record.gifInfo.isAnimatedGif) Color(0xFFE65100).copy(alpha = 0.14f) else TealPrimary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "GIF·${record.gifInfo.frameCount}f",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!record.finalDigits.isNullOrBlank()) {
                        Text(
                            text = "کد: ${record.finalDigits}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TealPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    } else if (!rawOcrPreview.isNullOrBlank()) {
                        Text(
                            text = "متن خام: \"$rawOcrPreview\"",
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = record.failureReason ?: record.lastRejectionCode ?: record.finalStatus,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (bestBenchmarkTarget != null || !record.expectedDigits.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        bestBenchmarkTarget?.let { best ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF2E7D32).copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "بهترین: ${best.targetTitleFa}",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF1B5E20),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        record.expectedDigits?.let { gt ->
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = TealPrimary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = "GT:$gt",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TealPrimary,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = "$timeText • ${record.buildType} • ${record.totalDurationMs}ms • ${record.rawFormat}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onRunSingleOcr,
                enabled = isOcrModuleReady && !isBenchmarkRunning && !batchRunning,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("captcha_lab_run_ocr_${record.captchaId}")
            ) {
                if (isBenchmarkRunning) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = TealPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "اجرای OCR تمام مراحل",
                        tint = if (isOcrModuleReady) TealPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("captcha_lab_delete_${record.captchaId}")
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "حذف رکورد",
                    tint = Color(0xFFD32F2F)
                )
            }
        }
    }
}

@Composable
private fun EmptyArchiveCard(
    totalRecords: Int,
    isArchivingEnabled: Boolean
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Science,
                contentDescription = null,
                tint = TealPrimary,
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = if (totalRecords == 0) {
                    "هنوز هیچ کپچایی بایگانی نشده است"
                } else {
                    "موردی با فیلتر انتخاب‌شده یافت نشد"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (totalRecords == 0) {
                    if (isArchivingEnabled) {
                        "بایگانی روشن است؛ به صفحه ورود برگردید و کپچا را بارگذاری یا رفرش کنید تا مراحل پیش‌پردازش و OCR ثبت شوند."
                    } else {
                        "برای شروع ثبت، ابتدا کلید بایگانی در بالای صفحه را روشن کرده و سپس کپچا را در صفحه ورود بررسی کنید."
                    }
                } else {
                    "فیلتر دیگری را انتخاب کنید تا سایر رکوردها نمایش داده شوند."
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
