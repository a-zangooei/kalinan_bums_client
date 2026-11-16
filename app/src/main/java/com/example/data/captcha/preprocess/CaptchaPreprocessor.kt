package com.example.data.captcha.preprocess

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * مرز وابسته به اندروید برای رمزگشایی ایمن بایت‌های خام کپچا (GIF/PNG/JPEG) و تبدیل نتیجه پیش‌پردازش به [Bitmap].
 *
 * این کلاس هیچ‌گونه وابستگی به کتابخانه‌های OCR یا رابط کاربری ندارد و هیچ لاگی از محتوای تصویر ثبت نمی‌کند.
 * تمامی Bitmapهای میانی بلافاصله پس از استخراج آرایه روشنایی در بلوک `finally` آزاد (`recycle`) می‌شوند.
 */
object CaptchaPreprocessor {

    /**
     * بایت‌های تصویر کپچا را رمزگشایی و بر اساس [config] پیش‌پردازش کرده و یک [Bitmap] با فرمت `ARGB_8888`
     * برمی‌گرداند.
     *
     * در صورت خالی بودن ورودی، خرابی فایل تصویر، ابعاد کمتر از [PreprocessConfig.minDimensionPx] یا
     * بیشتر از [PreprocessConfig.maxDimensionPx] و همچنین هنگام خطای حافظه، بدون کرش `null` بازمی‌گرداند.
     */
    @JvmOverloads
    fun preprocess(
        bytes: ByteArray,
        config: PreprocessConfig = PreprocessConfig()
    ): Bitmap? {
        if (bytes.isEmpty() || !config.isValid()) return null

        return try {
            val grayscale = decodeBytesToGrayscale(bytes, config) ?: return null
            val processed = CaptchaPreprocessPipeline.process(grayscale, config) ?: return null
            encodeGrayscaleToBitmap(processed)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * بایت‌های ورودی را ابتدا بدون تخصیص بافر (`inJustDecodeBounds`) اعتبارسنجی ابعاد می‌کند،
     * سپس فریم تصویر را به `ARGB_8888` رمزگشایی و به [GrayscaleImage] تبدیل کرده و Bitmap موقت را آزاد می‌نماید.
     */
    internal fun decodeBytesToGrayscale(
        bytes: ByteArray,
        config: PreprocessConfig
    ): GrayscaleImage? {
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        if (rawW < config.minDimensionPx || rawH < config.minDimensionPx) return null
        if (rawW > config.maxDimensionPx || rawH > config.maxDimensionPx) return null

        val decodeOpts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
            inScaled = false
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        var argbBitmap: Bitmap? = null

        return try {
            val width = decoded.width
            val height = decoded.height
            if (width < config.minDimensionPx || height < config.minDimensionPx) return null
            if (width > config.maxDimensionPx || height > config.maxDimensionPx) return null

            val readableBitmap = if (decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                decoded.copy(Bitmap.Config.ARGB_8888, false)?.also { argbBitmap = it } ?: return null
            }

            val argbPixels = IntArray(width * height)
            readableBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
            CaptchaPreprocessPipeline.toGrayscaleFromArgb(width, height, argbPixels)
        } finally {
            argbBitmap?.let { if (!it.isRecycled) it.recycle() }
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun encodeGrayscaleToBitmap(image: GrayscaleImage): Bitmap? {
        val total = image.pixels.size
        val argbOut = IntArray(total)
        val src = image.pixels
        for (i in 0 until total) {
            val v = src[i].coerceIn(0, 255)
            argbOut[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        var outputBitmap: Bitmap? = null
        return try {
            outputBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            outputBitmap.setPixels(argbOut, 0, image.width, 0, 0, image.width, image.height)
            outputBitmap
        } catch (t: Throwable) {
            outputBitmap?.let { if (!it.isRecycled) it.recycle() }
            null
        }
    }
}
