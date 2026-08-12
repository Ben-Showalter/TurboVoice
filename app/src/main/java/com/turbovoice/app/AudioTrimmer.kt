package com.turbovoice.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "TurboVoiceAudioTrim"

/**
 * Trims leading/trailing silence from a recorded M4A clip before it goes
 * to Groq — smaller upload, faster transcription round-trip. This is
 * genuinely new, unverified-on-real-hardware media code (decode-analyze
 * with MediaCodec, then extract/remux the original AAC frames for just
 * the speech range via MediaExtractor+MediaMuxer, so no re-encoding or
 * quality loss). Every step is wrapped so that if anything about this
 * goes wrong, it fails safe: trimSilence() returns false and the caller
 * just uses the original, untrimmed recording — voice-to-text should
 * never break because of this.
 */
object AudioTrimmer {

    /** Attempts to write a silence-trimmed copy of [inputFile] to
     *  [outputFile]. Returns true if it actually did (and [outputFile] is
     *  ready to use), false if trimming wasn't possible/worthwhile for
     *  any reason — [inputFile] should be used as-is in that case. */
    fun trimSilence(inputFile: File, outputFile: File): Boolean {
        return try {
            val energyMarks = analyzeEnergy(inputFile)
            if (energyMarks == null) {
                Log.i(TAG, "skipped: couldn't analyze audio")
                return false
            }
            if (energyMarks.isEmpty()) {
                Log.i(TAG, "skipped: no audio data found")
                return false
            }

            val sorted = energyMarks.map { it.second }.sorted()
            val min = sorted.first()
            val max = sorted.last()
            val median = sorted[sorted.size / 2]
            Log.i(TAG, "energy stats: min=$min median=$median max=$max (n=${sorted.size})")
            val threshold = 800.0
            val speechPoints = energyMarks.filter { it.second > threshold }.map { it.first }
            if (speechPoints.isEmpty()) {
                Log.i(TAG, "skipped: no speech confidently detected (whole clip below threshold)")
                return false
            }

            // Group speech points into segments, splitting wherever the
            // gap between them is long enough to be a real pause (not
            // just a normal micro-gap between words) — this is what
            // catches silence in the *middle* of a clip, not just at the
            // very start/end.
            val gapThresholdUs = 800_000L // 800ms
            val paddingUs = 200_000L // cushion so words right at a cut aren't clipped
            val duration = energyMarks.last().first

            val rawSegments = mutableListOf<Pair<Long, Long>>()
            var segStart = speechPoints.first()
            var lastPoint = speechPoints.first()
            for (t in speechPoints.drop(1)) {
                if (t - lastPoint > gapThresholdUs) {
                    rawSegments.add(segStart to lastPoint)
                    segStart = t
                }
                lastPoint = t
            }
            rawSegments.add(segStart to lastPoint)

            // Pad each segment, then merge any that now overlap as a result.
            val padded = rawSegments.map { (s, e) ->
                (s - paddingUs).coerceAtLeast(0) to (e + paddingUs).coerceAtMost(duration)
            }
            val segments = mutableListOf<Pair<Long, Long>>()
            for (seg in padded) {
                val last = segments.lastOrNull()
                if (last != null && seg.first <= last.second) {
                    segments[segments.size - 1] = last.first to maxOf(last.second, seg.second)
                } else {
                    segments.add(seg)
                }
            }

            val totalKept = segments.sumOf { it.second - it.first }
            if (totalKept > duration - 300_000L) {
                Log.i(TAG, "skipped: not enough silence to bother (duration=${duration}us, kept=${totalKept}us)")
                return false
            }

            Log.i(TAG, "trimming: duration=${duration}us -> ${segments.size} segment(s), keeping ${totalKept}us total: $segments")
            extractSegments(inputFile, outputFile, segments)
        } catch (e: Exception) {
            Log.w(TAG, "trimSilence failed, will use original file instead", e)
            false
        }
    }

    /** Decodes the audio (for analysis only — nothing here is written
     *  anywhere) and returns a list of (timestampUs, rmsEnergy) samples
     *  roughly every output buffer's worth of audio. */
    private fun analyzeEnergy(file: File): List<Pair<Long, Double>>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val marks = mutableListOf<Pair<Long, Double>>()
            var inputDone = false
            var outputDone = false
            var loops = 0
            val maxLoops = 200_000 // hard safety cap — never loop forever on a malformed file

            while (!outputDone && loops < maxLoops) {
                loops++
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)
                        val sampleSize = if (inBuffer != null) extractor.readSampleData(inBuffer, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null) {
                            marks.add(bufferInfo.presentationTimeUs to rmsOf(outBuffer, bufferInfo))
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }

            codec.stop()
            codec.release()
            return marks
        } catch (e: Exception) {
            Log.w(TAG, "analyzeEnergy failed", e)
            return null
        } finally {
            extractor.release()
        }
    }

    private fun rmsOf(buffer: ByteBuffer, info: MediaCodec.BufferInfo): Double {
        val dup = buffer.duplicate()
        dup.position(info.offset)
        dup.limit(info.offset + info.size)
        dup.order(ByteOrder.LITTLE_ENDIAN)
        val shorts = dup.asShortBuffer()
        val n = shorts.remaining()
        if (n == 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until n) {
            val s = shorts.get(i).toDouble()
            sumSquares += s * s
        }
        return Math.sqrt(sumSquares / n)
    }

    /** Copies the original (already-encoded) audio samples for each
     *  (start, end) range in [segments] into one continuous output file —
     *  no decode/re-encode, so no quality loss, and the gaps between
     *  segments are simply not written, splicing the kept parts together
     *  with continuous timestamps. */
    private fun extractSegments(inputFile: File, outputFile: File, segments: List<Pair<Long, Long>>): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(inputFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return false
            extractor.selectTrack(trackIndex)

            val newMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = newMuxer
            val outTrack = newMuxer.addTrack(format)
            newMuxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var wroteAny = false
            var outputTimeOffset = 0L // keeps output timestamps continuous across segments

            for ((segStart, segEnd) in segments) {
                extractor.seekTo(segStart, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var segmentBaseInputTime: Long? = null

                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0 || sampleTime > segEnd) break

                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    if (segmentBaseInputTime == null) segmentBaseInputTime = sampleTime

                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = outputTimeOffset + (sampleTime - segmentBaseInputTime)
                    info.flags = extractor.sampleFlags

                    newMuxer.writeSampleData(outTrack, buffer, info)
                    wroteAny = true
                    extractor.advance()
                }

                val base = segmentBaseInputTime
                if (base != null) {
                    outputTimeOffset += (segEnd - base)
                }
            }

            newMuxer.stop()
            wroteAny
        } catch (e: Exception) {
            Log.w(TAG, "extractSegments failed", e)
            false
        } finally {
            try { muxer?.release() } catch (e: Exception) { }
            extractor.release()
        }
    }
}
