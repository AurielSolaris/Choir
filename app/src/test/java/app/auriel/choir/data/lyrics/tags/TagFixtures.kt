// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.tags

import java.io.ByteArrayOutputStream

/**
 * Builders for the tag layouts the readers parse.
 *
 * Hand-built rather than checked in as sample files: a byte array in the test
 * says exactly which field is under test, and the repository has no business
 * carrying copyrighted audio just to prove a parser works.
 */

internal fun bytes(vararg parts: Any): ByteArray {
    val out = ByteArrayOutputStream()
    for (part in parts) {
        when (part) {
            is ByteArray -> out.write(part)
            is String -> out.write(part.toByteArray(Charsets.ISO_8859_1))
            is Int -> out.write(part)
            else -> error("unsupported fixture part: $part")
        }
    }
    return out.toByteArray()
}

/** ID3's seven-bits-per-byte length. */
internal fun syncsafe32(value: Int) = byteArrayOf(
    ((value shr 21) and 0x7F).toByte(),
    ((value shr 14) and 0x7F).toByte(),
    ((value shr 7) and 0x7F).toByte(),
    (value and 0x7F).toByte(),
)

internal fun u32be(value: Int) = byteArrayOf(
    ((value shr 24) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
)

internal fun u32le(value: Int) = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 24) and 0xFF).toByte(),
)

internal fun u24be(value: Int) = byteArrayOf(
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
)

// --- ID3v2 -------------------------------------------------------------------

internal class Id3Frame(val id: String, val content: ByteArray)

internal fun id3Tag(major: Int, vararg frames: Id3Frame, tagFlags: Int = 0): ByteArray {
    val body = ByteArrayOutputStream()
    for (frame in frames) {
        if (major == 2) {
            body.write(frame.id.toByteArray(Charsets.US_ASCII))
            body.write(u24be(frame.content.size))
        } else {
            body.write(frame.id.toByteArray(Charsets.US_ASCII))
            // 2.4 made the frame size synchsafe; 2.3 left it a plain integer.
            body.write(if (major == 4) syncsafe32(frame.content.size) else u32be(frame.content.size))
            body.write(0)
            body.write(0)
        }
        body.write(frame.content)
    }
    val payload = body.toByteArray()

    return bytes("ID3", major, 0, tagFlags, syncsafe32(payload.size), payload)
}

/** `encoding, language, descriptor, text`. */
internal fun uslt(text: String, encoding: Int = 3, descriptor: String = ""): ByteArray = bytes(
    encoding,
    "eng",
    encodeText(descriptor, encoding),
    terminator(encoding),
    encodeText(text, encoding),
)

/** `encoding, language, time format, content type, descriptor`, then the lines. */
internal fun sylt(
    lines: List<Pair<Long, String>>,
    encoding: Int = 3,
    timestampFormat: Int = 2,
    contentType: Int = 1,
): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(bytes(encoding, "eng", timestampFormat, contentType, terminator(encoding)))
    for ((timeMs, text) in lines) {
        out.write(encodeText(text, encoding))
        out.write(terminator(encoding))
        out.write(u32be(timeMs.toInt()))
    }
    return out.toByteArray()
}

internal fun txxx(description: String, value: String, encoding: Int = 3): ByteArray = bytes(
    encoding,
    encodeText(description, encoding),
    terminator(encoding),
    encodeText(value, encoding),
)

private fun encodeText(text: String, encoding: Int): ByteArray = when (encoding) {
    0 -> text.toByteArray(Charsets.ISO_8859_1)
    1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
    2 -> text.toByteArray(Charsets.UTF_16BE)
    else -> text.toByteArray(Charsets.UTF_8)
}

private fun terminator(encoding: Int): ByteArray =
    if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)

// --- Vorbis comments ---------------------------------------------------------

internal fun vorbisCommentBlock(vararg comments: String): ByteArray {
    val out = ByteArrayOutputStream()
    val vendor = "choir-test".toByteArray(Charsets.UTF_8)
    out.write(u32le(vendor.size))
    out.write(vendor)
    out.write(u32le(comments.size))
    for (comment in comments) {
        val encoded = comment.toByteArray(Charsets.UTF_8)
        out.write(u32le(encoded.size))
        out.write(encoded)
    }
    return out.toByteArray()
}

/** `fLaC`, then metadata blocks; the top bit of the type byte ends the chain. */
internal fun flacFile(vararg blocks: Pair<Int, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("fLaC".toByteArray(Charsets.US_ASCII))
    blocks.forEachIndexed { index, (type, content) ->
        val isLast = index == blocks.lastIndex
        out.write(if (isLast) type or 0x80 else type)
        out.write(u24be(content.size))
        out.write(content)
    }
    return out.toByteArray()
}

/**
 * An Ogg page with the segment table given verbatim, so a test can build the
 * awkward cases — a packet left open at the page boundary, in particular.
 */
internal fun oggRawPage(segments: List<Int>, data: ByteArray, sequence: Int = 0): ByteArray = bytes(
    "OggS",
    0, // version
    if (sequence == 0) 2 else 1, // beginning-of-stream, then continuation
    ByteArray(8), // granule position
    u32be(1), // stream serial
    u32be(sequence),
    u32be(0), // checksum, which nothing here verifies
    segments.size,
    segments.map(Int::toByte).toByteArray(),
    data,
)

/**
 * One page carrying whole [packets]. A packet is laid out as segments of 255
 * bytes with a shorter one to end it, which is how a reader knows where one
 * packet stops and the next begins.
 */
internal fun oggPage(packets: List<ByteArray>, sequence: Int = 0): ByteArray {
    val segments = mutableListOf<Int>()
    val data = ByteArrayOutputStream()

    for (packet in packets) {
        var offset = 0
        while (packet.size - offset >= 255) {
            segments += 255
            data.write(packet, offset, 255)
            offset += 255
        }
        segments += packet.size - offset
        data.write(packet, offset, packet.size - offset)
    }
    return oggRawPage(segments, data.toByteArray(), sequence)
}

/** Segment lengths for a packet that ends inside its page. */
internal fun segmentsFor(packet: ByteArray): List<Int> =
    List(packet.size / 255) { 255 } + listOf(packet.size % 255)

// --- MP4 ---------------------------------------------------------------------

/** `size, type, payload` — the whole of an MP4 atom header. */
internal fun atom(type: String, payload: ByteArray): ByteArray =
    bytes(u32be(payload.size + 8), type, payload)

internal fun atom(type: String, vararg children: ByteArray): ByteArray =
    atom(type, children.fold(ByteArray(0), ByteArray::plus))

/** An item's `data` atom: type indicator, locale, then UTF-8 text. */
internal fun dataAtom(text: String, typeIndicator: Int = 1): ByteArray =
    atom("data", bytes(u32be(typeIndicator), u32be(0), text.toByteArray(Charsets.UTF_8)))

/** The `©lyr` atom id, whose first byte is 0xA9 rather than a letter. */
internal const val LYRICS_ATOM = "©lyr"

/**
 * A minimal but structurally honest MP4: `ftyp`, then the atoms given.
 *
 * [moovLast] puts the metadata after a large `mdat`, which is what most
 * encoders produce and what forces the reader to skip rather than buffer.
 */
internal fun mp4File(
    ilstChildren: List<ByteArray>,
    moovLast: Boolean = false,
    metaHasVersionFlags: Boolean = true,
    mdatBytes: Int = 0,
): ByteArray {
    val ilst = atom("ilst", *ilstChildren.toTypedArray())
    val metaPayload = if (metaHasVersionFlags) u32be(0) + ilst else ilst
    val moov = atom("moov", atom("udta", atom("meta", metaPayload)))

    val ftyp = atom("ftyp", bytes("M4A ", u32be(0), "M4A mp42isom"))
    val mdat = atom("mdat", ByteArray(mdatBytes))

    return if (moovLast) bytes(ftyp, mdat, moov) else bytes(ftyp, moov, mdat)
}

/** An iTunes freeform `----` item: vendor, field name, then the value. */
internal fun freeformAtom(field: String, text: String): ByteArray = atom(
    "----",
    atom("mean", bytes(u32be(0), "com.apple.iTunes")),
    atom("name", bytes(u32be(0), field)),
    dataAtom(text),
)

// --- WAVE and AIFF -----------------------------------------------------------

/** One IFF chunk, padded to an even length as both formats require. */
internal fun iffChunk(id: String, payload: ByteArray, littleEndian: Boolean): ByteArray {
    val size = if (littleEndian) u32le(payload.size) else u32be(payload.size)
    val pad = if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
    return bytes(id, size, payload, pad)
}

internal fun waveFile(vararg chunks: ByteArray): ByteArray {
    val body = chunks.fold(ByteArray(0), ByteArray::plus)
    return bytes("RIFF", u32le(body.size + 4), "WAVE", body)
}

internal fun aiffFile(vararg chunks: ByteArray): ByteArray {
    val body = chunks.fold(ByteArray(0), ByteArray::plus)
    return bytes("FORM", u32be(body.size + 4), "AIFF", body)
}

internal val VORBIS_COMMENT_HEADER = byteArrayOf(3) + "vorbis".toByteArray(Charsets.US_ASCII)
internal val OPUS_TAGS_HEADER = "OpusTags".toByteArray(Charsets.US_ASCII)
internal val VORBIS_IDENTIFICATION = byteArrayOf(1) + "vorbis".toByteArray(Charsets.US_ASCII)
