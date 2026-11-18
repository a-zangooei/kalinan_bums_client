package com.example

import com.example.data.captcha.preprocess.CaptchaPreprocessPipeline
import com.example.data.captcha.preprocess.CaptchaPreprocessor
import com.example.data.captcha.preprocess.GrayscaleImage
import com.example.data.captcha.preprocess.NoiseRemovalMode
import com.example.data.captcha.preprocess.PreprocessConfig
import com.example.data.captcha.preprocess.ThresholdMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/**
 * تست‌های واحد خالص JVM برای لایه پیش‌پردازش کپچا (بدون نیاز به Robolectric یا کتابخانه OCR).
 */
class CaptchaPreprocessPipelineUnitTest {

    @Test
    fun toGrayscaleFromArgb_computesBt601LuminanceAndRedactsToString() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val white = 0xFFFFFFFF.toInt()

        val gray = CaptchaPreprocessPipeline.toGrayscaleFromArgb(
            width = 2,
            height = 2,
            argbPixels = intArrayOf(red, green, blue, white)
        )

        assertEquals(2, gray.width)
        assertEquals(2, gray.height)
        assertEquals(76, gray.pixels[0])   // (77 * 255) >> 8 = 76
        assertEquals(149, gray.pixels[1])  // (150 * 255) >> 8 = 149
        assertEquals(28, gray.pixels[2])   // (29 * 255) >> 8 = 28
        assertEquals(255, gray.pixels[3])
        assertEquals("GrayscaleImage(2x2)", gray.toString())
    }

    @Test
    fun stretchContrast_expandsDynamicRangeWhileIgnoringSingleOutlierNoise() {
        val w = 20
        val h = 10
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            if (x < 10) 90 else 190
        }
        // 1% extreme outliers (2 pixels out of 200) must not defeat 2% clipping
        pixels[0] = 0
        pixels[199] = 255

        val stretched = CaptchaPreprocessPipeline.stretchContrast(
            GrayscaleImage(w, h, pixels),
            clipPercent = 0.02f
        )

        assertEquals(0, stretched.pixels[1])
        assertEquals(255, stretched.pixels[198])
    }

    @Test
    fun fullPurePipeline_separatesFourWhiteDigitsOnTexturedNoisyGrayBackground() {
        val synthetic = buildSyntheticFourDigitCaptcha()

        // 1. Contrast stretch
        val contrasted = CaptchaPreprocessPipeline.stretchContrast(synthetic, clipPercent = 0.02f)

        // 2. Otsu thresholding (light digits = 255, dark background = 0)
        val otsuThreshold = CaptchaPreprocessPipeline.computeOtsuThreshold(contrasted)
        assertTrue("Otsu threshold ($otsuThreshold) should lie between background and digits", otsuThreshold in 70..200)

        val thresholded = CaptchaPreprocessPipeline.applyThreshold(
            image = contrasted,
            mode = ThresholdMode.OTSU
        )
        val isolatedNoiseBefore = countIsolatedForegroundPixels(thresholded, foregroundValue = 255)

        // 3. Median 3x3 removes isolated noise speckles while preserving all 4 digits
        val denoisedMedian = CaptchaPreprocessPipeline.removeNoise(
            image = thresholded,
            mode = NoiseRemovalMode.MEDIAN,
            kernelSize = 3
        )
        val isolatedNoiseAfterMedian = countIsolatedForegroundPixels(denoisedMedian, foregroundValue = 255)
        assertTrue(
            "Median filter should reduce/remove isolated noise (before=$isolatedNoiseBefore, after=$isolatedNoiseAfterMedian)",
            isolatedNoiseAfterMedian < isolatedNoiseBefore || isolatedNoiseAfterMedian == 0
        )
        assertEquals(
            "Exactly 4 separate digit components must survive median denoising",
            4,
            countConnectedComponents(denoisedMedian, targetValue = 255)
        )

        // Morphological close heals the internal pinhole inside digit 1, then open strips all salt dots
        val closedThenOpened = CaptchaPreprocessPipeline.morphologicalOpen(
            CaptchaPreprocessPipeline.removeNoise(
                image = thresholded,
                mode = NoiseRemovalMode.MORPHOLOGICAL_CLOSE,
                kernelSize = 3
            ),
            kernelSize = 3
        )
        assertEquals(0, countIsolatedForegroundPixels(closedThenOpened, foregroundValue = 255))
        assertEquals(4, countConnectedComponents(closedThenOpened, targetValue = 255))

        // 4. Inversion -> dark digits (0) on light background (255)
        val inverted = CaptchaPreprocessPipeline.invert(denoisedMedian)
        assertEquals(255, inverted.pixels[0]) // corner background is now pure white
        assertEquals(4, countConnectedComponents(inverted, targetValue = 0))

        // 5. Complete process() end-to-end (default 3x nearest-neighbor + 16px white border)
        val config = PreprocessConfig()
        val finalOutput = CaptchaPreprocessPipeline.process(synthetic, config)
        assertNotNull(finalOutput)
        finalOutput!!

        val expectedWidth = synthetic.width * config.scaleFactor + config.borderPaddingPx * 2
        val expectedHeight = synthetic.height * config.scaleFactor + config.borderPaddingPx * 2
        assertEquals(expectedWidth, finalOutput.width)
        assertEquals(expectedHeight, finalOutput.height)

        // Verify strictly binary crisp values (0 or 255, no interpolated blur)
        assertTrue(finalOutput.pixels.all { it == 0 || it == 255 })

        // Verify 16px border is 100% pure white (255)
        for (y in 0 until finalOutput.height) {
            for (x in 0 until finalOutput.width) {
                if (x < config.borderPaddingPx ||
                    x >= finalOutput.width - config.borderPaddingPx ||
                    y < config.borderPaddingPx ||
                    y >= finalOutput.height - config.borderPaddingPx
                ) {
                    assertEquals("Border pixel ($x,$y) must be white", 255, finalOutput.pixels[y * finalOutput.width + x])
                }
            }
        }

        // Verify final upscaled & padded image still contains exactly 4 separated dark digit components
        assertEquals(4, countConnectedComponents(finalOutput, targetValue = 0))
    }

    @Test
    fun fixedThreshold_and_upscale2x4x_workAsConfigured() {
        val synthetic = buildSyntheticFourDigitCaptcha()
        val config2x = PreprocessConfig(
            thresholdMode = ThresholdMode.FIXED,
            fixedThreshold = 150,
            noiseRemovalMode = NoiseRemovalMode.MEDIAN,
            scaleFactor = 2,
            borderPaddingPx = 8
        )
        val out2x = CaptchaPreprocessPipeline.process(synthetic, config2x)
        assertNotNull(out2x)
        assertEquals(synthetic.width * 2 + 16, out2x!!.width)
        assertEquals(synthetic.height * 2 + 16, out2x.height)
        assertEquals(4, countConnectedComponents(out2x, targetValue = 0))

        val config4x = config2x.copy(scaleFactor = 4, borderPaddingPx = 4)
        val out4x = CaptchaPreprocessPipeline.process(synthetic, config4x)
        assertNotNull(out4x)
        assertEquals(synthetic.width * 4 + 8, out4x!!.width)
        assertEquals(synthetic.height * 4 + 8, out4x.height)
        assertEquals(4, countConnectedComponents(out4x, targetValue = 0))
    }

    @Test
    fun thresholdModeNone_preservesContinuousGrayscaleAndMarksTraceSkipped() {
        val w = 12
        val h = 10
        val grad = GrayscaleImage(w, h, IntArray(w * h) { idx -> 60 + ((idx % w) * 14) })
        val nonBinaryCfg = PreprocessConfig(
            useDecodedColorFrame = false,
            contrastClipPercent = 0.02f,
            thresholdMode = ThresholdMode.NONE,
            noiseRemovalMode = NoiseRemovalMode.NONE,
            invertOutput = false,
            scaleFactor = 1,
            borderPaddingPx = 0
        )

        val processed = CaptchaPreprocessPipeline.process(grad, nonBinaryCfg)
        assertNotNull(processed)
        processed!!
        assertEquals(w, processed.width)
        assertEquals(h, processed.height)
        // Continuous intermediate grayscale shades must survive (not clamped strictly to {0, 255})
        assertTrue(processed.pixels.any { it in 1..254 })

        val traced = CaptchaPreprocessPipeline.processWithStages(grad, nonBinaryCfg)
        assertEquals(processed, traced.finalPadded)
        assertTrue(traced.thresholdSkipped)
        assertTrue(traced.denoiseSkipped)
        assertTrue(traced.invertSkipped)
        assertNull(traced.thresholded)
        assertNull(traced.denoised)
        assertNull(traced.inverted)
    }

    @Test
    fun invalidCorruptEmptyAndUndersizedInputs_returnNullSafelyWithoutCrashing() {
        assertNull(CaptchaPreprocessor.preprocess(ByteArray(0)))
        assertNull(CaptchaPreprocessor.preprocess("not-a-valid-image-payload".toByteArray()))

        // Tiny valid 2x2 PNG bytes -> rejected because smaller than minDimensionPx=8
        val tiny2x2Png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x02,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x72, 0xB6.toByte(), 0x0D, 0x24
        )
        assertNull(CaptchaPreprocessor.preprocess(tiny2x2Png))

        // Pure pipeline rejects tiny images and oversized images
        val tinyGray = GrayscaleImage(4, 4, IntArray(16) { 128 })
        assertNull(CaptchaPreprocessPipeline.process(tinyGray, PreprocessConfig(minDimensionPx = 8)))

        val oversizedCheck = CaptchaPreprocessPipeline.process(
            GrayscaleImage(30, 30, IntArray(900) { 128 }),
            PreprocessConfig(maxDimensionPx = 20)
        )
        assertNull(oversizedCheck)

        // Flat solid image must not crash Otsu or contrast stretch
        val flatImage = GrayscaleImage(16, 16, IntArray(256) { 110 })
        assertNotNull(CaptchaPreprocessPipeline.process(flatImage))

        // Invalid configs fail cleanly
        assertFalse(PreprocessConfig(scaleFactor = 0).isValid())
        assertFalse(PreprocessConfig(filterSize = 2).isValid())
        assertFalse(PreprocessConfig(borderPaddingPx = -1).isValid())
        assertNull(CaptchaPreprocessPipeline.process(flatImage, PreprocessConfig(scaleFactor = 0)))
    }

    /**
     * ساخت تصویر مصنوعی 120x40 شامل ۴ رقم روشن مجزا با ضخامت حداقل ۳ پیکسل روی پس‌زمینه
     * خاکستری بافت‌دار به همراه نقاط نویز منفرد سفید و سیاه.
     */
    private fun buildSyntheticFourDigitCaptcha(): GrayscaleImage {
        val w = 120
        val h = 40
        val pixels = IntArray(w * h)

        // Gray background with subtle gradient + diagonal/checker fine texture (80..120)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = 85 + (x * 15) / w
                val texture = if ((x + y) % 4 == 0) 18 else 0
                pixels[y * w + x] = base + texture
            }
        }

        // Helper to draw thick >=3px strokes for clean digits separated by >=10px
        fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, luma: Int) {
            for (y in y0..y1) {
                for (x in x0..x1) {
                    if (x in 0 until w && y in 0 until h) {
                        pixels[y * w + x] = luma
                    }
                }
            }
        }

        val digitLuma = 232

        // Digit 1 at x=12..22: vertical bar
        fillRect(16, 8, 19, 31, digitLuma)

        // Digit 2 at x=36..52: top, right-top, mid, left-bottom, bottom
        fillRect(36, 8, 52, 11, digitLuma)
        fillRect(49, 8, 52, 20, digitLuma)
        fillRect(36, 18, 52, 21, digitLuma)
        fillRect(36, 18, 39, 31, digitLuma)
        fillRect(36, 28, 52, 31, digitLuma)

        // Digit 3 at x=64..80: '7'
        fillRect(64, 8, 80, 11, digitLuma)
        fillRect(76, 8, 79, 31, digitLuma)

        // Digit 4 at x=92..108: '0' hollow rectangle
        fillRect(92, 8, 108, 11, digitLuma)
        fillRect(92, 28, 108, 31, digitLuma)
        fillRect(92, 8, 95, 31, digitLuma)
        fillRect(105, 8, 108, 31, digitLuma)

        // Inject isolated 1-pixel bright salt noise on background & isolated dark pepper inside a stroke
        val saltCoords = listOf(
            4 to 4, 28 to 10, 30 to 33, 58 to 15, 60 to 27, 86 to 6, 87 to 29, 115 to 12, 114 to 35
        )
        for ((nx, ny) in saltCoords) {
            pixels[ny * w + nx] = 248
        }
        pixels[15 * w + 17] = 40 // 1px dark pinhole inside digit 1

        return GrayscaleImage(w, h, pixels)
    }

    private fun countIsolatedForegroundPixels(image: GrayscaleImage, foregroundValue: Int): Int {
        var count = 0
        val w = image.width
        val h = image.height
        val p = image.pixels
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                if (p[y * w + x] != foregroundValue) continue
                var neighbors = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (p[(y + dy) * w + (x + dx)] == foregroundValue) neighbors++
                    }
                }
                if (neighbors == 0) count++
            }
        }
        return count
    }

    private fun countConnectedComponents(image: GrayscaleImage, targetValue: Int): Int {
        val w = image.width
        val h = image.height
        val p = image.pixels
        val visited = BooleanArray(p.size)
        val queue = ArrayDeque<Int>()
        var components = 0

        for (start in p.indices) {
            if (visited[start] || p[start] != targetValue) continue
            components++
            visited[start] = true
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                val cx = cur % w
                val cy = cur / w
                for (dy in -1..1) {
                    val ny = cy + dy
                    if (ny !in 0 until h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        if (nx !in 0 until w) continue
                        val nextIdx = ny * w + nx
                        if (!visited[nextIdx] && p[nextIdx] == targetValue) {
                            visited[nextIdx] = true
                            queue.addLast(nextIdx)
                        }
                    }
                }
            }
        }
        return components
    }
}
