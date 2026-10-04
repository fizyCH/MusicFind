package com.musicfind.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Microphone recorder built on AudioRecord.
 * Captures 16-bit mono PCM, exposes live loudness for the visualizer
 * and encodes the result into an AAC (.m4a) file for recognition.
 */
class AudioRecorder(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var pcmFile: File? = null

    @Volatile private var running = false
    @Volatile private var lastAmplitude = 0f

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val isRecording: Boolean get() = running

    /** Normalized 0..1 loudness for the visualizer. */
    fun amplitude(): Float = lastAmplitude

    fun start(): File? {
        if (running) return pcmFile
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, PCM_ENCODING)
        if (minBuffer <= 0) return null
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_MASK)
                        .setEncoding(PCM_ENCODING)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        } catch (_: Throwable) {
            null
        } ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        val file = File(context.cacheDir, "recognize_${System.currentTimeMillis()}.pcm")
        lastAmplitude = 0f
        audioRecord = record
        pcmFile = file
        running = true
        recordThread = Thread {
            val buffer = ShortArray(2048)
            try {
                record.startRecording()
                BufferedOutputStream(FileOutputStream(file)).use { out ->
                    val bytes = ByteArray(buffer.size * 2)
                    val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    while (running) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read <= 0) continue
                        var sumSquares = 0.0
                        for (i in 0 until read) {
                            val sample = buffer[i].toDouble()
                            sumSquares += sample * sample
                        }
                        val rms = sqrt(sumSquares / read) / 32767.0
                        lastAmplitude = sqrt((rms * 5.0).coerceIn(0.0, 1.0)).toFloat()
                        byteBuffer.clear()
                        byteBuffer.asShortBuffer().put(buffer, 0, read)
                        out.write(bytes, 0, read * 2)
                    }
                }
            } catch (_: Throwable) {
                // Recording stopped or mic became unavailable.
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }.apply { start() }
        return file
    }

    /** Stops recording and returns the raw PCM file; encode it with [encodeToM4a]. */
    fun stop(): File? {
        if (!running) return null
        running = false
        val thread = recordThread
        recordThread = null
        audioRecord = null
        try {
            thread?.join(2000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val file = pcmFile
        pcmFile = null
        lastAmplitude = 0f
        if (file == null || !file.exists() || file.length() < MIN_PCM_BYTES) {
            file?.delete()
            return null
        }
        return file
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        private const val PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BIT_RATE = 128_000

        /** ~0.5 second of 16-bit mono PCM. */
        private const val MIN_PCM_BYTES = SAMPLE_RATE

        /** Encodes a raw PCM file into an AAC (.m4a) file, deleting the source on success. */
        fun encodeToM4a(pcm: File): File? {
            val output = File(pcm.parentFile, "${pcm.nameWithoutExtension}.m4a")
            val pcmBytes = try {
                pcm.readBytes()
            } catch (_: Throwable) {
                pcm.delete()
                return null
            }
            if (pcmBytes.size < MIN_PCM_BYTES) {
                pcm.delete()
                return null
            }
            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var muxerStarted = false
            try {
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val info = MediaCodec.BufferInfo()
                var trackIndex = -1
                var offset = 0
                var samplesQueued = 0L
                var eosQueued = false
                var eosReceived = false
                var idleTicks = 0

                while (!eosReceived) {
                    if (!eosQueued) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)!!
                            inBuf.clear()
                            val len = minOf(inBuf.remaining(), pcmBytes.size - offset)
                            if (len > 0) {
                                inBuf.put(pcmBytes, offset, len)
                                offset += len
                            }
                            val ptsUs = samplesQueued * 1_000_000L / SAMPLE_RATE
                            if (offset >= pcmBytes.size) {
                                codec.queueInputBuffer(inIndex, 0, len, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosQueued = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, len, ptsUs, 0)
                                samplesQueued += len / 2
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, if (eosQueued) 10_000 else 0)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                info.size = 0
                            }
                            if (info.size > 0 && muxerStarted) {
                                val outBuf = codec.getOutputBuffer(outIndex)!!
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, outBuf, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                eosReceived = true
                            }
                        }
                    }
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        idleTicks++
                        if (idleTicks > 300) break
                    } else {
                        idleTicks = 0
                    }
                }
            } catch (_: Throwable) {
                runCatching { output.delete() }
                pcm.delete()
                return null
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                if (muxerStarted) runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
            }
            if (!output.exists() || output.length() == 0L) {
                output.delete()
                pcm.delete()
                return null
            }
            pcm.delete()
            return output
        }
    }
}
