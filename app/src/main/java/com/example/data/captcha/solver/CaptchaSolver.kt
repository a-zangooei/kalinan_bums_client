package com.example.data.captcha.solver

/**
 * واسط عمومی حل خودکار کپچا بر پایه بایت‌های تصویر دریافت‌شده از سامانه.
 */
interface CaptchaSolver {
    suspend fun solve(imageBytes: ByteArray): CaptchaResult
}
