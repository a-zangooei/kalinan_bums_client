package com.example.data.captcha.solver

/**
 * واسط عمومی حل خودکار کپچا بر پایه بایت‌های تصویر دریافت‌شده از سامانه.
 */
interface CaptchaSolver {
    suspend fun solve(imageBytes: ByteArray): CaptchaResult

    /**
     * اجرای تشخیص به همراه دریافت گزارش فنی عیب‌یابی برای لاگ توسعه‌دهنده (بدون ارقام یا متن OCR).
     */
    suspend fun solveWithDiagnostics(imageBytes: ByteArray): CaptchaSolveOutcome {
        val startNs = System.nanoTime()
        val result = solve(imageBytes)
        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
        val stage = when (result) {
            is CaptchaResult.Success -> OcrSolveStage.SUCCESS
            is CaptchaResult.NotConfident -> OcrSolveStage.VALIDATE
            is CaptchaResult.Error -> OcrSolveStage.RECOGNIZE
        }
        return CaptchaSolveOutcome(
            result = result,
            diagnostics = OcrDiagnosticReport(
                stage = stage,
                attemptCount = if (imageBytes.isEmpty()) 0 else 1,
                elapsedMs = elapsedMs,
                causeChain = (result as? CaptchaResult.Error)?.message
            )
        )
    }
}

