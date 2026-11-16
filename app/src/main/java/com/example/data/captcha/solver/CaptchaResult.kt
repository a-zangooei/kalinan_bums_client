package com.example.data.captcha.solver

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
