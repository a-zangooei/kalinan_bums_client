package com.example.data.repository

import com.example.data.logger.LogSanitizer
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Dynamically inspects any authenticated ASP.NET WebForms Master page (e.g., Default.aspx,
 * Reservation.aspx, MyProfile.aspx, MyMessage.aspx, Survey_Answer.aspx, and future pages),
 * extracts the logout postback target from `a#logout`, resolves the form's relative `action`,
 * and constructs a browser-accurate `application/x-www-form-urlencoded` payload containing
 * all successful controls plus any updated UpdatePanel Delta hidden fields.
 */
internal object LogoutFormParser {

    const val DEFAULT_LOGOUT_TARGET = "ctl00\$logout"
    const val DEFAULT_LOGOUT_ARGUMENT = ""

    private val DO_POSTBACK_REGEX = Regex(
        """__doPostBack\(\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*\)"""
    )

    private val VIEWSTATE_CHUNK_KEY_REGEX = Regex("""^__VIEWSTATE\d+$""")

    private val EXCLUDED_INPUT_TYPES = setOf("submit", "button", "image", "reset", "file")

    data class LogoutTarget(
        val eventTarget: String,
        val eventArgument: String,
        val usedDefaultFallback: Boolean
    )

    data class BuiltLogoutPayload(
        val postUrl: String,
        val originUrl: String,
        val refererUrl: String,
        val formBody: FormBody,
        val formId: String,
        val fieldCount: Int,
        val formKeys: List<String>,
        val eventTarget: String,
        val eventArgument: String,
        val usedDefaultTarget: Boolean,
        val viewStateGenerator: String?,
        val viewStateLength: Int,
        val viewStateFingerprint: String?,
        val viewStateChunksCount: Int?,
        val hasEventValidation: Boolean,
        val eventValidationLength: Int?,
        val excludedControlsCount: Int,
        val excludedImageOrButtonCount: Int,
        val payloadByteSize: Long
    )

    /**
     * Quick check verifying an HTML payload represents an authenticated Master page with a logout control
     * and ASP.NET WebForms state (never Login.aspx or empty error fragments).
     */
    fun isAuthenticatedMasterPage(html: String): Boolean {
        if (html.isBlank()) return false
        val isLoginHtml = html.contains("txtUsernamePlain", ignoreCase = true) ||
            html.contains("publicKeytxt", ignoreCase = true)
        if (isLoginHtml) return false

        val hasViewState = html.contains("__VIEWSTATE", ignoreCase = false)
        val hasLogoutMarker = html.contains("id=\"logout\"", ignoreCase = true) ||
            html.contains("id='logout'", ignoreCase = true) ||
            html.contains("ctl00\$logout", ignoreCase = false)
        return hasViewState && hasLogoutMarker
    }

    fun extractLogoutTarget(doc: Document): LogoutTarget {
        val logoutLink = doc.selectFirst("a#logout")
            ?: doc.selectFirst("a[id$=logout]")
            ?: doc.selectFirst("a[href*=logout]")

        val href = logoutLink?.attr("href")?.trim().orEmpty()
        val match = DO_POSTBACK_REGEX.find(href)
        return if (match != null) {
            LogoutTarget(
                eventTarget = match.groupValues[1].ifBlank { DEFAULT_LOGOUT_TARGET },
                eventArgument = match.groupValues[2],
                usedDefaultFallback = false
            )
        } else {
            LogoutTarget(
                eventTarget = DEFAULT_LOGOUT_TARGET,
                eventArgument = DEFAULT_LOGOUT_ARGUMENT,
                usedDefaultFallback = true
            )
        }
    }

    fun buildLogoutPayload(
        pageUrl: String,
        html: String,
        deltaHiddenFields: Map<String, String> = emptyMap(),
        deltaUpdatePanels: Map<String, String> = emptyMap()
    ): BuiltLogoutPayload? {
        if (!isAuthenticatedMasterPage(html)) return null
        val baseHttpUrl = pageUrl.toHttpUrlOrNull() ?: return null

        val doc = Jsoup.parse(html, pageUrl)
        val hasDomLogoutMarker = doc.selectFirst("a#logout, a[id$=logout], a[href*=logout]") != null ||
            html.contains("ctl00\$logout")
        if (!hasDomLogoutMarker) return null

        val form = doc.selectFirst("form#MainForm")
            ?: doc.select("form").firstOrNull { candidate ->
                candidate.selectFirst("input[name=__VIEWSTATE], input[name=__VIEWSTATEFIELDCOUNT]") != null
            }
            ?: return null

        // Patch any updated UpdatePanel fragments before collecting successful controls
        if (deltaUpdatePanels.isNotEmpty()) {
            applyDeltaUpdatePanels(form, deltaUpdatePanels)
        }

        val logoutTarget = extractLogoutTarget(doc)

        val rawAction = form.attr("action").trim()
        val resolvedActionUrl = if (rawAction.isNotEmpty()) {
            baseHttpUrl.resolve(rawAction)
        } else {
            baseHttpUrl
        }
        // Keep same-origin security invariant: never post session cookies off-origin
        val safePostHttpUrl = if (resolvedActionUrl != null &&
            resolvedActionUrl.host.equals(baseHttpUrl.host, ignoreCase = true)
        ) {
            resolvedActionUrl
        } else {
            baseHttpUrl
        }

        val originUrl = "${baseHttpUrl.scheme}://${baseHttpUrl.host}" +
            if ((baseHttpUrl.scheme == "https" && baseHttpUrl.port != 443) ||
                (baseHttpUrl.scheme == "http" && baseHttpUrl.port != 80)
            ) {
                ":${baseHttpUrl.port}"
            } else {
                ""
            }

        val collectedControls = mutableListOf<Pair<String, String>>()
        var excludedControlsCount = 0
        var excludedImageOrButtonCount = 0

        val formControls = form.select("input, select, textarea, button")
        for (el in formControls) {
            val tag = el.tagName().lowercase()
            if (tag == "button") {
                excludedControlsCount++
                excludedImageOrButtonCount++
                continue
            }

            if (el.hasAttr("disabled")) {
                excludedControlsCount++
                continue
            }

            val name = el.attr("name").trim()
            if (name.isEmpty()) {
                excludedControlsCount++
                continue
            }

            when (tag) {
                "input" -> {
                    val type = el.attr("type").trim().lowercase()
                    if (type in EXCLUDED_INPUT_TYPES) {
                        excludedControlsCount++
                        if (type == "image" || type == "submit" || type == "button") {
                            excludedImageOrButtonCount++
                        }
                        continue
                    }
                    if (type == "checkbox" || type == "radio") {
                        if (el.hasAttr("checked")) {
                            val checkedVal = el.attr("value").ifEmpty { "on" }
                            collectedControls.add(name to checkedVal)
                        }
                    } else {
                        collectedControls.add(name to el.attr("value"))
                    }
                }
                "select" -> {
                    collectSelectValues(el, name, collectedControls)
                }
                "textarea" -> {
                    collectedControls.add(name to el.text())
                }
            }
        }

        // Apply latest Delta hidden fields (e.g., __VIEWSTATE, __VIEWSTATEGENERATOR, __EVENTVALIDATION)
        if (deltaHiddenFields.isNotEmpty()) {
            applyDeltaHiddenFields(collectedControls, deltaHiddenFields)
        }

        // Overwrite __EVENTTARGET and __EVENTARGUMENT without duplicating keys
        upsertControl(collectedControls, "__EVENTTARGET", logoutTarget.eventTarget, insertAtIndex = 0)
        upsertControl(collectedControls, "__EVENTARGUMENT", logoutTarget.eventArgument, insertAtIndex = 1)

        val formBodyBuilder = FormBody.Builder(Charsets.UTF_8)
        var viewStateLen = 0
        val vsChunks = mutableListOf<String>()
        var viewStateGenerator: String? = null
        var hasEventValidation = false
        var eventValidationLen: Int? = null
        val formKeys = mutableListOf<String>()

        for ((key, value) in collectedControls) {
            formBodyBuilder.add(key, value)
            if (formKeys.size < 60) {
                formKeys.add(key)
            }
            when {
                key == "__VIEWSTATE" || VIEWSTATE_CHUNK_KEY_REGEX.matches(key) -> {
                    viewStateLen += value.length
                    vsChunks.add(value)
                }
                key == "__VIEWSTATEGENERATOR" -> viewStateGenerator = value
                key == "__EVENTVALIDATION" -> {
                    hasEventValidation = true
                    eventValidationLen = value.length
                }
            }
        }

        val formBody = formBodyBuilder.build()
        val payloadBytes = runCatching { formBody.contentLength() }.getOrDefault(-1L)

        return BuiltLogoutPayload(
            postUrl = safePostHttpUrl.toString(),
            originUrl = originUrl,
            refererUrl = baseHttpUrl.toString(),
            formBody = formBody,
            formId = form.id().ifBlank { "unknown" },
            fieldCount = collectedControls.size,
            formKeys = formKeys,
            eventTarget = logoutTarget.eventTarget,
            eventArgument = logoutTarget.eventArgument,
            usedDefaultTarget = logoutTarget.usedDefaultFallback,
            viewStateGenerator = viewStateGenerator,
            viewStateLength = viewStateLen,
            viewStateFingerprint = vsChunks.firstOrNull()?.let { LogSanitizer.computeFingerprint(it) },
            viewStateChunksCount = if (vsChunks.size > 1) vsChunks.size else null,
            hasEventValidation = hasEventValidation,
            eventValidationLength = eventValidationLen,
            excludedControlsCount = excludedControlsCount,
            excludedImageOrButtonCount = excludedImageOrButtonCount,
            payloadByteSize = payloadBytes
        )
    }

    private fun applyDeltaUpdatePanels(form: Element, deltaUpdatePanels: Map<String, String>) {
        for ((panelKey, panelHtml) in deltaUpdatePanels) {
            if (panelHtml.isBlank()) continue
            val shortId = panelKey.substringAfterLast('$')
            val targetEl = form.getElementById(panelKey)
                ?: form.getElementById(shortId)
                ?: form.getElementById(panelKey.replace('$', '_'))
            targetEl?.html(panelHtml)
        }
    }

    private fun applyDeltaHiddenFields(
        controls: MutableList<Pair<String, String>>,
        deltaHiddenFields: Map<String, String>
    ) {
        val deltaHasSingleVs = deltaHiddenFields.containsKey("__VIEWSTATE") &&
            !deltaHiddenFields.containsKey("__VIEWSTATEFIELDCOUNT")
        if (deltaHasSingleVs) {
            controls.removeAll { (key, _) ->
                key == "__VIEWSTATEFIELDCOUNT" ||
                    (VIEWSTATE_CHUNK_KEY_REGEX.matches(key) && !deltaHiddenFields.containsKey(key))
            }
        }

        for ((key, value) in deltaHiddenFields) {
            if (key == "__EVENTTARGET" || key == "__EVENTARGUMENT") continue
            upsertControl(controls, key, value, insertAtIndex = null)
        }
    }

    private fun upsertControl(
        controls: MutableList<Pair<String, String>>,
        key: String,
        value: String,
        insertAtIndex: Int?
    ) {
        val existingIndices = controls.mapIndexedNotNull { idx, pair ->
            if (pair.first == key) idx else null
        }
        if (existingIndices.isEmpty()) {
            if (insertAtIndex != null) {
                val safeIdx = insertAtIndex.coerceIn(0, controls.size)
                controls.add(safeIdx, key to value)
            } else {
                controls.add(key to value)
            }
            return
        }
        val firstIdx = existingIndices.first()
        controls[firstIdx] = key to value
        for (idx in existingIndices.drop(1).asReversed()) {
            controls.removeAt(idx)
        }
    }

    private fun collectSelectValues(
        selectEl: Element,
        name: String,
        controls: MutableList<Pair<String, String>>
    ) {
        val options = selectEl.select("option")
        if (options.isEmpty()) return

        if (selectEl.hasAttr("multiple")) {
            for (opt in options) {
                if (!opt.hasAttr("disabled") && opt.hasAttr("selected")) {
                    controls.add(name to optionValue(opt))
                }
            }
        } else {
            val selectedOpt = options.firstOrNull { !it.hasAttr("disabled") && it.hasAttr("selected") }
                ?: options.firstOrNull { !it.hasAttr("disabled") }
            if (selectedOpt != null) {
                controls.add(name to optionValue(selectedOpt))
            }
        }
    }

    private fun optionValue(option: Element): String {
        return if (option.hasAttr("value")) option.attr("value") else option.text()
    }
}
