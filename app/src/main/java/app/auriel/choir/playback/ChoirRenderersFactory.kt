// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import app.auriel.choir.core.MusicLog

/**
 * The set of decoders Choir plays through.
 *
 * [DefaultRenderersFactory] already looks for Media3's own FFmpeg extension by
 * reflection, so most of what is needed here is turning that on. The override
 * exists for the case it does not cover: a build carrying the prebuilt
 * community port instead, whose classes sit under a different package and which
 * upstream has no reason to know about.
 *
 * Nothing here fails when there is no FFmpeg at all. That is the ordinary case
 * for a source build, and it must stay a working one.
 */
@UnstableApi
class ChoirRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        // ON rather than PREFER. PREFER would put FFmpeg ahead of the device's
        // own decoders for MP3 and AAC too — software decoding, in a loop, for
        // hours, on a battery. ON keeps the hardware path for everything it can
        // handle and calls FFmpeg only where it cannot.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)

        // A device that advertises a decoder and then fails to configure it
        // should cost one silent retry, not the track.
        setEnableDecoderFallback(true)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) return
        // Upstream found and added its own extension; adding a second decoder
        // for the same formats would only make the choice ambiguous.
        if (out.any { it.javaClass.name.contains(FFMPEG_CLASS_MARKER) }) return

        addAlternateFfmpegRenderer(eventHandler, eventListener, audioSink, out)
    }

    /**
     * Instantiates whichever FFmpeg renderer [FfmpegSupport] found, through the
     * constructor every version of the extension has offered.
     */
    private fun addAlternateFfmpegRenderer(
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        audioSink: AudioSink,
        out: ArrayList<Renderer>,
    ) {
        val rendererClass = FfmpegSupport.rendererClass ?: return

        val renderer = runCatching {
            rendererClass
                .getConstructor(
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java,
                )
                .newInstance(eventHandler, eventListener, audioSink) as Renderer
        }.onFailure {
            MusicLog.w(TAG, "found ${rendererClass.name} but could not construct it", it)
        }.getOrNull() ?: return

        // Appended, so it sits behind the MediaCodec renderers the superclass
        // already added — the same ordering EXTENSION_RENDERER_MODE_ON gives
        // the extension upstream knows about.
        out += renderer
        MusicLog.i(TAG, "added ${rendererClass.name} as a fallback audio renderer")
    }

    private companion object {
        const val TAG = "ChoirRenderersFactory"

        /** Both the official and the community renderer are named this. */
        const val FFMPEG_CLASS_MARKER = "FfmpegAudioRenderer"
    }
}
