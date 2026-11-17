package com.example.data.captcha.preprocess

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.data.captcha.lab.CaptchaStageKind
import com.example.data.captcha.lab.CaptchaStageStatus
import com.example.data.captcha.lab.CapturedStageBuffer
import com.example.data.captcha.lab.DecodedArgbSnapshot
import com.example.data.captcha.lab.TracedPreprocessResult
import com.example.data.captcha.solver.AndroidBitmapFrame

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
     * اجرای پیش‌پردازش با ثبت کامل مراحل میانی و دلیل دقیق خطای احتمالی برای آزمایشگاه کپچا.
     */
    internal fun preprocessWithTrace(
        bytes: ByteArray,
        config: PreprocessConfig = PreprocessConfig()
    ): TracedPreprocessResult {
        val startNs = System.nanoTime()
        fun elapsedMs(): Long = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)

        if (bytes.isEmpty()) {
            return TracedPreprocessResult(
                frame = null,
                decodeStatus = "EMPTY_BYTES",
                decodedWidth = null,
                decodedHeight = null,
                mlKitInputWidth = null,
                mlKitInputHeight = null,
                stages = buildDecodeFailedStages("EMPTY_BYTES"),
                failureReason = "بایت‌های ورودی تصویر خالی هستند.",
                preprocessDurationMs = elapsedMs()
            )
        }
        if (!config.isValid()) {
            return TracedPreprocessResult(
                frame = null,
                decodeStatus = "INVALID_CONFIG",
                decodedWidth = null,
                decodedHeight = null,
                mlKitInputWidth = null,
                mlKitInputHeight = null,
                stages = buildDecodeFailedStages("INVALID_CONFIG"),
                failureReason = "تنظیمات PreprocessConfig نامعتبر است.",
                preprocessDurationMs = elapsedMs()
            )
        }

        return try {
            val decoded = decodeBytesWithArgbSnapshot(bytes, config)
            if (decoded.snapshot == null) {
                return TracedPreprocessResult(
                    frame = null,
                    decodeStatus = decoded.status,
                    decodedWidth = decoded.boundsWidth,
                    decodedHeight = decoded.boundsHeight,
                    mlKitInputWidth = null,
                    mlKitInputHeight = null,
                    stages = buildDecodeFailedStages(decoded.failureReason ?: decoded.status),
                    failureReason = decoded.failureReason ?: decoded.status,
                    preprocessDurationMs = elapsedMs()
                )
            }

            val snap = decoded.snapshot
            val grayscale = CaptchaPreprocessPipeline.toGrayscaleFromArgb(
                width = snap.width,
                height = snap.height,
                argbPixels = snap.argbPixels
            )
            val trace = CaptchaPreprocessPipeline.processWithStages(grayscale, config)

            val stages = buildPipelineStageBuffers(snap, trace)
            val finalImg = trace.finalPadded
            if (finalImg == null) {
                return TracedPreprocessResult(
                    frame = null,
                    decodeStatus = "SUCCESS",
                    decodedWidth = snap.width,
                    decodedHeight = snap.height,
                    mlKitInputWidth = null,
                    mlKitInputHeight = null,
                    stages = stages,
                    failureReason = trace.failureReason ?: "Preprocess pipeline returned null",
                    preprocessDurationMs = elapsedMs()
                )
            }

            val finalBitmap = encodeGrayscaleToBitmap(finalImg)
            if (finalBitmap == null) {
                val updatedStages = stages.map { st ->
                    if (st.stage == CaptchaStageKind.FINAL_PADDED) {
                        st.copy(status = CaptchaStageStatus.FAILED, note = "Bitmap encode failed")
                    } else st
                }
                return TracedPreprocessResult(
                    frame = null,
                    decodeStatus = "SUCCESS",
                    decodedWidth = snap.width,
                    decodedHeight = snap.height,
                    mlKitInputWidth = null,
                    mlKitInputHeight = null,
                    stages = updatedStages,
                    failureReason = "Bitmap encoding of final padded stage failed",
                    preprocessDurationMs = elapsedMs()
                )
            }

            TracedPreprocessResult(
                frame = AndroidBitmapFrame(finalBitmap),
                decodeStatus = "SUCCESS",
                decodedWidth = snap.width,
                decodedHeight = snap.height,
                mlKitInputWidth = finalImg.width,
                mlKitInputHeight = finalImg.height,
                stages = stages,
                failureReason = null,
                preprocessDurationMs = elapsedMs()
            )
        } catch (oom: OutOfMemoryError) {
            TracedPreprocessResult(
                frame = null,
                decodeStatus = "OUT_OF_MEMORY",
                decodedWidth = null,
                decodedHeight = null,
                mlKitInputWidth = null,
                mlKitInputHeight = null,
                stages = buildDecodeFailedStages("OutOfMemoryError"),
                failureReason = "OutOfMemoryError during decode/preprocess",
                exceptionType = oom.javaClass.name,
                preprocessDurationMs = elapsedMs()
            )
        } catch (t: Throwable) {
            TracedPreprocessResult(
                frame = null,
                decodeStatus = "EXCEPTION",
                decodedWidth = null,
                decodedHeight = null,
                mlKitInputWidth = null,
                mlKitInputHeight = null,
                stages = buildDecodeFailedStages("${t.javaClass.simpleName}: ${t.message ?: ""}"),
                failureReason = "${t.javaClass.simpleName}: ${t.message ?: "Unexpected preprocess error"}",
                exceptionType = t.javaClass.name,
                preprocessDurationMs = elapsedMs()
            )
        }
    }

    private data class DecodeSnapshotOutcome(
        val snapshot: DecodedArgbSnapshot? = null,
        val status: String,
        val boundsWidth: Int? = null,
        val boundsHeight: Int? = null,
        val failureReason: String? = null
    )

    private fun decodeBytesWithArgbSnapshot(
        bytes: ByteArray,
        config: PreprocessConfig
    ): DecodeSnapshotOutcome {
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        if (rawW <= 0 || rawH <= 0) {
            return DecodeSnapshotOutcome(
                status = "DECODE_BOUNDS_FAILED",
                failureReason = "BitmapFactory failed to read image bounds (${rawW}x${rawH})"
            )
        }
        if (rawW < config.minDimensionPx || rawH < config.minDimensionPx) {
            return DecodeSnapshotOutcome(
                status = "DIMENSIONS_TOO_SMALL",
                boundsWidth = rawW,
                boundsHeight = rawH,
                failureReason = "Dimensions (${rawW}x${rawH}) below minDimensionPx=${config.minDimensionPx}"
            )
        }
        if (rawW > config.maxDimensionPx || rawH > config.maxDimensionPx) {
            return DecodeSnapshotOutcome(
                status = "DIMENSIONS_TOO_LARGE",
                boundsWidth = rawW,
                boundsHeight = rawH,
                failureReason = "Dimensions (${rawW}x${rawH}) exceed maxDimensionPx=${config.maxDimensionPx}"
            )
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
            inScaled = false
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: return DecodeSnapshotOutcome(
                status = "DECODE_FAILED",
                boundsWidth = rawW,
                boundsHeight = rawH,
                failureReason = "BitmapFactory.decodeByteArray returned null"
            )
        var argbBitmap: Bitmap? = null

        return try {
            val width = decoded.width
            val height = decoded.height
            if (width < config.minDimensionPx || height < config.minDimensionPx) {
                return DecodeSnapshotOutcome(
                    status = "DIMENSIONS_TOO_SMALL",
                    boundsWidth = width,
                    boundsHeight = height,
                    failureReason = "Decoded dimensions (${width}x${height}) below minDimensionPx=${config.minDimensionPx}"
                )
            }
            if (width > config.maxDimensionPx || height > config.maxDimensionPx) {
                return DecodeSnapshotOutcome(
                    status = "DIMENSIONS_TOO_LARGE",
                    boundsWidth = width,
                    boundsHeight = height,
                    failureReason = "Decoded dimensions (${width}x${height}) exceed maxDimensionPx=${config.maxDimensionPx}"
                )
            }

            val readableBitmap = if (decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                decoded.copy(Bitmap.Config.ARGB_8888, false)?.also { argbBitmap = it }
                    ?: return DecodeSnapshotOutcome(
                        status = "ARGB_CONVERT_FAILED",
                        boundsWidth = width,
                        boundsHeight = height,
                        failureReason = "Failed to copy decoded bitmap to ARGB_8888"
                    )
            }

            val argbPixels = IntArray(width * height)
            readableBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
            DecodeSnapshotOutcome(
                snapshot = DecodedArgbSnapshot(width = width, height = height, argbPixels = argbPixels),
                status = "SUCCESS",
                boundsWidth = width,
                boundsHeight = height
            )
        } finally {
            argbBitmap?.let { if (!it.isRecycled) it.recycle() }
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun buildDecodeFailedStages(reason: String): List<CapturedStageBuffer> =
        CaptchaStageKind.entries.map { kind ->
            if (kind == CaptchaStageKind.DECODED) {
                CapturedStageBuffer(stage = kind, status = CaptchaStageStatus.FAILED, note = reason)
            } else {
                CapturedStageBuffer(stage = kind, status = CaptchaStageStatus.NOT_RUN, note = "Decode did not complete")
            }
        }

    private fun buildPipelineStageBuffers(
        decoded: DecodedArgbSnapshot,
        trace: CaptchaPreprocessPipeline.PipelineStageTrace
    ): List<CapturedStageBuffer> {
        fun grayStage(
            kind: CaptchaStageKind,
            img: GrayscaleImage?,
            skipped: Boolean = false,
            skipReason: String? = null
        ): CapturedStageBuffer {
            return when {
                img != null -> CapturedStageBuffer(
                    stage = kind,
                    status = CaptchaStageStatus.COMPLETED,
                    width = img.width,
                    height = img.height,
                    grayscaleImage = img
                )
                skipped -> CapturedStageBuffer(
                    stage = kind,
                    status = CaptchaStageStatus.SKIPPED,
                    note = skipReason
                )
                trace.failedStageName == kind.name -> CapturedStageBuffer(
                    stage = kind,
                    status = CaptchaStageStatus.FAILED,
                    note = trace.failureReason
                )
                else -> CapturedStageBuffer(
                    stage = kind,
                    status = CaptchaStageStatus.NOT_RUN,
                    note = trace.failureReason
                )
            }
        }

        return listOf(
            CapturedStageBuffer(
                stage = CaptchaStageKind.DECODED,
                status = CaptchaStageStatus.COMPLETED,
                width = decoded.width,
                height = decoded.height,
                argbSnapshot = decoded
            ),
            grayStage(CaptchaStageKind.GRAYSCALE, trace.grayscale),
            grayStage(CaptchaStageKind.CONTRAST_ENHANCED, trace.contrastEnhanced),
            grayStage(CaptchaStageKind.THRESHOLDED, trace.thresholded),
            grayStage(CaptchaStageKind.DENOISED, trace.denoised),
            grayStage(
                CaptchaStageKind.INVERTED,
                trace.inverted,
                skipped = trace.invertSkipped,
                skipReason = "invertOutput=false"
            ),
            grayStage(CaptchaStageKind.UPSCALED, trace.upscaled),
            grayStage(CaptchaStageKind.FINAL_PADDED, trace.finalPadded)
        )
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
