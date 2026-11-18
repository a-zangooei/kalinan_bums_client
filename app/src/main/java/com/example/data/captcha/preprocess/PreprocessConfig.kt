package com.example.data.captcha.preprocess

/**
 * روش محاسبه آستانه برای جداسازی ارقام روشن کپچا از پس‌زمینه تیره.
 */
enum class ThresholdMode {
    /** محاسبه خودکار و تطبیقی آستانه بهینه با روش اوتسو (Otsu) بر اساس هیستوگرام تصویر. */
    OTSU,

    /** اعمال آستانه عددی ثابت [PreprocessConfig.fixedThreshold] در بازه `0..255`. */
    FIXED,

    /** بدون باینری‌سازی؛ حفظ مقادیر پیوسته خاکستری برای سازگاری بهتر با مدل‌های عصبی OCR. */
    NONE
}

/**
 * روش حذف نویز نقطه‌ای و بافت ریز پس‌زمینه پس از آستانه‌گذاری.
 */
enum class NoiseRemovalMode {
    /** فیلتر میانه متقارن (پیش‌فرض؛ نقاط مزاحم منفرد را بدون شکستن خطوط مورب ارقام حذف می‌کند). */
    MEDIAN,

    /** بازسازی مورفولوژیک (فرسایش سپس بسط؛ مناسب حذف لکه‌ها و خطوط ریز جدا از بدنه ارقام). */
    MORPHOLOGICAL_OPEN,

    /** بسته‌سازی مورفولوژیک (بسط سپس فرسایش؛ مناسب پر کردن حفره‌های کوچک درون ارقام). */
    MORPHOLOGICAL_CLOSE,

    /** بدون فیلتر حذف نویز. */
    NONE
}

/**
 * پیکربندی مراحل پیش‌پردازش تصویر کپچا.
 *
 * مقادیر پیش‌فرض سازنده برای سازگاری با خط لوله باینری کلاسیک حفظ شده‌اند و تنظیمات بهینه تولیدی
 * در `CaptchaSolverConfig` تعریف می‌شوند:
 * - [useDecodedColorFrame]: در صورت `true`، فریم رنگی `ARGB_8888` رمزگشایی‌شده بدون تبدیل خاکستری یا
 *   باینری‌سازی به موتور OCR تحویل داده می‌شود.
 * - [contrastClipPercent]: برش درصدی هیستوگرام برای کشش کنتراست (مقدار `0f` یعنی بدون تغییر کنتراست).
 * - [thresholdMode] = [ThresholdMode.NONE]: عبور مقادیر خاکستری پیوسته بدون باینری‌سازی مخرب.
 * - [noiseRemovalMode] = [NoiseRemovalMode.NONE]: حذف‌نکردن پیکسل‌های لبه تا خطوط نازک ارقام سالم بمانند.
 */
data class PreprocessConfig(
    val contrastClipPercent: Float = 0.02f,
    val thresholdMode: ThresholdMode = ThresholdMode.OTSU,
    val fixedThreshold: Int = 140,
    val noiseRemovalMode: NoiseRemovalMode = NoiseRemovalMode.MEDIAN,
    val filterSize: Int = 3,
    val invertOutput: Boolean = true,
    val scaleFactor: Int = 3,
    val borderPaddingPx: Int = 16,
    val minDimensionPx: Int = 8,
    val maxDimensionPx: Int = 2048,
    val useDecodedColorFrame: Boolean = false
) {
    fun isValid(): Boolean {
        return !contrastClipPercent.isNaN() &&
            contrastClipPercent in 0f..0.45f &&
            fixedThreshold in 0..255 &&
            filterSize >= 1 &&
            filterSize % 2 == 1 &&
            filterSize <= 9 &&
            scaleFactor in 1..8 &&
            borderPaddingPx in 0..256 &&
            minDimensionPx >= 1 &&
            maxDimensionPx >= minDimensionPx
    }
}
