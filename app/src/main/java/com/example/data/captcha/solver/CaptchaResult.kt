package com.example.data.captcha.solver

/**
 * مراحل اجرای موتور تشخیص خودکار کپچا برای ردیابی فنی خطا در لاگ توسعه‌دهنده.
 */
enum class OcrSolveStage {
    INPUT_VALIDATION,
    SESSION_INIT,
    PREPROCESS,
    RECOGNIZE,
    VALIDATE,
    TIMEOUT,
    SUCCESS
}

/**
 * گزارش فنی عیب‌یابی OCR بدون ذخیره هیچ‌گونه رقم، متن خام یا بایت تصویر.
 */
data class OcrDiagnosticReport(
    val stage: OcrSolveStage,
    val attemptCount: Int,
    val elapsedMs: Long,
    val exceptionType: String? = null,
    val mlKitErrorCode: Int? = null,
    val causeChain: String? = null,
    val lastRejectionCode: String? = null,
    val elementCount: Int? = null,
    val extractedDigitCount: Int? = null
)

/**
 * نتیجه حل کپچا به همراه گزارش فنی عیب‌یابی برای لاگ توسعه‌دهنده.
 */
data class CaptchaSolveOutcome(
    val result: CaptchaResult,
    val diagnostics: OcrDiagnosticReport
)

/**
 * نتیجه حل خودکار تصویر کپچا.
 */
sealed class CaptchaResult {
    /** تشخیص موفق و معتبر دقیقاً ۴ رقم لاتین. */
    data class Success(val digits: String) : CaptchaResult() {
        /**
         * برای جلوگیری از ثبت تصادفی ارقام کپچا در لاگ‌ها، متن ارقام پنهان می‌شود.
         */
        override fun toString(): String = "CaptchaResult.Success([REDACTED])"
    }

    /** خروجی نامطمئن (تعداد ارقام غیر از ۴، امتیاز اطمینان پایین یا تعارض بین تلاش‌ها). */
    data class NotConfident(val reason: String) : CaptchaResult()

    /** خطا در ورودی، پیش‌پردازش، موتور تشخیص متن یا اتمام زمان مجاز (Timeout). */
    data class Error(val message: String) : CaptchaResult()
}

