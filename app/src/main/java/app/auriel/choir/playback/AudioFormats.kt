// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

/**
 * What Choir knows about audio file formats, and which of them it can play.
 *
 * Playing a file takes two separate things, and it is worth keeping them apart
 * because they fail independently:
 *
 *  - a **demuxer**, to find the audio packets inside the container, and
 *  - a **decoder**, to turn those packets into samples.
 *
 * Media3 brings demuxers for the containers Android cares about and no others,
 * so a WavPack file is unplayable even on a build with every decoder compiled
 * in — nothing can open the container to reach the packets. Conflating the two
 * is how a player ends up promising APE support and then silently skipping the
 * track, so [Playability] reports which half is missing.
 */
object AudioFormats {

    /** Whether anything available can open the container and find the audio. */
    enum class Demuxer {
        /** Media3 ships an extractor for this container. */
        BUNDLED,

        /** Choir wrote one, because Media3 does not have it. */
        CHOIR,

        /** No extractor exists; the file cannot be opened at all. */
        MISSING,
    }

    /** Whether anything on the device can decode the packets once found. */
    enum class Codec {
        /** The Android CDD requires every device to decode this. */
        PLATFORM,

        /** Some devices ship a decoder, many do not. Try it and see. */
        DEVICE_DEPENDENT,

        /** Only Choir's FFmpeg decoder handles this. */
        FFMPEG,
    }

    /**
     * The answer to "will this play?", which is what the rest of the app and
     * ultimately the user actually want to know.
     */
    enum class Playability {
        /** Guaranteed by the platform. */
        NATIVE,

        /** The platform may or may not have the decoder; FFmpeg covers it if not. */
        LIKELY,

        /** Plays only once the FFmpeg decoder is present. */
        NEEDS_DECODER,

        /** Media3 cannot open the container, with or without FFmpeg. */
        NEEDS_DEMUXER,

        /** Not a format Choir has an entry for. Worth trying anyway. */
        UNKNOWN,
    }

    /**
     * One format Choir recognises.
     *
     * [extensions] and [mimeTypes] are both matched because MediaStore is
     * inconsistent about which it gets right: a `.wv` arrives as
     * `application/octet-stream` with a usable extension, while some downloads
     * arrive with a correct MIME type and no extension worth reading.
     */
    data class Format(
        val label: String,
        val extensions: Set<String>,
        val mimeTypes: Set<String>,
        val demuxer: Demuxer,
        val codec: Codec,
        /**
         * Whether Android's *media scanner* understands the file — which is a
         * different question from whether Choir can play it, and was worth
         * separating once Choir started supplying demuxers of its own.
         *
         * The scanner writes a row with no duration for anything it cannot
         * parse, so this decides whether MediaStore's metadata can be trusted.
         * Every value here was measured on a device rather than assumed.
         */
        val scannerReads: Boolean = true,
        /**
         * Set where the extension does not determine the codec, so callers do
         * not present a guess as a fact.
         */
        val note: String? = null,
    ) {
        val playability: Playability
            get() = when {
                demuxer == Demuxer.MISSING -> Playability.NEEDS_DEMUXER
                codec == Codec.FFMPEG -> Playability.NEEDS_DECODER
                codec == Codec.DEVICE_DEPENDENT -> Playability.LIKELY
                else -> Playability.NATIVE
            }
    }

    /**
     * The formats table, ordered roughly by how likely you are to own one.
     *
     * Demuxer entries follow the extractor list Media3 1.5 names in its own
     * `UnrecognizedInputFormatException`, read off a failing device rather than
     * off documentation: Flv, Flac, Wav, FragmentedMp4, Mp4, Amr, Ps, Ogg, Ts,
     * Matroska, Adts, Ac3, Ac4, Mp3, Avi, and the image readers. Everything
     * absent from that list is [Demuxer.MISSING], which is most of the
     * audiophile formats.
     */
    val all: List<Format> = listOf(
        Format(
            label = "MP3",
            extensions = setOf("mp3"),
            mimeTypes = setOf("audio/mpeg", "audio/mp3", "audio/x-mpeg"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.PLATFORM,
        ),
        Format(
            label = "AAC",
            extensions = setOf("m4a", "m4b", "mp4", "aac", "3gp", "3ga"),
            mimeTypes = setOf("audio/mp4", "audio/aac", "audio/mp4a-latm", "audio/aac-adts"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.PLATFORM,
            // An .m4a is a container, not a codec: ALAC and AAC share it, and
            // which one is inside is only knowable after opening the file.
            note = "ALAC inside an .m4a needs the FFmpeg decoder",
        ),
        Format(
            label = "FLAC",
            extensions = setOf("flac"),
            mimeTypes = setOf("audio/flac", "audio/x-flac"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.PLATFORM,
        ),
        Format(
            label = "Vorbis",
            extensions = setOf("ogg", "oga"),
            mimeTypes = setOf("audio/ogg", "audio/vorbis", "application/ogg"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.PLATFORM,
        ),
        Format(
            label = "Opus",
            extensions = setOf("opus"),
            mimeTypes = setOf("audio/opus"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.PLATFORM,
        ),
        Format(
            label = "WAVE",
            extensions = setOf("wav", "wave"),
            mimeTypes = setOf("audio/wav", "audio/x-wav", "audio/vnd.wave"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.PLATFORM,
        ),
        Format(
            label = "Matroska",
            extensions = setOf("mka", "mkv", "webm"),
            mimeTypes = setOf("audio/x-matroska", "video/x-matroska", "audio/webm"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.DEVICE_DEPENDENT,
            // Measured: the scanner filed an .mka with a duration of zero.
            scannerReads = false,
            // Opening the container is not the end of it. Media3's Matroska
            // extractor matches a fixed list of codec ids and exposes no track
            // at all for anything outside it — a WavPack stream in an .mka
            // fails with "No valid tracks were found" even with an FFmpeg
            // decoder sitting right there, because the extractor never offers
            // it one. Verified on device.
            note = "plays the codecs Media3's Matroska reader knows, and no others",
        ),
        Format(
            label = "AMR",
            extensions = setOf("amr", "awb"),
            mimeTypes = setOf("audio/amr", "audio/amr-wb", "audio/3gpp"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.PLATFORM,
        ),
        Format(
            label = "MIDI",
            extensions = setOf("mid", "midi", "xmf", "rtttl", "ota", "imy"),
            mimeTypes = setOf("audio/midi", "audio/x-midi", "audio/mobile-xmf"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.PLATFORM,
        ),
        Format(
            label = "Dolby AC-3",
            extensions = setOf("ac3"),
            mimeTypes = setOf("audio/ac3", "audio/x-ac3"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.DEVICE_DEPENDENT,
            // Measured: a raw AC-3 stream is filed with a null duration.
            scannerReads = false,
        ),
        Format(
            label = "Dolby E-AC-3",
            extensions = setOf("ec3", "eac3"),
            mimeTypes = setOf("audio/eac3", "audio/x-eac3"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.DEVICE_DEPENDENT,
            scannerReads = false,
        ),
        Format(
            label = "Dolby AC-4",
            extensions = setOf("ac4"),
            mimeTypes = setOf("audio/ac4"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.DEVICE_DEPENDENT,
            scannerReads = false,
        ),
        Format(
            label = "DTS",
            extensions = setOf("dts", "dtshd"),
            mimeTypes = setOf("audio/vnd.dts", "audio/vnd.dts.hd"),
            demuxer = Demuxer.BUNDLED,
            codec = Codec.DEVICE_DEPENDENT,
            scannerReads = false,
        ),

        // --- Containers Media3 cannot open ------------------------------------

        Format(
            label = "AIFF",
            extensions = setOf("aiff", "aif", "aifc"),
            mimeTypes = setOf("audio/x-aiff", "audio/aiff"),
            // Choir's own, in AiffExtractor. The samples were always ordinary
            // PCM; only the chunked IFF wrapper and the byte order stood in the
            // way, which is what made this the one worth writing by hand.
            demuxer = Demuxer.CHOIR,
            codec = Codec.PLATFORM,
            // Measured: the scanner still files an AIFF with a null duration,
            // whatever Choir can now do with it. The two facts are unrelated.
            scannerReads = false,
            note = "played by Choir's own reader; compressed AIFC is not supported",
        ),
        Format(
            label = "Windows Media Audio",
            extensions = setOf("wma"),
            mimeTypes = setOf("audio/x-ms-wma", "audio/x-ms-asf", "video/x-ms-asf"),
            demuxer = Demuxer.MISSING,
            codec = Codec.FFMPEG,
            scannerReads = false,
        ),
        Format(
            label = "Monkey's Audio",
            extensions = setOf("ape"),
            mimeTypes = setOf("audio/x-ape", "audio/ape", "audio/x-monkeys-audio"),
            demuxer = Demuxer.MISSING,
            codec = Codec.FFMPEG,
            scannerReads = false,
        ),
        Format(
            label = "WavPack",
            extensions = setOf("wv"),
            mimeTypes = setOf("audio/x-wavpack", "audio/wavpack"),
            demuxer = Demuxer.MISSING,
            codec = Codec.FFMPEG,
            scannerReads = false,
        ),
        Format(
            label = "Musepack",
            extensions = setOf("mpc", "mp+", "mpp"),
            mimeTypes = setOf("audio/x-musepack", "audio/musepack"),
            demuxer = Demuxer.MISSING,
            codec = Codec.FFMPEG,
            scannerReads = false,
        ),
        Format(
            label = "True Audio",
            extensions = setOf("tta"),
            mimeTypes = setOf("audio/x-tta"),
            demuxer = Demuxer.MISSING,
            codec = Codec.FFMPEG,
            scannerReads = false,
        ),
        Format(
            label = "DSD",
            extensions = setOf("dsf", "dff"),
            mimeTypes = setOf("audio/x-dsf", "audio/x-dff", "audio/dsd"),
            demuxer = Demuxer.MISSING,
            codec = Codec.FFMPEG,
            scannerReads = false,
        ),
        Format(
            label = "TAK",
            extensions = setOf("tak"),
            mimeTypes = setOf("audio/x-tak"),
            demuxer = Demuxer.MISSING,
            codec = Codec.FFMPEG,
            scannerReads = false,
        ),
        Format(
            label = "Shorten",
            extensions = setOf("shn"),
            mimeTypes = setOf("audio/x-shorten"),
            demuxer = Demuxer.MISSING,
            codec = Codec.FFMPEG,
            scannerReads = false,
        ),
    )

    private val byExtension: Map<String, Format> =
        all.flatMap { format -> format.extensions.map { it to format } }.toMap()

    private val byMimeType: Map<String, Format> =
        all.flatMap { format -> format.mimeTypes.map { it to format } }.toMap()

    /**
     * Identifies a file from whatever MediaStore was willing to say about it.
     *
     * The extension is consulted first. That is the opposite of the usual
     * advice, but MediaStore's own MIME guess *is* derived from the extension
     * for anything it recognises, and where it fails it writes
     * `application/octet-stream` — a value that would otherwise throw away the
     * one real clue the filename still carries.
     *
     * @return the matching format, or `null` when neither clue is recognised.
     */
    fun identify(displayName: String?, mimeType: String?): Format? =
        extensionOf(displayName)?.let(byExtension::get)
            ?: mimeType?.trim()?.lowercase()?.substringBefore(';')?.let(byMimeType::get)

    /** [identify], reduced to the single question most callers are asking. */
    fun playabilityOf(displayName: String?, mimeType: String?): Playability =
        identify(displayName, mimeType)?.playability ?: Playability.UNKNOWN

    /**
     * True when MediaStore's own metadata for a file cannot be trusted to be
     * complete — the scanner writes a row with no duration for anything it
     * could not parse, which is every format in the second half of the table.
     */
    fun isScannerBlind(displayName: String?, mimeType: String?): Boolean =
        identify(displayName, mimeType)?.scannerReads?.not() ?: true

    /** The lowercased extension, without the dot, or `null` if there isn't one. */
    fun extensionOf(displayName: String?): String? {
        val name = displayName?.trim().orEmpty()
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase()
    }
}
