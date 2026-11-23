package com.example.data.repository

import com.example.BuildConfig
import com.example.data.logger.AppLogger
import com.example.data.model.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class UpdateRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val githubOwner: String = GITHUB_OWNER,
    private val githubRepo: String = GITHUB_REPO,
    private val releasesApiUrl: String? = null,
    private val fallbackReleaseWebUrl: String? = null
) {

    companion object {
        val GITHUB_OWNER: String = BuildConfig.UPDATE_GITHUB_OWNER.ifBlank { "a-zangooei" }
        val GITHUB_REPO: String = BuildConfig.UPDATE_GITHUB_REPO.ifBlank { "kalinan_bums_client" }
        val RELEASES_API_URL: String = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        val FALLBACK_RELEASE_WEB_URL: String = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    }

    suspend fun checkForUpdate(
        currentVersionName: String = BuildConfig.VERSION_NAME
    ): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        val flowId = AppLogger.generateFlowId("upd")
        val effectiveOwner = githubOwner.trim()
        val effectiveRepo = githubRepo.trim()
        if (effectiveOwner.isBlank() || effectiveRepo.isBlank()) {
            AppLogger.logDebug("UpdateRepository", "CheckForUpdateSkipped", "GitHub repository owner/repo is not configured", flowId)
            return@withContext Result.failure(Exception("نشانی مخزن به‌روزرسانی تنظیم نشده است"))
        }

        val effectiveApiUrl = releasesApiUrl?.trim().takeUnless { it.isNullOrBlank() }
            ?: "https://api.github.com/repos/$effectiveOwner/$effectiveRepo/releases/latest"
        val effectiveFallbackWebUrl = fallbackReleaseWebUrl?.trim().takeUnless { it.isNullOrBlank() }
            ?: "https://github.com/$effectiveOwner/$effectiveRepo/releases/latest"
        val cleanCurrentVersion = currentVersionName.trim().removePrefix("v").removePrefix("V")

        AppLogger.logInfo(
            "UpdateRepository",
            "CheckForUpdate",
            "Checking latest GitHub release for $effectiveOwner/$effectiveRepo (current=$currentVersionName)",
            flowId
        )
        try {
            val request = Request.Builder()
                .url(effectiveApiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Kalinan-Android-Client")
                .get()
                .build()

            var isSuccessful = false
            var responseCode = 0
            var bodyString = ""
            client.newCall(request).execute().use { response ->
                isSuccessful = response.isSuccessful
                responseCode = response.code
                if (isSuccessful) {
                    bodyString = response.body?.string() ?: ""
                }
            }

            if (responseCode == 404) {
                AppLogger.logInfo(
                    "UpdateRepository",
                    "NoPublishedRelease",
                    "No formal GitHub release published yet for $effectiveOwner/$effectiveRepo (HTTP 404)",
                    flowId
                )
                return@withContext Result.success(
                    AppUpdateInfo(
                        isUpdateAvailable = false,
                        latestVersionName = "",
                        currentVersionName = cleanCurrentVersion,
                        downloadUrl = effectiveFallbackWebUrl,
                        changelogSnippet = "",
                        publishedAt = "",
                        hasPublishedRelease = false
                    )
                )
            }

            if (responseCode == 403 || responseCode == 429) {
                AppLogger.logWarn("UpdateRepository", "CheckForUpdateRateLimited", "GitHub releases API returned HTTP $responseCode", flowId = flowId)
                return@withContext Result.failure(
                    Exception("محدودیت موقت درخواست به گیت‌هاب رخ داده است؛ لطفاً کمی بعد دوباره تلاش کنید (کد خطا: $responseCode)")
                )
            }

            if (!isSuccessful) {
                AppLogger.logWarn("UpdateRepository", "CheckForUpdateHttpError", "GitHub releases API returned HTTP $responseCode", flowId = flowId)
                return@withContext Result.failure(
                    Exception("عدم دریافت اطلاعات نسخه از گیت‌هاب (کد خطا: $responseCode)")
                )
            }

            if (bodyString.isBlank()) {
                AppLogger.logWarn("UpdateRepository", "CheckForUpdateEmpty", "GitHub releases API returned empty body", flowId = flowId)
                return@withContext Result.failure(Exception("پاسخ سرور گیت‌هاب خالی بود"))
            }

            val json = JSONObject(bodyString)
            val tagName = json.optString("tag_name", "").trim()
            val rawBody = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")

            var releaseApkUrl: String? = null
            var firstApkUrl: String? = null
            val assetsArray = json.optJSONArray("assets")
            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    val assetUrl = asset.optString("browser_download_url", "").trim()
                    if (assetName.endsWith(".apk", ignoreCase = true) && assetUrl.isNotEmpty()) {
                        if (firstApkUrl == null) {
                            firstApkUrl = assetUrl
                        }
                        if (assetName.contains("release", ignoreCase = true)) {
                            releaseApkUrl = assetUrl
                            break
                        }
                    }
                }
            }
            val downloadUrl = releaseApkUrl ?: firstApkUrl ?: effectiveFallbackWebUrl

            val changelogSnippet = extractChangelogSnippet(rawBody)
            val isNewer = isNewerVersion(latest = tagName, current = currentVersionName)

            AppLogger.logInfo(
                "UpdateRepository",
                "CheckForUpdateSuccess",
                "Latest tag='$tagName', current='$currentVersionName', updateAvailable=$isNewer",
                flowId
            )

            Result.success(
                AppUpdateInfo(
                    isUpdateAvailable = isNewer,
                    latestVersionName = tagName.removePrefix("v").removePrefix("V"),
                    currentVersionName = cleanCurrentVersion,
                    downloadUrl = downloadUrl,
                    changelogSnippet = changelogSnippet,
                    publishedAt = publishedAt,
                    hasPublishedRelease = true
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.logError("UpdateRepository", "Update check failed: ${e.message}", e, flowId = flowId, operation = "CheckForUpdate")
            Result.failure(e)
        }
    }

    fun extractChangelogSnippet(rawBody: String): String {
        if (rawBody.isBlank()) return "بهبودهای کلی در عملکرد و امنیت برنامه"

        // Search for known Persian or English changelog headers
        val headers = listOf(
            "### 📝 تغییرات این نسخه",
            "### تغییرات این نسخه",
            "### 📢 پیام این نسخه",
            "## 📝 تغییرات این نسخه",
            "## تغییرات این نسخه",
            "### Changelog",
            "## Changelog"
        )

        for (header in headers) {
            val startIndex = rawBody.indexOf(header)
            if (startIndex != -1) {
                val afterHeader = rawBody.substring(startIndex + header.length).trim()
                // Take content up to the next horizontal rule '---' or next header '###'
                val endMarkerIndex = listOf(
                    afterHeader.indexOf("\n---"),
                    afterHeader.indexOf("\n###"),
                    afterHeader.indexOf("\n##")
                ).filter { it != -1 }.minOrNull()

                val extracted = if (endMarkerIndex != null) {
                    afterHeader.substring(0, endMarkerIndex).trim()
                } else {
                    afterHeader.trim()
                }

                if (extracted.isNotBlank()) {
                    return extracted
                }
            }
        }

        // Fallback: extract bullet points if any exist
        val bulletLines = rawBody.lines()
            .map { it.trim() }
            .filter { it.startsWith("•") || it.startsWith("-") || it.startsWith("*") }
            .take(5)

        return if (bulletLines.isNotEmpty()) {
            bulletLines.joinToString("\n")
        } else {
            "بهبود ارتباط با سرور و ارتقای امنیت برنامه"
        }
    }

    fun isNewerVersion(latest: String, current: String): Boolean {
        val cleanLatest = latest.trim().removePrefix("v").removePrefix("V").split("-")[0]
        val cleanCurrent = current.trim().removePrefix("v").removePrefix("V").split("-")[0]

        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

        if (latestParts.isEmpty() || currentParts.isEmpty()) return false

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }

        return false
    }
}
