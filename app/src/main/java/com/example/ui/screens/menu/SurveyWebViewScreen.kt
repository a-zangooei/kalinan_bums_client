package com.example.ui.screens.menu

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.logger.AppLogger
import com.example.data.network.CullinanHttpClient
import com.example.ui.components.PersianRtlLayout
import com.example.ui.theme.TealPrimary
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyWebViewScreen(
    surveyUrl: String,
    onCloseAndRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val effectiveUrl = remember(surveyUrl) {
        surveyUrl.ifBlank { CullinanHttpClient.SURVEY_URL }
    }
    val portalHost = remember(effectiveUrl) {
        effectiveUrl.toHttpUrlOrNull()?.host ?: "jeton.bums.ac.ir"
    }

    var pageProgress by remember { mutableIntStateOf(0) }
    var isPageLoading by remember { mutableStateOf(true) }
    var isSurveyCompletedDetected by remember { mutableStateOf(false) }
    var sslBlockedError by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onCloseAndRefresh()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    PersianRtlLayout {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "تکمیل نظرسنجی سامانه",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = portalHost,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onCloseAndRefresh,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("survey_webview_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "بازگشت و بررسی منو"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                sslBlockedError = false
                                webViewRef?.reload()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("survey_webview_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "تازه‌سازی صفحه نظرسنجی"
                            )
                        }
                        IconButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(effectiveUrl)))
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("survey_webview_external_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "باز کردن در مرورگر گوشی"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onCloseAndRefresh,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("survey_webview_done_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TealPrimary,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isSurveyCompletedDetected) {
                                    "بازگشت و نمایش منوی رزرو"
                                } else {
                                    "تکمیل کردم؛ بررسی منوی رزرو"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (isPageLoading && pageProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { pageProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = TealPrimary
                    )
                }

                AnimatedVisibility(visible = isSurveyCompletedDetected) {
                    Surface(
                        color = TealPrimary.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = TealPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "دسترسی به منوی رزرو فعال شده است. دکمه پایین را برای بازگشت بزنید.",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = TealPrimary
                            )
                        }
                    }
                }

                if (sslBlockedError) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "خطای گواهی امنیتی در بارگذاری صفحه",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "مرورگر داخلی برای حفظ امنیت، بارگذاری با گواهی نامعتبر را متوقف کرد. می‌توانید صفحه نظرسنجی را در مرورگر گوشی باز کنید.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(effectiveUrl)))
                                        }
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Text("باز کردن در مرورگر گوشی")
                                }
                            }
                        }
                    }
                } else {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("survey_webview_container"),
                        factory = { ctx ->
                            syncSessionCookiesToWebView(effectiveUrl)
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.userAgentString = CullinanHttpClient.DEFAULT_USER_AGENT

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        pageProgress = newProgress
                                        isPageLoading = newProgress < 100
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val dest = request?.url ?: return false
                                        val destHost = dest.host
                                        if (destHost != null && !destHost.equals(portalHost, ignoreCase = true)) {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, dest))
                                            }
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isPageLoading = false
                                        if (url != null && (
                                                url.contains("Reservation/Reservation.aspx", ignoreCase = true) ||
                                                    url.contains("/Master/Default.aspx", ignoreCase = true)
                                                )
                                        ) {
                                            isSurveyCompletedDetected = true
                                            AppLogger.logInfo(
                                                "SurveyWebView",
                                                "SurveyCompletionDetected",
                                                "WebView navigated to $url indicating survey completion"
                                            )
                                        }
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView?,
                                        handler: SslErrorHandler?,
                                        error: SslError?
                                    ) {
                                        AppLogger.logWarn(
                                            component = "SurveyWebView",
                                            operation = "WebViewSslBlocked",
                                            message = "SSL error in SurveyWebView: ${error?.primaryError}",
                                            errorCategory = "WEBVIEW_SSL_ERROR"
                                        )
                                        handler?.cancel()
                                        sslBlockedError = true
                                    }
                                }

                                webViewRef = this
                                AppLogger.logInfo("SurveyWebView", "LoadSurveyUrl", "Loading $effectiveUrl inside WebView")
                                loadUrl(effectiveUrl)
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun syncSessionCookiesToWebView(targetUrl: String) {
    try {
        val httpUrl = targetUrl.toHttpUrlOrNull() ?: return
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        val cookies = CullinanHttpClient.cookieJar.getCookiesForUrl(httpUrl)
        val portSuffix = if (httpUrl.port != 80 && httpUrl.port != 443) ":${httpUrl.port}" else ""
        val origin = "${httpUrl.scheme}://${httpUrl.host}$portSuffix"
        for (cookie in cookies) {
            cookieManager.setCookie(origin, "${cookie.name}=${cookie.value}; path=${cookie.path}")
        }
        cookieManager.flush()
    } catch (e: Exception) {
        AppLogger.logWarn(
            component = "SurveyWebView",
            operation = "CookieSyncFailed",
            message = "Failed to sync session cookies to WebView: ${e.message}",
            throwable = e,
            errorCategory = "COOKIE_SYNC_WARN"
        )
    }
}
