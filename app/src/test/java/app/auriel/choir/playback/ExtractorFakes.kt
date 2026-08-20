// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream

/**
 * A file in memory, standing in for the input an extractor is given.
 *
 * Media3 ships a fake of its own in `media3-test-utils`, which is not depended
 * on here: it brings JUnit 4 and a chain of test artifacts along with it, and
 * what an extractor actually needs from its input is a hundred lines of array
 * arithmetic. Written out, it is also the one place the peek/read distinction —
 * where every extractor bug of this kind lives — can be reasoned about
 * directly.
 */
@UnstableApi
class FakeExtractorInput(private val data: ByteArray) : ExtractorInput {

    private var readPosition = 0
    private var peekPosition = 0

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (readPosition >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - readPosition)
        System.arraycopy(data, readPosition, target, offset, count)
        readPosition += count
        peekPosition = readPosition
        return count
    }

    override fun readFully(
        target: ByteArray,
        offset: Int,
        length: Int,
        allowEndOfInput: Boolean,
    ): Boolean {
        if (readPosition + length > data.size) {
            check(allowEndOfInput) { "unexpected end of input" }
            return false
        }
        System.arraycopy(data, readPosition, target, offset, length)
        readPosition += length
        peekPosition = readPosition
        return true
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        readFully(target, offset, length, /* allowEndOfInput= */ false)
    }

    override fun skip(length: Int): Int {
        if (readPosition >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - readPosition)
        readPosition += count
        peekPosition = readPosition
        return count
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        if (readPosition + length > data.size) {
            check(allowEndOfInput) { "unexpected end of input" }
            return false
        }
        readPosition += length
        peekPosition = readPosition
        return true
    }

    override fun skipFully(length: Int) {
        skipFully(length, /* allowEndOfInput= */ false)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        if (peekPosition >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - peekPosition)
        System.arraycopy(data, peekPosition, target, offset, count)
        peekPosition += count
        return count
    }

    override fun peekFully(
        target: ByteArray,
        offset: Int,
        length: Int,
        allowEndOfInput: Boolean,
    ): Boolean {
        if (peekPosition + length > data.size) {
            check(allowEndOfInput) { "unexpected end of input while peeking" }
            return false
        }
        System.arraycopy(data, peekPosition, target, offset, length)
        peekPosition += length
        return true
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        peekFully(target, offset, length, /* allowEndOfInput= */ false)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        if (peekPosition + length > data.size) {
            check(allowEndOfInput) { "unexpected end of input while peeking" }
            return false
        }
        peekPosition += length
        return true
    }

    override fun advancePeekPosition(length: Int) {
        advancePeekPosition(length, /* allowEndOfInput= */ false)
    }

    override fun resetPeekPosition() {
        peekPosition = readPosition
    }

    override fun getPeekPosition(): Long = peekPosition.toLong()

    override fun getPosition(): Long = readPosition.toLong()

    override fun getLength(): Long = data.size.toLong()

    /** Rewinds and rethrows, which is what the real input does with a retry. */
    override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
        readPosition = position.toInt()
        peekPosition = readPosition
        throw e
    }

    /** Places the reader as a seek would, so the resync path can be exercised. */
    fun seekTo(position: Long) {
        readPosition = position.toInt()
        peekPosition = readPosition
    }
}

/** One sample, as it reached the output. */
data class WrittenSample(
    val timeUs: Long,
    val flags: Int,
    val bytes: ByteArray,
) {
    val isKeyFrame: Boolean get() = flags and C.BUFFER_FLAG_KEY_FRAME != 0

    // Generated equals/hashCode compare the array by identity, which for a
    // value type holding bytes is never what a test means.
    override fun equals(other: Any?): Boolean =
        other is WrittenSample && timeUs == other.timeUs && flags == other.flags &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = (timeUs.hashCode() * 31 + flags) * 31 + bytes.contentHashCode()
}

/** Collects what an extractor wrote, so a test can read it back. */
@UnstableApi
class FakeTrackOutput : TrackOutput {

    var format: Format? = null
        private set

    val samples = mutableListOf<WrittenSample>()

    private val pending = ByteArrayOutputStream()

    override fun format(format: Format) {
        this.format = format
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        val buffer = ByteArray(length)
        val read = input.read(buffer, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            check(allowEndOfInput) { "unexpected end of input" }
            return C.RESULT_END_OF_INPUT
        }
        pending.write(buffer, 0, read)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        val buffer = ByteArray(length)
        data.readBytes(buffer, 0, length)
        pending.write(buffer, 0, length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        val written = pending.toByteArray()
        pending.reset()
        // `offset` counts back from the end of what was written, which is how
        // an extractor says "the sample ended here, this much of it ago".
        val end = written.size - offset
        samples += WrittenSample(timeUs, flags, written.copyOfRange(end - size, end))
    }
}

/** Collects the track and seek map an extractor publishes. */
@UnstableApi
class FakeExtractorOutput : ExtractorOutput {

    val tracks = mutableMapOf<Int, FakeTrackOutput>()

    var seekMap: SeekMap? = null
        private set

    var tracksEnded = false
        private set

    /** The one track an audio extractor publishes. */
    val track: FakeTrackOutput get() = tracks.values.first()

    override fun track(id: Int, type: Int): TrackOutput =
        tracks.getOrPut(id) { FakeTrackOutput() }

    override fun endTracks() {
        tracksEnded = true
    }

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }
}

/**
 * Runs an extractor to the end of its input, the way `ProgressiveMediaPeriod`
 * would, and hands back everything it produced.
 */
@UnstableApi
fun extractAll(extractor: Extractor, input: FakeExtractorInput): FakeExtractorOutput {
    val output = FakeExtractorOutput()
    extractor.init(output)

    val seekPosition = PositionHolder()
    var guard = 0
    while (extractor.read(input, seekPosition) != Extractor.RESULT_END_OF_INPUT) {
        check(guard++ < MAX_READS) { "extractor did not finish after $MAX_READS reads" }
    }
    return output
}

private const val MAX_READS = 100_000
