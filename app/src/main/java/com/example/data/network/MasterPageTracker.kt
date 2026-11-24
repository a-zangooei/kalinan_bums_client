package com.example.data.network

import com.example.data.logger.AppLogger
import com.example.data.parser.DeltaParser
import com.example.data.repository.LogoutFormParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class MasterPageSource {
    FULL_HTML,
    DELTA_PATCHED
}

/**
 * In-memory-only representation of the latest authenticated ASP.NET WebForms Master page.
 * Never written to disk or exported in logs; wiped unconditionally on logout.
 */
data class MasterPageState(
    val pageUrl: String,
    val html: String,
    val source: MasterPageSource = MasterPageSource.FULL_HTML,
    val deltaHiddenFields: Map<String, String> = emptyMap(),
    val deltaUpdatePanels: Map<String, String> = emptyMap(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Thread-safe, centralized tracker that keeps the latest authenticated Master page state
 * synchronized across all current and future portal pages, including UpdatePanel AJAX deltas.
 */
class MasterPageTracker {

    private val lock = Any()

    @Volatile
    private var latestState: MasterPageState? = null

    fun currentState(): MasterPageState? = synchronized(lock) { latestState }

    fun clear(reason: String = "session_cleared") {
        val hadState: Boolean
        val prevPath: String?
        synchronized(lock) {
            hadState = latestState != null
            prevPath = latestState?.pageUrl?.toHttpUrlOrNull()?.encodedPath
            latestState = null
        }
        if (hadState) {
            AppLogger.logDebug(
                component = "MasterPageTracker",
                operation = "ClearPageState",
                message = "Cleared in-memory MasterPageState (prevPath=$prevPath, reason=$reason)"
            )
        }
    }

    fun onHttpResponse(
        url: String,
        statusCode: Int,
        contentType: String?,
        rawBody: String,
        isDeltaRequest: Boolean,
        isLogoutOperation: Boolean = false
    ) {
        if (isLogoutOperation || statusCode !in 200..299 || rawBody.isBlank()) return

        val parsedUrl = url.toHttpUrlOrNull() ?: return
        val pathLower = parsedUrl.encodedPath.lowercase()

        if (pathLower.contains("login.aspx")) {
            clear("login_page_observed")
            return
        }

        if (isStaticOrBinaryPath(pathLower)) return

        val looksLikeDelta = isDeltaRequest || looksLikeAspNetDelta(rawBody)
        if (looksLikeDelta) {
            val patch = DeltaParser.extractTrackerPatch(rawBody)
            if (patch.isSessionExpired) {
                clear("delta_session_expired")
                return
            }
            if (patch.hiddenFields.isNotEmpty() || patch.updatePanels.isNotEmpty()) {
                var patchedPath: String? = null
                synchronized(lock) {
                    val current = latestState
                    if (current != null && samePagePath(current.pageUrl, url)) {
                        val mergedHidden = current.deltaHiddenFields.toMutableMap().apply {
                            putAll(patch.hiddenFields)
                        }
                        val mergedPanels = current.deltaUpdatePanels.toMutableMap().apply {
                            putAll(patch.updatePanels)
                        }
                        latestState = current.copy(
                            source = MasterPageSource.DELTA_PATCHED,
                            deltaHiddenFields = mergedHidden,
                            deltaUpdatePanels = mergedPanels,
                            updatedAtMs = System.currentTimeMillis()
                        )
                        patchedPath = parsedUrl.encodedPath
                    }
                }
                if (patchedPath != null) {
                    AppLogger.logDebug(
                        component = "MasterPageTracker",
                        operation = "DeltaPatchedPageState",
                        message = "Patched MasterPageState for $patchedPath (hiddenFields=${patch.hiddenFields.keys}, panels=${patch.updatePanels.keys})"
                    )
                }
            }
            return
        }

        val isHtmlLike = contentType?.contains("html", ignoreCase = true) == true ||
            rawBody.trimStart().startsWith("<")
        if (!isHtmlLike) return

        if (LogoutFormParser.isAuthenticatedMasterPage(rawBody)) {
            synchronized(lock) {
                latestState = MasterPageState(
                    pageUrl = url,
                    html = rawBody,
                    source = MasterPageSource.FULL_HTML,
                    deltaHiddenFields = emptyMap(),
                    deltaUpdatePanels = emptyMap(),
                    updatedAtMs = System.currentTimeMillis()
                )
            }
            AppLogger.logDebug(
                component = "MasterPageTracker",
                operation = "TrackedMasterPage",
                message = "Tracked MasterPageState in memory for ${parsedUrl.encodedPath} (len=${rawBody.length})"
            )
        }
    }

    private fun samePagePath(urlA: String, urlB: String): Boolean {
        val pathA = urlA.toHttpUrlOrNull()?.encodedPath ?: return false
        val pathB = urlB.toHttpUrlOrNull()?.encodedPath ?: return false
        return pathA.equals(pathB, ignoreCase = true)
    }

    private fun isStaticOrBinaryPath(pathLower: String): Boolean {
        return pathLower.endsWith(".ashx") ||
            pathLower.endsWith(".png") ||
            pathLower.endsWith(".jpg") ||
            pathLower.endsWith(".jpeg") ||
            pathLower.endsWith(".gif") ||
            pathLower.endsWith(".svg") ||
            pathLower.endsWith(".ico") ||
            pathLower.endsWith(".css") ||
            pathLower.endsWith(".js") ||
            pathLower.endsWith(".woff") ||
            pathLower.endsWith(".woff2")
    }

    private fun looksLikeAspNetDelta(body: String): Boolean {
        return body.contains("|hiddenField|") ||
            body.contains("|updatePanel|") ||
            body.contains("|pageRedirect|")
    }
}
