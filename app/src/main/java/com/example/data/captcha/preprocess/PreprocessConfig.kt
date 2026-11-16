package com.example.data.captcha.preprocess

/**
 * روش محاسبه آستانه برای جداسازی ارقام روشن کپچا از پس‌زمینه تیره.
 */
enum class ThresholdMode {
    /** محاسبه خودکار و تطبیقی آستانه بهینه با روش اوتسو (Otsu) بر اساس هیستوگرام تصویر. */
    OTSU,

    /** اعمال آستانه عددی ثابت [PreprocessConfig.fixedThreshold] در بازه `0..255`. */
    FIXED
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
 * **دلیل مقادیر پیش‌فرض پیشنهادی:**
 * - [contrastClipPercent] = `0.02f`: برش ۲ درصدی در دو انتهای هیستوگرام مانع از آن می‌شود که چند پیکسل
 *   بسیار روشن یا بسیار تاریکِ نویزی، دامنه کشش کنتراست را خراب کنند و تفکیک ارقام روشن از زمینه خاکستری
 *   را بهبود می‌دهد.
 * - [thresholdMode] = [ThresholdMode.OTSU] و [fixedThreshold] = `140`: روش اوتسو به‌طور خودکار با تغییر
 *   شدت نور و بافت پس‌زمینه بین کپچاهای مختلف سازگار می‌شود؛ مقدار `140` نیز به‌عنوان آستانه جایگزین دستی
 *   برای ارقام سفید/روشن روی پس‌زمینه خاکستری میانه در نظر گرفته شده است.
 * - [noiseRemovalMode] = [NoiseRemovalMode.MEDIAN] و [filterSize] = `3`: پنجره ۳×۳ نقاط پراکنده و بافت
 *   نازک پس‌زمینه را پاک می‌کند بی‌آنکه ضخامت اصلی ارقام (معمولاً ۳ تا ۶ پیکسل) را از بین ببرد.
 * - [invertOutput] = `true`: اکثر موتورهای OCR روی متن تیره (`0`) با پس‌زمینه کاملاً سفید (`255`) بیشترین دقت را دارند.
 * - [scaleFactor] = `3`: بزرگ‌نمایی ۳ برابری با الگوریتم Nearest-Neighbor لبه‌های باینری را تیز نگه می‌دارد
 *   و ارتفاع کاراکترهای کپچای کوچک (~۲۵ تا ۴۰ پیکسل) را بدون مصرف حافظه بیش‌ازحد به محدوده بهینه OCR می‌رساند.
 * - [borderPaddingPx] = `16`: حاشیه سفید یکنواخت از برخورد کاراکترهای اول و آخر به لبه کادر و بریده‌شدن توسط بخش‌بندی OCR جلوگیری می‌کند.
 * - [minDimensionPx] = `8` و [maxDimensionPx] = `2048`: ورودی‌های خیلی کوچکِ خراب را رد کرده و از تخصیص حافظه غیرعادی (`OutOfMemoryError`) پیشگیری می‌کند.
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
    val maxDimensionPx: Int = 2048
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
