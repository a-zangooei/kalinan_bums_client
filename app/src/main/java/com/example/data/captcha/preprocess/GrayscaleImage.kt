package com.example.data.captcha.preprocess

/**
 * مدل تصویر خاکستری خالص مستقل از چارچوب اندروید برای اجرای الگوریتم‌های پیش‌پردازش
 * و تست واحد روی JVM بدون نیاز به Robolectric.
 *
 * هر خانه در [pixels] شدت روشنایی یک پیکسل را در بازه `0..255` (چیدمان سطری row-major)
 * نگهداری می‌کند؛ به‌طوری‌که `0` معادل سیاه کامل و `255` معادل سفید کامل است.
 */
data class GrayscaleImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    init {
        require(width > 0) { "Image width must be positive" }
        require(height > 0) { "Image height must be positive" }
        val expectedSize = width.toLong() * height.toLong()
        require(expectedSize <= Int.MAX_VALUE && pixels.size == expectedSize.toInt()) {
            "Pixel buffer size does not match width * height"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GrayscaleImage) return false
        return width == other.width &&
            height == other.height &&
            pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }

    /**
     * از چاپ آرایه پیکسل‌ها جلوگیری می‌کند تا هیچ داده‌ای از تصویر در خروجی‌های متنی ثبت نشود.
     */
    override fun toString(): String = "GrayscaleImage(${width}x${height})"
}
