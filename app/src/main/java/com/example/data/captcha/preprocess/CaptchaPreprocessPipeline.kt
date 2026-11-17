package com.example.data.captcha.preprocess

import kotlin.math.roundToInt

/**
 * توابع خالص پیش‌پردازش تصویر کپچا روی [GrayscaleImage] (بدون هرگونه وابستگی به Android SDK و بدون لاگ).
 *
 * ترتیب مراحل در [process]:
 * 1. افزایش کنتراست ([stretchContrast])
 * 2. آستانه‌گذاری برای جداسازی ارقام روشن از زمینه تیره ([applyThreshold])
 * 3. حذف نقاط مزاحم و بافت ریز ([removeNoise])
 * 4. وارونه‌سازی به متن تیره روی زمینه روشن ([invert])
 * 5. بزرگ‌نمایی لبه تیز ([upscaleNearestNeighbor]) و افزودن حاشیه سفید ([addWhiteBorder])
 */
object CaptchaPreprocessPipeline {

    private const val LUMA_MAX = 255
    private const val HISTOGRAM_BINS = 256

    /**
     * آرایه پیکسل‌های ARGB را با وزن‌دهی استاندارد ادراکی BT.601 به تصویر خاکستری تبدیل می‌کند.
     */
    fun toGrayscaleFromArgb(
        width: Int,
        height: Int,
        argbPixels: IntArray
    ): GrayscaleImage {
        require(width > 0 && height > 0) { "Dimensions must be positive" }
        val totalLong = width.toLong() * height.toLong()
        require(totalLong <= Int.MAX_VALUE && argbPixels.size == totalLong.toInt()) {
            "ARGB buffer size does not match width * height"
        }

        val out = IntArray(argbPixels.size)
        for (i in argbPixels.indices) {
            val argb = argbPixels[i]
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            // 0.301*R + 0.586*G + 0.113*B approx via integer bit-shift (77 + 150 + 29 = 256)
            out[i] = ((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, LUMA_MAX)
        }
        return GrayscaleImage(width = width, height = height, pixels = out)
    }

    /**
     * اجرای کامل خط لوله پیش‌پردازش روی تصویر خاکستری و بازگرداندن `null` در صورت نامعتبر بودن ورودی یا پیکربندی.
     */
    fun process(
        image: GrayscaleImage,
        config: PreprocessConfig = PreprocessConfig()
    ): GrayscaleImage? {
        if (!config.isValid()) return null
        if (image.width < config.minDimensionPx || image.height < config.minDimensionPx) return null
        if (image.width > config.maxDimensionPx || image.height > config.maxDimensionPx) return null

        val contrasted = stretchContrast(image, config.contrastClipPercent)
        val thresholded = applyThreshold(
            image = contrasted,
            mode = config.thresholdMode,
            fixedThreshold = config.fixedThreshold
        )
        val denoised = removeNoise(
            image = thresholded,
            mode = config.noiseRemovalMode,
            kernelSize = config.filterSize
        )
        val polarized = if (config.invertOutput) invert(denoised) else denoised
        val upscaled = upscaleNearestNeighbor(polarized, config.scaleFactor) ?: return null
        return addWhiteBorder(upscaled, config.borderPaddingPx)
    }

    /**
     * نتیجه کامل اجرای مرحله‌به‌مرحله خط لوله پیش‌پردازش برای استفاده اختصاصی آزمایشگاه کپچا.
     */
    internal data class PipelineStageTrace(
        val grayscale: GrayscaleImage,
        val contrastEnhanced: GrayscaleImage? = null,
        val thresholded: GrayscaleImage? = null,
        val denoised: GrayscaleImage? = null,
        val inverted: GrayscaleImage? = null,
        val invertSkipped: Boolean = false,
        val upscaled: GrayscaleImage? = null,
        val finalPadded: GrayscaleImage? = null,
        val failedStageName: String? = null,
        val failureReason: String? = null
    )

    /**
     * اجرای دقیقاً مشابه [process] با نگهداری تصویر هر مرحله برای بایگانی آزمایشگاه کپچا.
     */
    internal fun processWithStages(
        image: GrayscaleImage,
        config: PreprocessConfig = PreprocessConfig()
    ): PipelineStageTrace {
        if (!config.isValid()) {
            return PipelineStageTrace(
                grayscale = image,
                failedStageName = "CONFIG_VALIDATION",
                failureReason = "Invalid PreprocessConfig parameters"
            )
        }
        if (image.width < config.minDimensionPx || image.height < config.minDimensionPx) {
            return PipelineStageTrace(
                grayscale = image,
                failedStageName = "DIMENSION_VALIDATION",
                failureReason = "Image dimensions (${image.width}x${image.height}) below minDimensionPx=${config.minDimensionPx}"
            )
        }
        if (image.width > config.maxDimensionPx || image.height > config.maxDimensionPx) {
            return PipelineStageTrace(
                grayscale = image,
                failedStageName = "DIMENSION_VALIDATION",
                failureReason = "Image dimensions (${image.width}x${image.height}) exceed maxDimensionPx=${config.maxDimensionPx}"
            )
        }

        val contrasted = stretchContrast(image, config.contrastClipPercent)
        val thresholded = applyThreshold(
            image = contrasted,
            mode = config.thresholdMode,
            fixedThreshold = config.fixedThreshold
        )
        val denoised = removeNoise(
            image = thresholded,
            mode = config.noiseRemovalMode,
            kernelSize = config.filterSize
        )
        val invertSkipped = !config.invertOutput
        val invertedOrNull = if (config.invertOutput) invert(denoised) else null
        val polarized = invertedOrNull ?: denoised
        val upscaled = upscaleNearestNeighbor(polarized, config.scaleFactor)
            ?: return PipelineStageTrace(
                grayscale = image,
                contrastEnhanced = contrasted,
                thresholded = thresholded,
                denoised = denoised,
                inverted = invertedOrNull,
                invertSkipped = invertSkipped,
                failedStageName = "UPSCALED",
                failureReason = "Upscale failed for scaleFactor=${config.scaleFactor} on ${polarized.width}x${polarized.height}"
            )
        val finalPadded = addWhiteBorder(upscaled, config.borderPaddingPx)
        return PipelineStageTrace(
            grayscale = image,
            contrastEnhanced = contrasted,
            thresholded = thresholded,
            denoised = denoised,
            inverted = invertedOrNull,
            invertSkipped = invertSkipped,
            upscaled = upscaled,
            finalPadded = finalPadded
        )
    }

    /**
     * کشش خطی کنتراست با برش درصدی ملایم در دو انتهای هیستوگرام برای پایداری در برابر پیکسل‌های تک‌افتاده نویزی.
     */
    fun stretchContrast(
        image: GrayscaleImage,
        clipPercent: Float = 0.02f
    ): GrayscaleImage {
        val pixels = image.pixels
        val total = pixels.size
        val histogram = IntArray(HISTOGRAM_BINS)
        var rawMin = LUMA_MAX
        var rawMax = 0

        for (p in pixels) {
            val clamped = p.coerceIn(0, LUMA_MAX)
            histogram[clamped]++
            if (clamped < rawMin) rawMin = clamped
            if (clamped > rawMax) rawMax = clamped
        }

        val safeClip = clipPercent.coerceIn(0f, 0.45f)
        var low = rawMin
        var high = rawMax

        if (safeClip > 0f && total > 10) {
            val clipCount = (total * safeClip).roundToInt()
            var accLow = 0
            for (v in 0..LUMA_MAX) {
                accLow += histogram[v]
                if (accLow > clipCount) {
                    low = v
                    break
                }
            }
            var accHigh = 0
            for (v in LUMA_MAX downTo 0) {
                accHigh += histogram[v]
                if (accHigh > clipCount) {
                    high = v
                    break
                }
            }
            if (low >= high) {
                low = rawMin
                high = rawMax
            }
        }

        val range = high - low
        if (range <= 0) {
            return GrayscaleImage(
                width = image.width,
                height = image.height,
                pixels = pixels.copyOf()
            )
        }

        val out = IntArray(total)
        val denom = range.toFloat()
        for (i in pixels.indices) {
            val v = pixels[i].coerceIn(0, LUMA_MAX)
            val stretched = when {
                v <= low -> 0
                v >= high -> LUMA_MAX
                else -> (((v - low) * LUMA_MAX) / denom).roundToInt().coerceIn(0, LUMA_MAX)
            }
            out[i] = stretched
        }
        return GrayscaleImage(width = image.width, height = image.height, pixels = out)
    }

    /**
     * محاسبه آستانه بهینه بین‌کلاسی اوتسو (Otsu) روی هیستوگرام روشنایی.
     */
    fun computeOtsuThreshold(image: GrayscaleImage): Int {
        val pixels = image.pixels
        val total = pixels.size.toDouble()
        val histogram = IntArray(HISTOGRAM_BINS)
        var weightedSumAll = 0.0

        for (p in pixels) {
            val v = p.coerceIn(0, LUMA_MAX)
            histogram[v]++
            weightedSumAll += v.toDouble()
        }

        var weightBackground = 0.0
        var sumBackground = 0.0
        var maxBetweenVariance = -1.0
        var bestSum = 0L
        var bestCount = 0

        for (t in 0 until LUMA_MAX) {
            val count = histogram[t].toDouble()
            weightBackground += count
            if (weightBackground == 0.0) continue

            val weightForeground = total - weightBackground
            if (weightForeground <= 0.0) break

            sumBackground += t * count
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (weightedSumAll - sumBackground) / weightForeground
            val meanDiff = meanBackground - meanForeground
            val variance = weightBackground * weightForeground * meanDiff * meanDiff

            if (variance > maxBetweenVariance + 1e-9) {
                maxBetweenVariance = variance
                bestSum = t.toLong()
                bestCount = 1
            } else if (kotlin.math.abs(variance - maxBetweenVariance) <= 1e-9) {
                bestSum += t.toLong()
                bestCount++
            }
        }
        return if (bestCount > 0) (bestSum / bestCount).toInt() else 0
    }

    /**
     * باینری‌سازی تصویر با فرض روشن‌بودن ارقام (`255`) روی پس‌زمینه تیره‌تر (`0`).
     */
    fun applyThreshold(
        image: GrayscaleImage,
        mode: ThresholdMode = ThresholdMode.OTSU,
        fixedThreshold: Int = 140
    ): GrayscaleImage {
        val threshold = when (mode) {
            ThresholdMode.OTSU -> computeOtsuThreshold(image)
            ThresholdMode.FIXED -> fixedThreshold.coerceIn(0, LUMA_MAX)
        }
        val pixels = image.pixels
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val v = pixels[i].coerceIn(0, LUMA_MAX)
            out[i] = if (v > threshold) LUMA_MAX else 0
        }
        return GrayscaleImage(width = image.width, height = image.height, pixels = out)
    }

    /**
     * حذف نقاط پراکنده و بافت ریز با روش انتخابی [NoiseRemovalMode].
     */
    fun removeNoise(
        image: GrayscaleImage,
        mode: NoiseRemovalMode = NoiseRemovalMode.MEDIAN,
        kernelSize: Int = 3
    ): GrayscaleImage {
        if (mode == NoiseRemovalMode.NONE || kernelSize <= 1) {
            return GrayscaleImage(image.width, image.height, image.pixels.copyOf())
        }
        return when (mode) {
            NoiseRemovalMode.MEDIAN -> medianFilter(image, kernelSize)
            NoiseRemovalMode.MORPHOLOGICAL_OPEN -> morphologicalOpen(image, kernelSize)
            NoiseRemovalMode.MORPHOLOGICAL_CLOSE -> morphologicalClose(image, kernelSize)
            NoiseRemovalMode.NONE -> GrayscaleImage(image.width, image.height, image.pixels.copyOf())
        }
    }

    /**
     * فیلتر میانه دوبعدی با اندازه پنجره فرد [kernelSize] و تکرار مرزی ایمن.
     */
    fun medianFilter(
        image: GrayscaleImage,
        kernelSize: Int = 3
    ): GrayscaleImage {
        require(kernelSize >= 1 && kernelSize % 2 == 1) { "Kernel size must be a positive odd integer" }
        if (kernelSize == 1) {
            return GrayscaleImage(image.width, image.height, image.pixels.copyOf())
        }

        val w = image.width
        val h = image.height
        val src = image.pixels
        val out = IntArray(src.size)
        val radius = kernelSize / 2
        val window = IntArray(kernelSize * kernelSize)
        val medianIdx = window.size / 2

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                var idx = 0
                for (ky in -radius..radius) {
                    val sy = (y + ky).coerceIn(0, h - 1)
                    val sRowOffset = sy * w
                    for (kx in -radius..radius) {
                        val sx = (x + kx).coerceIn(0, w - 1)
                        window[idx++] = src[sRowOffset + sx]
                    }
                }
                window.sort()
                out[rowOffset + x] = window[medianIdx]
            }
        }
        return GrayscaleImage(width = w, height = h, pixels = out)
    }

    /**
     * فرسایش خاکستری (کمینه محلی) برای کوچک‌کردن پیش‌زمینه روشن و حذف نقاط سفید منفرد.
     */
    fun erode(
        image: GrayscaleImage,
        kernelSize: Int = 3
    ): GrayscaleImage {
        require(kernelSize >= 1 && kernelSize % 2 == 1) { "Kernel size must be a positive odd integer" }
        if (kernelSize == 1) {
            return GrayscaleImage(image.width, image.height, image.pixels.copyOf())
        }

        val w = image.width
        val h = image.height
        val src = image.pixels
        val out = IntArray(src.size)
        val radius = kernelSize / 2

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                var minVal = LUMA_MAX
                for (ky in -radius..radius) {
                    val sy = (y + ky).coerceIn(0, h - 1)
                    val sRowOffset = sy * w
                    for (kx in -radius..radius) {
                        val sx = (x + kx).coerceIn(0, w - 1)
                        val v = src[sRowOffset + sx]
                        if (v < minVal) minVal = v
                    }
                }
                out[rowOffset + x] = minVal
            }
        }
        return GrayscaleImage(width = w, height = h, pixels = out)
    }

    /**
     * بسط خاکستری (بیشینه محلی) برای بازگرداندن ضخامت پیش‌زمینه روشن یا پر کردن شکاف‌های ریز.
     */
    fun dilate(
        image: GrayscaleImage,
        kernelSize: Int = 3
    ): GrayscaleImage {
        require(kernelSize >= 1 && kernelSize % 2 == 1) { "Kernel size must be a positive odd integer" }
        if (kernelSize == 1) {
            return GrayscaleImage(image.width, image.height, image.pixels.copyOf())
        }

        val w = image.width
        val h = image.height
        val src = image.pixels
        val out = IntArray(src.size)
        val radius = kernelSize / 2

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                var maxVal = 0
                for (ky in -radius..radius) {
                    val sy = (y + ky).coerceIn(0, h - 1)
                    val sRowOffset = sy * w
                    for (kx in -radius..radius) {
                        val sx = (x + kx).coerceIn(0, w - 1)
                        val v = src[sRowOffset + sx]
                        if (v > maxVal) maxVal = v
                    }
                }
                out[rowOffset + x] = maxVal
            }
        }
        return GrayscaleImage(width = w, height = h, pixels = out)
    }

    /** بازسازی مورفولوژیک (فرسایش سپس بسط) برای حذف لکه‌های روشن جدا از ارقام. */
    fun morphologicalOpen(image: GrayscaleImage, kernelSize: Int = 3): GrayscaleImage =
        dilate(erode(image, kernelSize), kernelSize)

    /** بسته‌سازی مورفولوژیک (بسط سپس فرسایش) برای بستن حفره‌های تیره کوچک داخل ارقام روشن. */
    fun morphologicalClose(image: GrayscaleImage, kernelSize: Int = 3): GrayscaleImage =
        erode(dilate(image, kernelSize), kernelSize)

    /**
     * وارونه‌سازی شدت روشنایی (`255 - pixel`) تا ارقام تیره (`0`) روی پس‌زمینه سفید (`255`) قرار گیرند.
     */
    fun invert(image: GrayscaleImage): GrayscaleImage {
        val src = image.pixels
        val out = IntArray(src.size)
        for (i in src.indices) {
            out[i] = (LUMA_MAX - src[i]).coerceIn(0, LUMA_MAX)
        }
        return GrayscaleImage(width = image.width, height = image.height, pixels = out)
    }

    /**
     * بزرگ‌نمایی صحیح لبه تیز (Nearest-Neighbor) بدون ایجاد تاری خطی بین لبه سیاه و سفید.
     */
    fun upscaleNearestNeighbor(
        image: GrayscaleImage,
        scaleFactor: Int = 3
    ): GrayscaleImage? {
        if (scaleFactor < 1) return null
        if (scaleFactor == 1) {
            return GrayscaleImage(image.width, image.height, image.pixels.copyOf())
        }
        val outWidthLong = image.width.toLong() * scaleFactor
        val outHeightLong = image.height.toLong() * scaleFactor
        val totalLong = outWidthLong * outHeightLong
        if (outWidthLong > Int.MAX_VALUE || outHeightLong > Int.MAX_VALUE || totalLong > 16_777_216L) {
            return null
        }

        val outWidth = outWidthLong.toInt()
        val outHeight = outHeightLong.toInt()
        val src = image.pixels
        val out = IntArray(totalLong.toInt())

        for (y in 0 until outHeight) {
            val srcRow = (y / scaleFactor) * image.width
            val dstRow = y * outWidth
            for (x in 0 until outWidth) {
                out[dstRow + x] = src[srcRow + (x / scaleFactor)]
            }
        }
        return GrayscaleImage(width = outWidth, height = outHeight, pixels = out)
    }

    /**
     * افزودن حاشیه سفید یکنواخت (`255`) در چهار طرف تصویر.
     */
    fun addWhiteBorder(
        image: GrayscaleImage,
        paddingPx: Int = 16
    ): GrayscaleImage {
        require(paddingPx >= 0) { "Padding must be non-negative" }
        if (paddingPx == 0) {
            return GrayscaleImage(image.width, image.height, image.pixels.copyOf())
        }
        val outWidth = image.width + paddingPx * 2
        val outHeight = image.height + paddingPx * 2
        val out = IntArray(outWidth * outHeight) { LUMA_MAX }
        val src = image.pixels

        for (y in 0 until image.height) {
            val srcRow = y * image.width
            val dstRow = (y + paddingPx) * outWidth + paddingPx
            System.arraycopy(src, srcRow, out, dstRow, image.width)
        }
        return GrayscaleImage(width = outWidth, height = outHeight, pixels = out)
    }
}
