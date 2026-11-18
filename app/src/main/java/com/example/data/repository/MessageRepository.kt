package com.example.data.repository

import com.example.data.logger.AppLogger
import com.example.data.logger.LogContext
import com.example.data.logger.LogEntry
import com.example.data.logger.LogLevel
import com.example.data.logger.LogSanitizer
import com.example.data.model.MessageError
import com.example.data.model.MessageItem
import com.example.data.model.MessageOpenResult
import com.example.data.model.MessagesPageState
import com.example.data.model.WebFormsState
import com.example.data.network.CullinanHttpClient
import com.example.data.parser.DeltaParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup

class MessageRepository {

    private val mutex = Mutex()

    private data class PagerMetadata(
        val hasPager: Boolean,
        val currentPage: Int,
        val totalKnownPages: Int,
        val nextPageEventTarget: String?,
        val nextPageEventArgument: String?,
        val pageArgumentsMap: Map<Int, Pair<String, String>> = emptyMap(),
        val defaultEventTarget: String = GRID_CONTROL_EVENT_TARGET
    )

    private data class PagerLinkCandidate(
        val eventTarget: String,
        val eventArgument: String,
        val linkText: String,
        val pageNum: Int?,
        val isAfterCurrent: Boolean
    )

    /**
     * Fetches the MyMessage.aspx page, parses the messages table, and determines whether
     * the reservation menu is unlocked or blocked by unread messages.
     */
    suspend fun fetchMessagesPage(): Result<MessagesPageState> = withContext(Dispatchers.IO) {
        val flowId = AppLogger.generateFlowId("msg_fetch")
        AppLogger.log(
            LogEntry(
                timestamp = "",
                level = LogLevel.INFO,
                component = "MessageRepository",
                operation = "FetchMessagesPage",
                flowId = flowId,
                message = "Initiating fetch of user messages page"
            )
        )

        mutex.withLock {
            fetchMessagesPageInternal(flowId)
        }
    }

    private fun fetchMessagesPageInternal(flowId: String): Result<MessagesPageState> {
        return try {
            val request = Request.Builder()
                .url(CullinanHttpClient.MESSAGES_URL)
                .get()
                .header("Referer", CullinanHttpClient.BASE_URL)
                .tag(LogContext::class.java, LogContext(flowId, "FetchMessagesPage"))
                .build()

            var isSuccessful = false
            var responseCode = 0
            var html = ""
            CullinanHttpClient.executeCall(request).use { response ->
                isSuccessful = response.isSuccessful
                responseCode = response.code
                if (isSuccessful) {
                    html = response.body?.string() ?: ""
                }
            }
            if (!isSuccessful) {
                AppLogger.logError("MessageRepository", "Failed to fetch messages page: HTTP $responseCode", flowId = flowId)
                return Result.failure(Exception("خطا در دریافت صفحه پیام‌های سامانه ($responseCode)"))
            }

            val parsed = parseFullMessagesDocument(html, flowId)
            AppLogger.log(
                LogEntry(
                    timestamp = "",
                    level = LogLevel.INFO,
                    component = "MessageRepository",
                    operation = "FetchMessagesPageSuccess",
                    flowId = flowId,
                    message = "Messages parsed: page=${parsed.currentServerPage}/${parsed.totalKnownPages}, total=${parsed.messages.size}, unread=${parsed.unreadCount}, unlocked=${parsed.isReservationUnlocked}, pager=${parsed.hasPaginationControls}, nextArg=${parsed.nextPageEventArgument}, SM='${parsed.scriptManagerName}', UP='${parsed.updatePanelName}'"
                )
            )
            Result.success(parsed)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.logError("MessageRepository", "Exception in fetchMessagesPage: ${e.message}", e, flowId = flowId)
            Result.failure(e)
        }
    }

    /**
     * Loads the next page of messages using ASP.NET GridView pagination (__doPostBack 'Page$N')
     * and appends new rows onto [currentState].
     */
    suspend fun fetchNextMessagesPage(
        currentState: MessagesPageState
    ): Result<MessagesPageState> = withContext(Dispatchers.IO) {
        val flowId = AppLogger.generateFlowId("msg_next_page")
        mutex.withLock {
            val target = currentState.nextPageEventTarget
            val argument = currentState.nextPageEventArgument
            if (target.isNullOrBlank() || argument.isNullOrBlank()) {
                return@withLock Result.success(currentState.copy(isLoadingMore = false))
            }

            AppLogger.logInfo(
                component = "MessageRepository",
                operation = "FetchNextMessagesPage",
                message = "Requesting messages pagination target='$target', arg='$argument' (currentServerPage=${currentState.currentServerPage}, loadedCount=${currentState.messages.size})",
                flowId = flowId
            )

            try {
                // If the user opened a message from a different 10-page block and we need to advance
                // beyond loaded pages, use the active server state safely.
                val pageResult = postPaginationInternal(
                    eventTarget = target,
                    eventArgument = argument,
                    baseState = currentState,
                    flowId = flowId
                )

                val pageState = pageResult.getOrElse { err ->
                    return@withLock Result.failure(err)
                }

                val mergedMessages = mergeMessagesPreservingOrder(
                    existing = currentState.messages,
                    incoming = pageState.messages
                )
                val unreadCount = mergedMessages.count { !it.isRead }
                val distinctLoadedPages = mergedMessages.map { it.pageNumber }.distinct().size.coerceAtLeast(1)

                val combined = pageState.copy(
                    messages = mergedMessages,
                    hasUnreadMessages = unreadCount > 0,
                    unreadCount = unreadCount,
                    hasPaginationControls = currentState.hasPaginationControls || pageState.hasPaginationControls,
                    isReservationUnlocked = currentState.isReservationUnlocked || pageState.isReservationUnlocked,
                    loadedPagesCount = distinctLoadedPages,
                    totalKnownPages = maxOf(currentState.totalKnownPages, pageState.totalKnownPages, distinctLoadedPages),
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = null
                )

                AppLogger.logInfo(
                    component = "MessageRepository",
                    operation = "FetchNextMessagesPageSuccess",
                    message = "Pagination succeeded: serverPage=${combined.currentServerPage}, added=${mergedMessages.size - currentState.messages.size}, totalMessages=${mergedMessages.size}, hasNext=${combined.hasNextPage}",
                    flowId = flowId
                )

                Result.success(combined)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogger.logError("MessageRepository", "Exception in fetchNextMessagesPage: ${e.message}", e, flowId = flowId)
                Result.failure(e)
            }
        }
    }

    /**
     * Opens a specific [MessageItem], automatically switching the ASP.NET GridView page first
     * if [message.pageNumber] differs from [currentState.currentServerPage], so `ctl02..ctl06`
     * row buttons always resolve against the right page's ViewState.
     */
    suspend fun openMessageForItem(
        message: MessageItem,
        currentState: MessagesPageState
    ): Result<Pair<MessagesPageState, MessageItem>> = withContext(Dispatchers.IO) {
        val flowId = AppLogger.generateFlowId("msg_open_item")
        mutex.withLock {
            try {
                var workingState = currentState
                var targetButtonName = message.buttonInputName

                if (message.pageNumber != workingState.currentServerPage) {
                    AppLogger.logInfo(
                        component = "MessageRepository",
                        operation = "SwitchPageBeforeOpen",
                        message = "Switching GridView page ${workingState.currentServerPage} -> ${message.pageNumber} before opening row=${message.rowIndex}",
                        flowId = flowId
                    )

                    val switchedResult = navigateToSpecificPageInternal(
                        targetPage = message.pageNumber,
                        baseState = workingState,
                        flowId = flowId
                    )
                    val switchedPageState = switchedResult.getOrElse { err ->
                        return@withLock Result.failure(err)
                    }

                    // Locate the matching button on the newly active page
                    val matchedOnPage = switchedPageState.messages.firstOrNull {
                        it.rowIndex == message.rowIndex && it.title == message.title
                    } ?: switchedPageState.messages.firstOrNull {
                        it.title == message.title && it.sendDate == message.sendDate && it.sendTime == message.sendTime
                    }
                    if (matchedOnPage != null && matchedOnPage.buttonInputName.isNotBlank()) {
                        targetButtonName = matchedOnPage.buttonInputName
                    }

                    // Update server ViewState/currentServerPage while keeping previously loaded messages & pagination target
                    val maxLoadedPage = workingState.messages.maxOfOrNull { it.pageNumber } ?: 1
                    val nextPageNumber = maxLoadedPage + 1
                    val preservedNextTarget = if (workingState.hasNextPage) workingState.nextPageEventTarget else switchedPageState.nextPageEventTarget
                    val preservedNextArg = when {
                        workingState.hasNextPage && workingState.nextPageEventArgument != null -> workingState.nextPageEventArgument
                        nextPageNumber <= switchedPageState.totalKnownPages -> "Page\$$nextPageNumber"
                        else -> switchedPageState.nextPageEventArgument
                    }

                    workingState = workingState.copy(
                        viewState = switchedPageState.viewState,
                        viewStateGenerator = switchedPageState.viewStateGenerator,
                        eventValidation = switchedPageState.eventValidation,
                        scriptManagerName = switchedPageState.scriptManagerName,
                        updatePanelName = switchedPageState.updatePanelName,
                        currentServerPage = switchedPageState.currentServerPage,
                        nextPageEventTarget = preservedNextTarget,
                        nextPageEventArgument = preservedNextArg
                    )
                }

                when (val openRes = openMessageInternal(
                    buttonInputName = targetButtonName,
                    state = workingState.toWebFormsState(),
                    scriptManagerName = workingState.scriptManagerName,
                    updatePanelName = workingState.updatePanelName,
                    flowId = flowId
                )) {
                    is MessageOpenResult.Failure -> Result.failure(openRes.error)
                    is MessageOpenResult.Success -> {
                        val updatedItem = message.copy(
                            isRead = true,
                            messageText = openRes.messageText
                        )
                        val updatedList = workingState.messages.map { item ->
                            if (isSameMessage(item, message)) updatedItem else item
                        }
                        val newUnread = updatedList.count { !it.isRead }
                        val updatedPageState = workingState.withUpdatedWebFormsState(openRes.updatedState).copy(
                            messages = updatedList,
                            unreadCount = newUnread,
                            hasUnreadMessages = newUnread > 0
                        )
                        Result.success(Pair(updatedPageState, updatedItem))
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogger.logError("MessageRepository", "Exception in openMessageForItem: ${e.message}", e, flowId = flowId)
                Result.failure(e)
            }
        }
    }

    /**
     * Opens a specific message using ASP.NET partial-rendering Delta POST.
     * Records the message as read on server side, updates WebForms state, and extracts message text.
     *
     * IMPORTANT: No automatic retry to prevent state corruption.
     */
    suspend fun openMessage(
        buttonInputName: String,
        state: WebFormsState,
        scriptManagerName: String = "ctl00\$ScriptManager",
        updatePanelName: String = "ctl00\$UpdatePanel1"
    ): MessageOpenResult = withContext(Dispatchers.IO) {
        val flowId = AppLogger.generateFlowId("msg_open")
        openMessageInternal(buttonInputName, state, scriptManagerName, updatePanelName, flowId)
    }

    private fun openMessageInternal(
        buttonInputName: String,
        state: WebFormsState,
        scriptManagerName: String = "ctl00\$ScriptManager",
        updatePanelName: String = "ctl00\$UpdatePanel1",
        flowId: String = AppLogger.generateFlowId("msg_open")
    ): MessageOpenResult {
        AppLogger.log(
            LogEntry(
                timestamp = "",
                level = LogLevel.INFO,
                component = "MessageRepository",
                operation = "OpenMessageAttempt",
                flowId = flowId,
                message = "Executing Delta POST to open message (button: $buttonInputName)"
            )
        )

        if (buttonInputName.isBlank()) {
            AppLogger.logError("MessageRepository", "buttonInputName cannot be blank", flowId = flowId)
            return MessageOpenResult.Failure(
                MessageError.ServerError(400, "شناسه دکمه پیام موجود نیست")
            )
        }

        return try {
            val formBuilder = FormBody.Builder()
                .add(scriptManagerName, "$updatePanelName|$buttonInputName")
                .add("__EVENTTARGET", "")
                .add("__EVENTARGUMENT", "")
                .add("__VIEWSTATE", state.viewState)
                .add("__VIEWSTATEGENERATOR", state.viewStateGenerator.ifBlank { "87AF9C2D" })
                .add("__VIEWSTATEENCRYPTED", "")

            if (!state.eventValidation.isNullOrBlank()) {
                formBuilder.add("__EVENTVALIDATION", state.eventValidation)
            }

            formBuilder.add("ctl00\$cphMain\$hdnId", "")
                .add("__ASYNCPOST", "true")
                .add("$buttonInputName.x", "10")
                .add("$buttonInputName.y", "10")

            val request = Request.Builder()
                .url(CullinanHttpClient.MESSAGES_URL)
                .post(formBuilder.build())
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-MicrosoftAjax", "Delta=true")
                .header("Cache-Control", "no-cache")
                .header("Accept", "*/*")
                .header("Origin", CullinanHttpClient.BASE_URL)
                .header("Referer", CullinanHttpClient.MESSAGES_URL)
                .tag(LogContext::class.java, LogContext(flowId, "OpenMessageDeltaPost"))
                .build()

            var isSuccessful = false
            var responseCode = 0
            var deltaText = ""
            CullinanHttpClient.executeCall(request).use { response ->
                isSuccessful = response.isSuccessful
                responseCode = response.code
                if (isSuccessful) {
                    deltaText = response.body?.string() ?: ""
                }
            }
            if (!isSuccessful) {
                AppLogger.logError("MessageRepository", "Open message failed HTTP $responseCode", flowId = flowId)
                return MessageOpenResult.Failure(
                    MessageError.ServerError(responseCode, "پاسخ ناموفق از سرور با کد $responseCode")
                )
            }

            // Strict check 1: Check for full HTML page (redirect or error) instead of delta
            val isFullHtml = (deltaText.contains("<!DOCTYPE", ignoreCase = true) || deltaText.contains("<html", ignoreCase = true)) &&
                !deltaText.contains("|updatePanel|") && !deltaText.contains("|hiddenField|")

            if (isFullHtml) {
                if (deltaText.contains("Login.aspx", ignoreCase = true) || deltaText.contains("txtUsernamePlain")) {
                    AppLogger.logError("MessageRepository", "Session expired detected via HTML redirect", flowId = flowId)
                    return MessageOpenResult.Failure(MessageError.SessionExpired)
                }
                AppLogger.logError("MessageRepository", "Unexpected full HTML received instead of Delta response", flowId = flowId)
                return MessageOpenResult.Failure(MessageError.InvalidDeltaResponse)
            }

            val deltaResponse = DeltaParser.parse(deltaText, flowId = flowId)

            // Strict check 2: Session expired token
            if (deltaResponse.isSessionExpired) {
                AppLogger.logError("MessageRepository", "Session expired detected via Delta redirect", flowId = flowId)
                return MessageOpenResult.Failure(MessageError.SessionExpired)
            }

            // Strict check 3: Valid Delta structure
            if (deltaResponse.rawTokens.isEmpty()) {
                AppLogger.logError("MessageRepository", "Empty delta tokens returned by server", flowId = flowId)
                return MessageOpenResult.Failure(MessageError.InvalidDeltaResponse)
            }

            // Strict check 4: Presence of UpdatePanel
            if (deltaResponse.updatePanels.isEmpty()) {
                AppLogger.logError("MessageRepository", "No update panels returned in delta response", flowId = flowId)
                return MessageOpenResult.Failure(MessageError.MissingUpdatePanel)
            }

            // Strict check 5: Strict message extraction from update panels or message paragraph
            var messageContent: String? = null

            // 1. Search in all update panels (e.g. UpdatePanel1, ctl00$UpdatePanel1)
            for ((_, panelHtml) in deltaResponse.updatePanels) {
                val doc = Jsoup.parse(panelHtml)
                val pText = doc.select("#cphMain_pMessage, .messageText, #lblMessageText").text().trim()
                if (pText.isNotBlank()) {
                    messageContent = pText
                    break
                }
            }

            // 2. Fallback regex search for explicit message container tag in delta
            if (messageContent.isNullOrBlank()) {
                val pMatch = Regex("""<(?:p|span|div)[^>]*id=["'](?:cphMain_)?(?:p|lbl)?Message(?:Text)?["'][^>]*>([\s\S]*?)</(?:p|span|div)>""", RegexOption.IGNORE_CASE).find(deltaText)
                if (pMatch != null) {
                    val candidate = Jsoup.parse(pMatch.groupValues[1]).text().trim()
                    if (candidate.isNotBlank()) {
                        messageContent = candidate
                        AppLogger.logWarn(
                            component = "MessageRepository",
                            operation = "OpenMessageRegexFallback",
                            message = "Extracted message content via fallback regex instead of UpdatePanel DOM selector",
                            flowId = flowId,
                            errorCategory = "MESSAGE_SELECTOR_FALLBACK"
                        )
                    }
                }
            }

            // If still missing or empty, fail strictly without silent empty success
            if (messageContent.isNullOrBlank()) {
                AppLogger.logError("MessageRepository", "No message content found inside expected panel elements", flowId = flowId)
                return MessageOpenResult.Failure(MessageError.EmptyMessageContent)
            }

            // Extract updated WebForms atomic state (ViewState, Generator, EventValidation)
            val updatedState = DeltaParser.extractWebFormsState(deltaResponse, state)

            // Privacy-preserving diagnostics: NEVER log messageContent or raw delta body
            AppLogger.log(
                LogEntry(
                    timestamp = "",
                    level = LogLevel.INFO,
                    component = "MessageRepository",
                    operation = "OpenMessageSuccess",
                    flowId = flowId,
                    message = "Message successfully opened. Extracted text length: ${messageContent.length}, Delta tokens: ${deltaResponse.rawTokens.size}, Panels: ${deltaResponse.updatePanels.keys.joinToString()}, State fingerprint: ${LogSanitizer.computeFingerprint(updatedState.viewState)}"
                )
            )

            MessageOpenResult.Success(
                updatedState = updatedState,
                messageText = messageContent
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.logError("MessageRepository", "Exception in openMessage: ${e.message}", e, flowId = flowId)
            MessageOpenResult.Failure(MessageError.NetworkError(e))
        }
    }

    /**
     * Backwards-compatible overload for openMessage with primitive parameters.
     */
    suspend fun openMessage(
        buttonInputName: String,
        currentViewState: String,
        currentViewStateGenerator: String = "87AF9C2D",
        currentEventValidation: String = "",
        scriptManagerName: String = "ctl00\$ScriptManager",
        updatePanelName: String = "ctl00\$UpdatePanel1"
    ): MessageOpenResult = openMessage(
        buttonInputName = buttonInputName,
        state = WebFormsState(
            viewState = currentViewState,
            viewStateGenerator = currentViewStateGenerator.ifBlank { "87AF9C2D" },
            eventValidation = currentEventValidation.ifBlank { null }
        ),
        scriptManagerName = scriptManagerName,
        updatePanelName = updatePanelName
    )

    /**
     * Sequentially marks all unread messages as read with serialized ViewState and EventValidation chaining.
     * Enforces safety protections:
     * - Hard loop ceiling (max 50)
     * - Zero automatic retry on POST failures
     * - Live progress callback
     * - Final verification GET to ensure menu unlocked
     */
    suspend fun markAllUnreadAsReadSerialized(
        initialState: MessagesPageState,
        onProgress: (currentIndex: Int, totalCount: Int, currentTitle: String, messageText: String) -> Unit
    ): Result<MessagesPageState> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val flowId = AppLogger.generateFlowId("msg_serial")
            AppLogger.log(
                LogEntry(
                    timestamp = "",
                    level = LogLevel.INFO,
                    component = "MessageRepository",
                    operation = "MarkAllUnreadSerialized",
                    flowId = flowId,
                    message = "Starting serialized unread message processing"
                )
            )

            var workingState = initialState
            val unreadList = workingState.messages.filter { !it.isRead && it.buttonInputName.isNotBlank() }
            val totalCount = unreadList.size

            if (totalCount == 0) {
                return@withLock fetchMessagesPageInternal(flowId)
            }

            var currentWebFormsState = workingState.toWebFormsState()
            var currentServerPage = workingState.currentServerPage
            val updatedMessages = workingState.messages.toMutableList()

            // Safety limit: max 50 iterations
            val loopLimit = minOf(totalCount, 50)

            for (i in 0 until loopLimit) {
                val targetMsg = unreadList[i]
                if (targetMsg.buttonInputName.isBlank()) {
                    AppLogger.logError("MessageRepository", "Missing buttonInputName for message: ${targetMsg.title}", flowId = flowId)
                    return@withLock Result.failure(Exception("شناسه دکمه پیام '${targetMsg.title}' یافت نشد"))
                }

                var targetBtn = targetMsg.buttonInputName
                if (targetMsg.pageNumber != currentServerPage) {
                    val switchRes = navigateToSpecificPageInternal(
                        targetPage = targetMsg.pageNumber,
                        baseState = workingState.withUpdatedWebFormsState(currentWebFormsState).copy(
                            currentServerPage = currentServerPage
                        ),
                        flowId = flowId
                    )
                    val switched = switchRes.getOrElse { err ->
                        return@withLock Result.failure(err)
                    }
                    currentWebFormsState = switched.toWebFormsState()
                    currentServerPage = switched.currentServerPage
                    val matched = switched.messages.firstOrNull {
                        it.rowIndex == targetMsg.rowIndex && it.title == targetMsg.title
                    }
                    if (matched != null && matched.buttonInputName.isNotBlank()) {
                        targetBtn = matched.buttonInputName
                    }
                }

                // Execute exact Delta POST with chained WebFormsState
                val openResult = openMessageInternal(
                    buttonInputName = targetBtn,
                    state = currentWebFormsState,
                    scriptManagerName = workingState.scriptManagerName,
                    updatePanelName = workingState.updatePanelName,
                    flowId = flowId
                )

                when (openResult) {
                    is MessageOpenResult.Failure -> {
                        AppLogger.logError("MessageRepository", "Aborting serialized read due to failure on message '${targetMsg.title}': ${openResult.error.userMessage}", openResult.error, flowId = flowId)
                        return@withLock Result.failure(openResult.error)
                    }
                    is MessageOpenResult.Success -> {
                        currentWebFormsState = openResult.updatedState

                        // Update local memory list
                        val listIdx = updatedMessages.indexOfFirst { isSameMessage(it, targetMsg) }
                        if (listIdx != -1) {
                            updatedMessages[listIdx] = targetMsg.copy(
                                isRead = true,
                                messageText = openResult.messageText
                            )
                        }

                        // Notify live UI listener
                        onProgress(i + 1, totalCount, targetMsg.title, openResult.messageText)
                    }
                }
            }

            // Final check: Perform a fresh GET to verify that the reservation menu has been unlocked
            val verifyResult = fetchMessagesPageInternal(flowId)
            if (verifyResult.isSuccess) {
                val freshState = verifyResult.getOrThrow()
                val mergedMessages = freshState.messages.map { freshMsg ->
                    val local = updatedMessages.firstOrNull { isSameMessage(it, freshMsg) }
                    if (local != null && local.isRead) {
                        freshMsg.copy(isRead = true, messageText = local.messageText)
                    } else {
                        freshMsg
                    }
                }
                AppLogger.logInfo(
                    component = "MessageRepository",
                    operation = "MarkAllUnreadVerified",
                    message = "Serialized read verified via fresh GET: unlocked=${freshState.isReservationUnlocked}, remainingUnread=${mergedMessages.count { !it.isRead }}",
                    flowId = flowId
                )
                Result.success(
                    freshState.copy(
                        messages = mergedMessages,
                        unreadCount = mergedMessages.count { !it.isRead },
                        hasUnreadMessages = mergedMessages.any { !it.isRead }
                    )
                )
            } else {
                AppLogger.logWarn(
                    component = "MessageRepository",
                    operation = "MarkAllUnreadVerifyFallback",
                    message = "Final verification GET failed after marking $loopLimit messages; returning locally chained state",
                    throwable = verifyResult.exceptionOrNull(),
                    flowId = flowId,
                    errorCategory = "MESSAGES_VERIFY_FALLBACK"
                )
                Result.success(
                    workingState.withUpdatedWebFormsState(currentWebFormsState).copy(
                        messages = updatedMessages,
                        unreadCount = updatedMessages.count { !it.isRead },
                        hasUnreadMessages = updatedMessages.any { !it.isRead }
                    )
                )
            }
        }
    }

    private fun navigateToSpecificPageInternal(
        targetPage: Int,
        baseState: MessagesPageState,
        flowId: String
    ): Result<MessagesPageState> {
        if (targetPage <= 1) {
            val postRes = postPaginationInternal(
                eventTarget = GRID_CONTROL_EVENT_TARGET,
                eventArgument = "Page\$1",
                baseState = baseState,
                flowId = flowId
            )
            if (postRes.isSuccess && postRes.getOrThrow().currentServerPage == 1) {
                return postRes
            }
            return fetchMessagesPageInternal(flowId)
        }

        return postPaginationInternal(
            eventTarget = GRID_CONTROL_EVENT_TARGET,
            eventArgument = "Page\$$targetPage",
            baseState = baseState,
            flowId = flowId
        )
    }

    private fun postPaginationInternal(
        eventTarget: String,
        eventArgument: String,
        baseState: MessagesPageState,
        flowId: String
    ): Result<MessagesPageState> {
        val state = baseState.toWebFormsState()
        val formBuilder = FormBody.Builder()
            .add(baseState.scriptManagerName, "${baseState.updatePanelName}|$eventTarget")
            .add("__EVENTTARGET", eventTarget)
            .add("__EVENTARGUMENT", eventArgument)
            .add("__VIEWSTATE", state.viewState)
            .add("__VIEWSTATEGENERATOR", state.viewStateGenerator.ifBlank { "87AF9C2D" })
            .add("__VIEWSTATEENCRYPTED", "")

        if (!state.eventValidation.isNullOrBlank()) {
            formBuilder.add("__EVENTVALIDATION", state.eventValidation)
        }

        formBuilder.add("ctl00\$cphMain\$hdnId", "")
            .add("__ASYNCPOST", "true")

        val request = Request.Builder()
            .url(CullinanHttpClient.MESSAGES_URL)
            .post(formBuilder.build())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-MicrosoftAjax", "Delta=true")
            .header("Cache-Control", "no-cache")
            .header("Accept", "*/*")
            .header("Origin", CullinanHttpClient.BASE_URL)
            .header("Referer", CullinanHttpClient.MESSAGES_URL)
            .tag(LogContext::class.java, LogContext(flowId, "FetchMessagesPageDelta"))
            .build()

        var isSuccessful = false
        var responseCode = 0
        var responseText = ""
        CullinanHttpClient.executeCall(request).use { response ->
            isSuccessful = response.isSuccessful
            responseCode = response.code
            if (isSuccessful) {
                responseText = response.body?.string() ?: ""
            }
        }

        if (!isSuccessful) {
            AppLogger.logError("MessageRepository", "Pagination POST failed HTTP $responseCode", flowId = flowId)
            return Result.failure(MessageError.ServerError(responseCode, "خطا در دریافت صفحه بعدی پیام‌ها ($responseCode)"))
        }

        val isFullHtml = (responseText.contains("<!DOCTYPE", ignoreCase = true) || responseText.contains("<html", ignoreCase = true)) &&
            !responseText.contains("|updatePanel|") && !responseText.contains("|hiddenField|")

        val requestedPageNum = PAGE_ARG_NUMBER_REGEX.find(eventArgument)?.groupValues?.get(1)?.toIntOrNull()
            ?: (baseState.currentServerPage + 1)

        if (isFullHtml) {
            if (responseText.contains("Login.aspx", ignoreCase = true) || responseText.contains("txtUsernamePlain")) {
                return Result.failure(MessageError.SessionExpired)
            }
            return Result.success(parseFullMessagesDocument(responseText, flowId, fallbackPageNumber = requestedPageNum))
        }

        val deltaResponse = DeltaParser.parse(responseText, flowId = flowId)
        if (deltaResponse.isSessionExpired) {
            return Result.failure(MessageError.SessionExpired)
        }
        if (!deltaResponse.errorMessage.isNullOrBlank()) {
            return Result.failure(MessageError.ServerError(responseCode, deltaResponse.errorMessage))
        }

        val panelHtml = deltaResponse.updatePanels["UpdatePanel1"]
            ?: deltaResponse.updatePanels["ctl00\$UpdatePanel1"]
            ?: deltaResponse.updatePanels[baseState.updatePanelName]
            ?: deltaResponse.updatePanels.values.firstOrNull { it.contains("cphMain_grdContent") }
            ?: return Result.failure(MessageError.MissingUpdatePanel)

        val doc = Jsoup.parse(panelHtml)
        val pager = parsePagerMetadata(doc, fallbackPage = requestedPageNum)
        val messages = parseMessageRows(doc, pageNumber = pager.currentPage)
        val updatedWebForms = DeltaParser.extractWebFormsState(deltaResponse, state)
        val unreadCount = messages.count { !it.isRead }

        return Result.success(
            MessagesPageState(
                viewState = updatedWebForms.viewState,
                viewStateGenerator = updatedWebForms.viewStateGenerator,
                eventValidation = updatedWebForms.eventValidation ?: "",
                scriptManagerName = baseState.scriptManagerName,
                updatePanelName = baseState.updatePanelName,
                messages = messages,
                hasUnreadMessages = unreadCount > 0,
                unreadCount = unreadCount,
                hasPaginationControls = pager.hasPager,
                isReservationUnlocked = baseState.isReservationUnlocked || panelHtml.contains("Reservation/Reservation.aspx", ignoreCase = true),
                isLoading = false,
                currentServerPage = pager.currentPage,
                loadedPagesCount = 1,
                totalKnownPages = pager.totalKnownPages,
                nextPageEventTarget = pager.nextPageEventTarget,
                nextPageEventArgument = pager.nextPageEventArgument
            )
        )
    }

    private fun parseFullMessagesDocument(
        html: String,
        flowId: String,
        fallbackPageNumber: Int = 1
    ): MessagesPageState {
        val doc = Jsoup.parse(html)

        val viewState = doc.select("input[name=__VIEWSTATE]").`val`()
        val viewStateGenerator = doc.select("input[name=__VIEWSTATEGENERATOR]").`val`().ifEmpty { "87AF9C2D" }
        val eventValidation = doc.select("input[name=__EVENTVALIDATION]").`val`()

        val scriptManagerInput = doc.select("input[name*='ScriptManager']").firstOrNull { input ->
            val name = input.attr("name")
            name.isNotBlank() &&
                !name.endsWith("_HiddenField", ignoreCase = true) &&
                !name.endsWith("_TSM", ignoreCase = true) &&
                !name.contains("HiddenField", ignoreCase = true)
        }
        val scriptManagerName = when (val rawSmName = scriptManagerInput?.attr("name")?.takeIf { it.isNotBlank() }) {
            null -> {
                val hiddenSm = doc.select("input[name*='ScriptManager']").firstOrNull()?.attr("name") ?: ""
                if (hiddenSm.contains("ctl00") && hiddenSm.endsWith("_HiddenField", ignoreCase = true)) {
                    hiddenSm.removeSuffix("_HiddenField").replace('_', '$')
                } else {
                    "ctl00\$ScriptManager"
                }
            }
            else -> rawSmName
        }

        val updatePanelDiv = doc.select("div[id$='UpdatePanel1'], div[id*='UpdatePanel']").firstOrNull { div ->
            val id = div.attr("id")
            id.isNotBlank() && !id.contains("UpdateProgress", ignoreCase = true)
        }
        val updatePanelHtmlId = updatePanelDiv?.attr("id")
        val updatePanelName = when {
            updatePanelHtmlId != null && updatePanelHtmlId.contains("ctl00") -> updatePanelHtmlId.replace('_', '$')
            updatePanelHtmlId != null && updatePanelHtmlId.isNotEmpty() -> "ctl00\$$updatePanelHtmlId"
            else -> "ctl00\$UpdatePanel1"
        }

        val isReservationUnlocked = html.contains("Reservation/Reservation.aspx", ignoreCase = true)
        val pager = parsePagerMetadata(doc, fallbackPage = fallbackPageNumber)
        val messages = parseMessageRows(doc, pageNumber = pager.currentPage)
        val unreadCount = messages.count { !it.isRead }

        if (viewState.isBlank() || (messages.isEmpty() && doc.select("#cphMain_grdContent").isEmpty())) {
            AppLogger.logWarn(
                component = "MessageRepository",
                operation = "FetchMessagesAnomalousDom",
                message = "Anomalous MyMessage DOM (VS len=${viewState.length}, grdPresent=${doc.select("#cphMain_grdContent").isNotEmpty()}, parsed=${messages.size})",
                flowId = flowId,
                errorCategory = "MESSAGES_DOM_ANOMALY"
            )
        }

        return MessagesPageState(
            viewState = viewState,
            viewStateGenerator = viewStateGenerator,
            eventValidation = eventValidation,
            scriptManagerName = scriptManagerName,
            updatePanelName = updatePanelName,
            messages = messages,
            hasUnreadMessages = unreadCount > 0,
            unreadCount = unreadCount,
            hasPaginationControls = pager.hasPager,
            isReservationUnlocked = isReservationUnlocked,
            isLoading = false,
            currentServerPage = pager.currentPage,
            loadedPagesCount = 1,
            totalKnownPages = pager.totalKnownPages,
            nextPageEventTarget = pager.nextPageEventTarget,
            nextPageEventArgument = pager.nextPageEventArgument
        )
    }

    private fun parseMessageRows(
        doc: org.jsoup.nodes.Document,
        pageNumber: Int
    ): List<MessageItem> {
        val rows = doc.select("#cphMain_grdContent tr").filter {
            it.select("input[name*='imgbtnMessageText']").isNotEmpty()
        }

        val messages = mutableListOf<MessageItem>()
        for (row in rows) {
            val cells = row.select("td")
            if (cells.size < 3) continue

            val rowIdxStr = cells[0].text().trim()
            val rowIdx = rowIdxStr.toIntOrNull() ?: ((pageNumber - 1) * 5 + messages.size + 1)
            val title = cells[1].text().trim()

            val imgBtn = row.select("input[type=image][name*='imgbtnMessageText']").firstOrNull()
            val buttonName = imgBtn?.attr("name") ?: ""

            val sendDate = if (cells.size > 3) cells[3].text().trim() else ""
            val sendTime = if (cells.size > 4) cells[4].text().trim() else ""

            val hasMsgClose = row.select("img[src*='MsgClose']").isNotEmpty()
            val hasMsgOpen = row.select("img[src*='MsgOpen']").isNotEmpty()

            val readDate = if (cells.size > 6) cells[6].text().trim() else ""
            val readTime = if (cells.size > 7) cells[7].text().trim() else ""

            val isRead = hasMsgOpen || (!hasMsgClose && readDate.isNotBlank() && readDate != "-")

            messages.add(
                MessageItem(
                    rowIndex = rowIdx,
                    title = title,
                    buttonInputName = buttonName,
                    sendDate = sendDate,
                    sendTime = sendTime,
                    isRead = isRead,
                    readDate = readDate,
                    readTime = readTime,
                    messageText = null,
                    pageNumber = pageNumber
                )
            )
        }
        return messages
    }

    private fun parsePagerMetadata(
        doc: org.jsoup.nodes.Document,
        fallbackPage: Int = 1
    ): PagerMetadata {
        val pagerRow = doc.select("#cphMain_grdContent tr.PagerStyle, .PagerStyle, tr[class*='Pager']").firstOrNull()
            ?: return PagerMetadata(
                hasPager = false,
                currentPage = fallbackPage,
                totalKnownPages = fallbackPage,
                nextPageEventTarget = null,
                nextPageEventArgument = null
            )

        val currentPage = pagerRow.select("span").firstOrNull()?.text()?.trim()?.toIntOrNull() ?: fallbackPage
        val candidates = mutableListOf<PagerLinkCandidate>()
        var passedCurrentSpan = false
        val pageMap = mutableMapOf<Int, Pair<String, String>>()

        val nestedCells = pagerRow.select("table td")
        val elementsToScan = if (nestedCells.isNotEmpty()) nestedCells else pagerRow.select("td, span, a")

        for (cell in elementsToScan) {
            if (cell.select("span").isNotEmpty() && cell.select("a").isEmpty()) {
                val spanNum = cell.text().trim().toIntOrNull()
                if (spanNum == currentPage || cell.tagName() == "span") {
                    passedCurrentSpan = true
                }
                continue
            }
            val anchors = if (cell.tagName() == "a") listOf(cell) else cell.select("a")
            for (anchor in anchors) {
                val href = anchor.attr("href")
                val match = DO_POSTBACK_REGEX.find(href) ?: continue
                val target = match.groupValues[1].trim()
                val arg = match.groupValues[2].trim()
                val linkText = anchor.text().trim()
                val pageNum = PAGE_ARG_NUMBER_REGEX.find(arg)?.groupValues?.get(1)?.toIntOrNull()
                    ?: linkText.toIntOrNull()

                if (pageNum != null) {
                    pageMap[pageNum] = target to arg
                }

                candidates.add(
                    PagerLinkCandidate(
                        eventTarget = target,
                        eventArgument = arg,
                        linkText = linkText,
                        pageNum = pageNum,
                        isAfterCurrent = passedCurrentSpan
                    )
                )
            }
        }

        val maxNumericPage = maxOf(
            currentPage,
            candidates.mapNotNull { it.pageNum }.maxOrNull() ?: currentPage
        )

        val nextCandidate = candidates.firstOrNull { it.pageNum == currentPage + 1 }
            ?: candidates.filter { it.pageNum != null && it.pageNum > currentPage }.minByOrNull { it.pageNum!! }
            ?: candidates.firstOrNull { it.isAfterCurrent && it.eventArgument.startsWith("Page$", ignoreCase = true) }

        return PagerMetadata(
            hasPager = true,
            currentPage = currentPage,
            totalKnownPages = maxNumericPage,
            nextPageEventTarget = nextCandidate?.eventTarget,
            nextPageEventArgument = nextCandidate?.eventArgument,
            pageArgumentsMap = pageMap,
            defaultEventTarget = candidates.firstOrNull()?.eventTarget ?: GRID_CONTROL_EVENT_TARGET
        )
    }

    private fun mergeMessagesPreservingOrder(
        existing: List<MessageItem>,
        incoming: List<MessageItem>
    ): List<MessageItem> {
        if (incoming.isEmpty()) return existing
        val result = existing.toMutableList()
        for (item in incoming) {
            if (result.none { isSameMessage(it, item) }) {
                result.add(item)
            }
        }
        return result
    }

    private fun isSameMessage(a: MessageItem, b: MessageItem): Boolean {
        if (a.pageNumber != b.pageNumber) {
            return a.rowIndex == b.rowIndex &&
                a.title == b.title &&
                a.sendDate == b.sendDate &&
                a.sendTime == b.sendTime
        }
        return a.rowIndex == b.rowIndex && a.title == b.title && a.sendDate == b.sendDate
    }

    private companion object {
        const val GRID_CONTROL_EVENT_TARGET = "ctl00\$cphMain\$grdContent"
        val DO_POSTBACK_REGEX = Regex("""__doPostBack\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*\)""")
        val PAGE_ARG_NUMBER_REGEX = Regex("""Page\$(\d+)""", RegexOption.IGNORE_CASE)
    }
}
