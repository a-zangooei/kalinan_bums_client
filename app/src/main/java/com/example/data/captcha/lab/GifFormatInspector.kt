package com.example.data.captcha.lab

/**
 * نتیجه بازرسی باینری فایل تصویر خام دریافتی از سرور (بدون تبدیل یا تغییر فرمت).
 */
data class RawImageInspection(
    val rawFormat: String,
    val mimeType: String,
    val rawExtension: String,
    val headerWidth: Int?,
    val headerHeight: Int?,
    val gifInfo: GifInspectionInfo
)

/**
 * تحلیل‌گر خالص JVM برای شناسایی فرمت واقعی بایت‌های تصویر خام و بررسی دقیق ساختار فایل‌های GIF
 * (تشخیص GIF87a / GIF89a، شمارش تعداد فریم‌ها و شناسایی انیمیشن‌بودن).
 */
object GifFormatInspector {

    fun inspect(bytes: ByteArray): RawImageInspection {
        if (bytes.isEmpty()) {
            return RawImageInspection(
                rawFormat = "EMPTY",
                mimeType = "application/octet-stream",
                rawExtension = "bin",
                headerWidth = null,
                headerHeight = null,
                gifInfo = GifInspectionInfo(isGif = false, note = "Empty payload")
            )
        }

        if (isGifSignature(bytes)) {
            return inspectGif(bytes)
        }

        if (isPngSignature(bytes)) {
            val w = if (bytes.size >= 24) readIntBigEndian(bytes, 16) else null
            val h = if (bytes.size >= 24) readIntBigEndian(bytes, 20) else null
            return RawImageInspection(
                rawFormat = "PNG",
                mimeType = "image/png",
                rawExtension = "png",
                headerWidth = w,
                headerHeight = h,
                gifInfo = GifInspectionInfo(isGif = false)
            )
        }

        if (isJpegSignature(bytes)) {
            return RawImageInspection(
                rawFormat = "JPEG",
                mimeType = "image/jpeg",
                rawExtension = "jpg",
                headerWidth = null,
                headerHeight = null,
                gifInfo = GifInspectionInfo(isGif = false)
            )
        }

        if (isWebpSignature(bytes)) {
            return RawImageInspection(
                rawFormat = "WEBP",
                mimeType = "image/webp",
                rawExtension = "webp",
                headerWidth = null,
                headerHeight = null,
                gifInfo = GifInspectionInfo(isGif = false)
            )
        }

        if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
            return RawImageInspection(
                rawFormat = "BMP",
                mimeType = "image/bmp",
                rawExtension = "bmp",
                headerWidth = null,
                headerHeight = null,
                gifInfo = GifInspectionInfo(isGif = false)
            )
        }

        return RawImageInspection(
            rawFormat = "UNKNOWN",
            mimeType = "application/octet-stream",
            rawExtension = "bin",
            headerWidth = null,
            headerHeight = null,
            gifInfo = GifInspectionInfo(isGif = false, note = "Unrecognized header signature")
        )
    }

    private fun inspectGif(bytes: ByteArray): RawImageInspection {
        val version = String(bytes, 0, 6, Charsets.US_ASCII)
        if (bytes.size < 13) {
            return RawImageInspection(
                rawFormat = "GIF",
                mimeType = "image/gif",
                rawExtension = "gif",
                headerWidth = null,
                headerHeight = null,
                gifInfo = GifInspectionInfo(
                    isGif = true,
                    gifVersion = version,
                    isAnimatedGif = false,
                    frameCount = 0,
                    note = "Truncated GIF header (${bytes.size} bytes)"
                )
            )
        }

        val logicalW = readUInt16LittleEndian(bytes, 6)
        val logicalH = readUInt16LittleEndian(bytes, 8)
        val packed = bytes[10].toInt() and 0xFF
        val hasGct = (packed and 0x80) != 0
        val gctSize = if (hasGct) 3 * (1 shl ((packed and 0x07) + 1)) else 0

        var pos = 13 + gctSize
        var frames = 0
        var note: String? = null

        while (pos < bytes.size) {
            when (val block = bytes[pos].toInt() and 0xFF) {
                0x3B -> { // Trailer
                    break
                }
                0x21 -> { // Extension introducer
                    if (pos + 2 >= bytes.size) {
                        note = "Truncated GIF extension block"
                        break
                    }
                    pos += 2 // skip 0x21 and extension label
                    pos = skipSubBlocks(bytes, pos)
                }
                0x2C -> { // Image Descriptor -> represents a frame
                    frames++
                    if (pos + 10 > bytes.size) {
                        note = "Truncated GIF image descriptor at frame #$frames"
                        break
                    }
                    val imgPacked = bytes[pos + 9].toInt() and 0xFF
                    val hasLct = (imgPacked and 0x80) != 0
                    val lctSize = if (hasLct) 3 * (1 shl ((imgPacked and 0x07) + 1)) else 0
                    pos += 10 + lctSize
                    if (pos >= bytes.size) {
                        note = "Truncated LZW minimum code size at frame #$frames"
                        break
                    }
                    pos += 1 // skip LZW minimum code size
                    pos = skipSubBlocks(bytes, pos)
                }
                else -> {
                    note = "Unexpected GIF block 0x${block.toString(16)} at offset $pos"
                    break
                }
            }
        }

        val effectiveNote = note ?: when {
            frames > 1 -> "Animated GIF with $frames frames; Android BitmapFactory decodes frame #0"
            frames == 1 -> "Single-frame static GIF"
            else -> "No GIF image descriptor frame found"
        }

        return RawImageInspection(
            rawFormat = "GIF",
            mimeType = "image/gif",
            rawExtension = "gif",
            headerWidth = logicalW,
            headerHeight = logicalH,
            gifInfo = GifInspectionInfo(
                isGif = true,
                gifVersion = version,
                isAnimatedGif = frames > 1,
                frameCount = frames,
                logicalWidth = logicalW,
                logicalHeight = logicalH,
                note = effectiveNote
            )
        )
    }

    private fun skipSubBlocks(bytes: ByteArray, startOffset: Int): Int {
        var pos = startOffset
        while (pos < bytes.size) {
            val blockSize = bytes[pos].toInt() and 0xFF
            pos++
            if (blockSize == 0) return pos
            pos += blockSize
        }
        return bytes.size
    }

    private fun isGifSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        return bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
    }

    private fun isPngSignature(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte() &&
            (bytes[4].toInt() and 0xFF) == 0x0D &&
            (bytes[5].toInt() and 0xFF) == 0x0A &&
            (bytes[6].toInt() and 0xFF) == 0x1A &&
            (bytes[7].toInt() and 0xFF) == 0x0A

    private fun isJpegSignature(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF

    private fun isWebpSignature(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()

    private fun readUInt16LittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
