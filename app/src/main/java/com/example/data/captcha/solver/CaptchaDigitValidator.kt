package com.example.data.captcha.solver

/**
 * مدل یک جزء متنی تشخیص‌داده‌شده توسط موتور OCR به همراه امتیاز اطمینان اختیاری.
 */
data class OcrElement(
    val text: String,
    val confidence: Float? = null
)

/**
 * خروجی انتزاعی موتور OCR مستقل از SDK واقعی ML Kit برای امکان تست واحد خالص روی JVM.
 */
data class OcrOutput(
    val fullText: String,
    val elements: List<OcrElement> = emptyList()
)

internal sealed class DigitValidationOutcome {
    data class Valid(val digits: String) : DigitValidationOutcome()
    data class Invalid(val reason: String) : DigitValidationOutcome()
}

/**
 * اعتبارسنجی خالص خروجی OCR برای اطمینان از وجود دقیقاً ۴ رقم لاتین معتبر و امتیاز اطمینان کافی.
 */
internal object CaptchaDigitValidator {

    const val REQUIRED_DIGIT_COUNT = 4
    const val DEFAULT_MIN_ELEMENT_CONFIDENCE = 0.65f

    /**
     * تبدیل ارقام فارسی (`۰..۹`) و عربی (`٠..٩`) به ارقام لاتین (`0..9`)
     * و نگه‌داشتن سایر نویسه‌ها بدون تغییر.
     */
    fun normalizeDigitsToLatin(raw: String): String {
        if (raw.isEmpty()) return raw
        return buildString(raw.length) {
            for (ch in raw) {
                when (ch) {
                    in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
                    in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
                    else -> append(ch)
                }
            }
        }
    }

    /**
     * استخراج صرفاً ارقام لاتین از متن خام پس از تبدیل ارقام فارسی/عربی و حذف فاصله و نویسه‌های غیررقم.
     */
    fun extractNormalizedDigits(raw: String): String {
        val latinized = normalizeDigitsToLatin(raw)
        return buildString(latinized.length) {
            for (ch in latinized) {
                if (ch in '0'..'9') {
                    append(ch)
                }
            }
        }
    }

    /**
     * اعتبارسنجی خروجی یک تلاش OCR بر اساس قاعده ۴ رقم و امتیاز اطمینان عنصری (در صورت موجود بودن).
     */
    fun validate(
        output: OcrOutput,
        minElementConfidence: Float = DEFAULT_MIN_ELEMENT_CONFIDENCE
    ): DigitValidationOutcome {
        val rawCombined = output.fullText.ifBlank {
            output.elements.joinToString(" ") { it.text }
        }
        val digits = extractNormalizedDigits(rawCombined)
        if (digits.length != REQUIRED_DIGIT_COUNT) {
            return DigitValidationOutcome.Invalid(
                "تعداد ارقام تشخیص‌داده‌شده با طول مورد انتظار ($REQUIRED_DIGIT_COUNT) هم‌خوانی ندارد"
            )
        }

        // بررسی امتیاز اطمینان در سطح عنصر برای عناصری که حاوی رقم هستند
        for (element in output.elements) {
            val elemDigits = extractNormalizedDigits(element.text)
            if (elemDigits.isNotEmpty()) {
                val conf = element.confidence
                if (conf != null && !conf.isNaN() && conf < minElementConfidence) {
                    return DigitValidationOutcome.Invalid(
                        "امتیاز اطمینان یکی از اجزای رقمی کمتر از حد مجاز است"
                    )
                }
            }
        }

        return DigitValidationOutcome.Valid(digits)
    }
}
