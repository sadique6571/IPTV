package com.sadique.iptv

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.ceil

internal data class LiveCachedSegment(
    val sequence: Long,
    val discontinuity: Int,
    val file: File,
    val initialization: File?,
    val durationUs: Long,
    val size: Long,
    val programTimeUs: Long? = null,
    val audio: File? = null,
    val audioInitialization: File? = null,
    val audioDurationUs: Long = 0L,
    val audioProgramTimeUs: Long? = null,
)

/** Retains enough extra media for player refresh jitter at the one-hour boundary. */
internal class LiveBufferWindow(
    private val maxDurationUs: Long = 3_630_000_000L,
    private val maxBytes: Long = 6L * 1024 * 1024 * 1024,
) {
    val segments = ArrayDeque<LiveCachedSegment>()
    private var bytes = 0L
    var durationUs = 0L
        private set

    fun append(segment: LiveCachedSegment, retire: (File) -> Unit) {
        segments.addLast(segment)
        bytes += segment.size
        durationUs += segment.durationUs
        while (segments.size > 1 && (durationUs - segments.first.durationUs >= maxDurationUs || bytes > maxBytes)) {
            val oldest = segments.removeFirst()
            durationUs -= oldest.durationUs
            bytes -= oldest.size
            retire(oldest.file)
            oldest.audio?.let(retire)
        }
    }
}

/** Records an unencrypted HLS rendition and its selected audio into app-private rolling playlists. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class LiveTimeShift private constructor(
    private val directory: File,
    private val channelUrl: String,
    private val snapshot: HlsMediaPlaylist,
    private val multivariant: HlsMultivariantPlaylist,
    private val audioLanguage: String?,
    private var audioUri: Uri?,
    private val pausedAtUs: Long,
    headers: Map<String, String>,
    private val onReady: (Uri, Long) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val videoPlaylist = File(directory, "video.m3u8")
    private val audioPlaylist = File(directory, "audio.m3u8")
    private val entryPlaylist = File(directory, "buffer.m3u8")
    private val http = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(5_000).setReadTimeoutMs(8_000)
        .setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(headers)
    private val worker = Executors.newSingleThreadExecutor()
    private val window = LiveBufferWindow()
    private val segments get() = window.segments
    private val oldFiles = ArrayDeque<Pair<File, Long>>()
    private val maps = mutableMapOf<Triple<String, Long, Long>, File>()
    private var oldBytes = 0L
    private var upstreamSequence = -1L
    private var sequence = 0L
    private var upstreamDiscontinuity: Int? = null
    private var discontinuity = 0
    private var lastTargetSeconds = 1L
    private var lastAudioTargetSeconds = 1L
    private var ready = false
    private var task: Future<*>? = null
    @Volatile private var closed = false

    private fun start() {
        task = worker.submit {
            try {
                val now = System.currentTimeMillis()
                directory.parentFile?.listFiles()?.filter { it != directory && now - it.lastModified() > 2 * 60 * 60 * 1000L }
                    ?.forEach { it.deleteRecursively() }
                record()
            } finally {
                if (closed) directory.deleteRecursively()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        task?.cancel(true)
        worker.execute { directory.deleteRecursively() }
        worker.shutdown()
    }

    private fun record() {
        val index = snapshot.segments.indexOfLast { it.relativeStartTimeUs <= pausedAtUs }.coerceAtLeast(0)
        val anchor = snapshot.segments[index]
        val anchorSequence = snapshot.mediaSequence + index
        val offsetUs = (pausedAtUs - anchor.relativeStartTimeUs).coerceIn(0, (anchor.durationUs - 1).coerceAtLeast(0))
        var source = snapshot
        var audioSource: HlsMediaPlaylist? = null
        var minimumSequence = anchorSequence
        var readFailures = 0
        var fetchFailures = 0
        var rewindCount = 0
        while (!closed) {
            try {
                val audioAddress = audioUri
                if (audioAddress != null && audioSource == null) {
                    audioSource = readPlaylist(audioAddress, null) as? HlsMediaPlaylist
                        ?: throw IOException("Expected an HLS audio playlist.")
                }
                for ((i, chunk) in source.segments.withIndex()) {
                    val upstream = source.mediaSequence + i
                    if (closed) return
                    if (upstream < minimumSequence || upstream <= upstreamSequence) continue
                    if (chunk.hasGapTag) continue
                    val audio = audioSource?.let { audioSegment(it, source, chunk, upstream) }
                    if (audioAddress != null && audio == null) {
                        val lastAudio = audioSource?.let { it.mediaSequence + it.segments.lastIndex } ?: break
                        if (upstream > lastAudio && audioSource?.hasProgramDateTime != true) break
                        continue
                    }
                    if (!supports(chunk) || audio != null && !supports(audio)) {
                        onStatus("This feed no longer supports time-shift. Select Go Live.")
                        return
                    }
                    saveSegment(source, chunk, upstream, audioSource, audio)
                    readFailures = 0
                    publish(source.hasEndTag && i == source.segments.lastIndex)
                    if (!ready) {
                        ready = true
                        if (upstream != anchorSequence) onStatus("Pause point unavailable. Using earliest saved video.")
                        onReady(Uri.fromFile(if (audioUri == null) videoPlaylist else entryPlaylist),
                            if (upstream == anchorSequence) offsetUs / 1_000 else 0L)
                    }
                }
                if (source.hasEndTag) {
                    if (ready) publish(ended = true)
                    return
                }
            } catch (_: IOException) {
                readFailures++
                if (readFailures == 3) onStatus(if (ready) {
                    "Live feed interrupted. Saved video is still available."
                } else {
                    "Time-shift could not start. Select Go Live to reconnect."
                })
            }
            try {
                val delay = (source.targetDurationUs / 2_000).coerceIn(1_000L, 8_000L)
                Thread.sleep(delay)
                if (closed) return
                if (fetchFailures >= 3 || readFailures >= 3) {
                    source = refreshRendition(source)
                    audioSource = null
                } else {
                    source = fetchPlaylist(source)
                    audioSource = audioSource?.let(::fetchPlaylist)
                }
                fetchFailures = 0
                if (upstreamSequence >= 0 && source.mediaSequence + source.segments.lastIndex < upstreamSequence) {
                    rewindCount++
                    if (rewindCount >= 3) {
                        discontinuity++
                        upstreamDiscontinuity = null
                        upstreamSequence = -1L
                        minimumSequence = source.mediaSequence
                        rewindCount = 0
                        onStatus("The broadcaster restarted. Saved video is still available.")
                    }
                } else rewindCount = 0
            } catch (_: IOException) {
                fetchFailures++
                if (fetchFailures == 3) onStatus("Live feed interrupted. Retrying the buffer.")
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun fetchPlaylist(previous: HlsMediaPlaylist): HlsMediaPlaylist {
        val url = Uri.parse(previous.baseUri)
        val query = url.encodedQuery?.split('&')?.filterNot { it.startsWith("_HLS_") }
            ?.joinToString("&")?.ifEmpty { null }
        return readPlaylist(url.buildUpon().encodedQuery(query).build(), previous) as? HlsMediaPlaylist
            ?: throw IOException("Expected an HLS media playlist.")
    }

    private fun refreshRendition(previous: HlsMediaPlaylist): HlsMediaPlaylist {
        val fresh = readPlaylist(Uri.parse(channelUrl), null)
        if (fresh is HlsMediaPlaylist && audioUri == null) return fresh
        val master = fresh as? HlsMultivariantPlaylist ?: throw IOException("No HLS rendition available.")
        val originalPath = Uri.parse(snapshot.baseUri).encodedPath
        val original = multivariant.variants.firstOrNull { it.url.encodedPath == originalPath }
        val variant = master.variants.firstOrNull { it.url.encodedPath == originalPath }
            ?: master.variants.firstOrNull { original != null && it.format.height == original.format.height &&
                it.format.bitrate == original.format.bitrate }
            ?: master.variants.singleOrNull()
            ?: throw IOException("The buffered rendition is unavailable.")
        val refreshedAudio = chooseAudio(master, variant.audioGroupId, audioLanguage)
        if ((audioUri == null) != (refreshedAudio == null)) throw IOException("The rendition changed its audio layout.")
        audioUri = refreshedAudio
        return readPlaylist(variant.url, previous) as? HlsMediaPlaylist
            ?: throw IOException("Expected a refreshed HLS media playlist.")
    }

    private fun readPlaylist(uri: Uri, previous: HlsMediaPlaylist?): androidx.media3.exoplayer.hls.playlist.HlsPlaylist {
        if (uri.scheme != "http" && uri.scheme != "https") throw IOException("Invalid playlist URL.")
        val source = http.createDataSource()
        return DataSourceInputStream(source, DataSpec(uri)).use { input ->
            input.open()
            HlsPlaylistParser(multivariant, previous)
                .parse(source.uri ?: uri, input)
        }
    }

    private fun saveSegment(
        source: HlsMediaPlaylist, segment: HlsMediaPlaylist.Segment, upstream: Long,
        audioSource: HlsMediaPlaylist?, audio: HlsMediaPlaylist.Segment?,
    ) {
        val mark = source.discontinuitySequence + segment.relativeDiscontinuitySequence
        if (upstreamDiscontinuity != null && (upstreamDiscontinuity != mark || upstream != upstreamSequence + 1)) {
            discontinuity++
        }
        val initialization = saveInitialization(source, segment, "video")
        val file = segmentFile("video", segment)
        saveFile(UriUtil.resolve(source.baseUri, segment.url), segment, file)
        val audioFile = if (audio != null) segmentFile("audio", audio) else null
        val audioInitialization = try {
            if (audio != null && audioSource != null && audioFile != null) {
                val init = saveInitialization(audioSource, audio, "audio")
                saveFile(UriUtil.resolve(audioSource.baseUri, audio.url), audio, audioFile)
                init
            } else null
        } catch (error: IOException) {
            file.delete()
            throw error
        }
        val size = file.length() + (initialization?.length() ?: 0L) +
            (audioFile?.length() ?: 0L) + (audioInitialization?.length() ?: 0L)
        window.append(LiveCachedSegment(
            sequence++, discontinuity, file, initialization, segment.durationUs, size,
            timestamp(source, segment), audioFile, audioInitialization, audio?.durationUs ?: 0L,
            if (audioSource != null && audio != null) timestamp(audioSource, audio) else null,
        ), ::retire)
        upstreamDiscontinuity = mark
        upstreamSequence = upstream
        val usedMaps = segments.flatMap { listOfNotNull(it.initialization, it.audioInitialization) }.toSet()
        val unused = maps.values.filterNot { it in usedMaps }
        unused.forEach(::retire)
        maps.entries.removeAll { it.value in unused }
        val cutoff = android.os.SystemClock.elapsedRealtime() - RETIRE_MS
        while (oldFiles.isNotEmpty() && (oldFiles.first.second <= cutoff || oldBytes > MAX_RETIRED_BYTES)) {
            val old = oldFiles.removeFirst().first
            oldBytes -= old.length()
            old.delete()
        }
    }

    private fun saveInitialization(source: HlsMediaPlaylist, segment: HlsMediaPlaylist.Segment, track: String): File? {
        val init = segment.initializationSegment ?: return null
        val url = UriUtil.resolve(source.baseUri, init.url)
        val key = Triple(url, init.byteRangeOffset, init.byteRangeLength)
        return maps.getOrPut(key) {
            val target = File(directory, "init-$track-$sequence.mp4")
            saveFile(url, init, target)
            target
        }
    }

    private fun segmentFile(track: String, segment: HlsMediaPlaylist.Segment): File {
        val extension = Uri.parse(segment.url).lastPathSegment.orEmpty().substringAfterLast('.', "")
            .lowercase().takeIf { it in MEDIA_EXTENSIONS }
            ?: if (segment.initializationSegment == null) "ts" else "m4s"
        return File(directory, "$track-$sequence.$extension")
    }

    private fun retire(file: File) {
        oldBytes += file.length()
        oldFiles.addLast(file to android.os.SystemClock.elapsedRealtime())
    }

    private fun saveFile(url: String, segment: HlsMediaPlaylist.Segment, file: File) {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") throw IOException("Invalid media URL.")
        val spec = DataSpec.Builder().setUri(uri).setPosition(segment.byteRangeOffset)
            .setLength(segment.byteRangeLength).build()
        try {
            DataSourceInputStream(http.createDataSource(), spec).use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var written = 0L
                    while (!closed) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        written += count
                        if (written > MAX_CHUNK_BYTES || directory.usableSpace < MIN_FREE_BYTES) {
                            throw IOException("Not enough storage for the live buffer.")
                        }
                        output.write(buffer, 0, count)
                    }
                    if (closed || written == 0L) throw IOException("Incomplete media segment.")
                }
            }
        } catch (error: IOException) {
            file.delete()
            throw error
        }
    }

    private fun publish(ended: Boolean) {
        if (audioUri != null) publishPlaylist(audioPlaylist, audio = true, ended = ended)
        publishPlaylist(videoPlaylist, audio = false, ended = ended)
        if (audioUri != null && !entryPlaylist.exists()) {
            val bitrate = multivariant.variants.firstOrNull {
                it.url.encodedPath == Uri.parse(snapshot.baseUri).encodedPath
            }?.format?.bitrate?.takeIf { it > 0 } ?: 2_000_000
            entryPlaylist.writeText(
                "#EXTM3U\n#EXT-X-VERSION:7\n" +
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=$bitrate,AUDIO=\"audio\"\nvideo.m3u8\n",
            )
        }
    }

    private fun publishPlaylist(target: File, audio: Boolean, ended: Boolean) {
        val first = segments.first
        val newestDurationUs = if (audio) segments.last.audioDurationUs else segments.last.durationUs
        val targetSeconds = ceil(newestDurationUs / 1_000_000.0).toLong()
        if (audio) lastAudioTargetSeconds = maxOf(lastAudioTargetSeconds, targetSeconds)
        else lastTargetSeconds = maxOf(lastTargetSeconds, targetSeconds)
        val part = File(directory, "${target.name}.tmp")
        part.bufferedWriter().use { out ->
            out.appendLine("#EXTM3U").appendLine("#EXT-X-VERSION:7")
            out.appendLine("#EXT-X-TARGETDURATION:${if (audio) lastAudioTargetSeconds else lastTargetSeconds}")
            out.appendLine("#EXT-X-MEDIA-SEQUENCE:${first.sequence}")
            out.appendLine("#EXT-X-DISCONTINUITY-SEQUENCE:${first.discontinuity}")
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            var lastDiscontinuity = first.discontinuity
            var lastMap: File? = null
            for (segment in segments) {
                if (segment.discontinuity != lastDiscontinuity) out.appendLine("#EXT-X-DISCONTINUITY")
                val initialization = if (audio) segment.audioInitialization else segment.initialization
                if (initialization != lastMap && initialization != null) {
                    out.appendLine("#EXT-X-MAP:URI=\"${initialization.name}\"")
                }
                if (segment === first || segment.discontinuity != lastDiscontinuity) {
                    val time = if (audio) segment.audioProgramTimeUs else segment.programTimeUs
                    if (time != null) out.appendLine("#EXT-X-PROGRAM-DATE-TIME:${dateFormat.format(java.util.Date(time / 1_000))}")
                }
                val file = if (audio) segment.audio ?: throw IOException("Missing audio fragment.") else segment.file
                val duration = if (audio) segment.audioDurationUs else segment.durationUs
                out.appendLine("#EXTINF:${duration / 1_000_000.0},").appendLine(file.name)
                lastDiscontinuity = segment.discontinuity
                lastMap = initialization
            }
            if (ended) out.appendLine("#EXT-X-ENDLIST")
        }
        if (!part.renameTo(target)) throw IOException("Could not publish the live buffer.")
    }

    companion object {
        private const val MIN_FREE_BYTES = 128L * 1024 * 1024
        private const val MAX_CHUNK_BYTES = 64L * 1024 * 1024
        private const val MAX_RETIRED_BYTES = 128L * 1024 * 1024
        private const val RETIRE_MS = 45_000L
        private val MEDIA_EXTENSIONS = setOf("aac", "ac3", "ec3", "mp3", "m4s", "mp4", "ts")

        private fun timestamp(playlist: HlsMediaPlaylist, segment: HlsMediaPlaylist.Segment): Long? =
            if (playlist.hasProgramDateTime) playlist.startTimeUs + segment.relativeStartTimeUs else null

        private fun audioSegment(
            audio: HlsMediaPlaylist, video: HlsMediaPlaylist, chunk: HlsMediaPlaylist.Segment, sequence: Long,
        ): HlsMediaPlaylist.Segment? {
            val videoTime = timestamp(video, chunk)
            if (videoTime != null && audio.hasProgramDateTime) {
                val closest = audio.segments.minByOrNull { kotlin.math.abs(audio.startTimeUs + it.relativeStartTimeUs - videoTime) }
                    ?: return null
                return closest.takeIf {
                    !it.hasGapTag && kotlin.math.abs(audio.startTimeUs + it.relativeStartTimeUs - videoTime) <
                        minOf(chunk.durationUs, it.durationUs) / 2
                }
            }
            val index = sequence - audio.mediaSequence
            return if (index in 0 until audio.segments.size.toLong()) {
                audio.segments[index.toInt()].takeUnless { it.hasGapTag }
            } else null
        }

        private fun chooseAudio(master: HlsMultivariantPlaylist, group: String?, language: String?): Uri? {
            val choices = master.audios.filter { it.url != null && (group == null || it.groupId == group) }
            return (choices.firstOrNull { language != null && language == it.format.language }
                ?: choices.firstOrNull { it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }
                ?: choices.firstOrNull())?.url
        }

        private fun supports(segment: HlsMediaPlaylist.Segment): Boolean =
            segment.fullSegmentEncryptionKeyUri == null && segment.drmInitData == null &&
                segment.initializationSegment?.fullSegmentEncryptionKeyUri == null

        fun begin(
            context: Context, channelUrl: String, manifest: HlsManifest, positionMs: Long, headers: Map<String, String>,
            audioLanguage: String?,
            onReady: (Uri, Long) -> Unit, onStatus: (String) -> Unit,
        ): LiveTimeShift? {
            val snapshot = manifest.mediaPlaylist
            if (snapshot.hasEndTag || snapshot.segments.isEmpty() || !snapshot.segments.all(::supports)) return null
            val master = manifest.multivariantPlaylist
            val originalPath = Uri.parse(snapshot.baseUri).encodedPath
            val variant = master.variants.firstOrNull { it.url.encodedPath == originalPath }
            val audioUri = if (variant == null || variant.audioGroupId != null) {
                chooseAudio(master, variant?.audioGroupId, audioLanguage)
            } else null
            val root = File(context.cacheDir, "live-timeshift")
            val directory = File(root, UUID.randomUUID().toString())
            if (!directory.mkdirs()) return null
            return LiveTimeShift(directory, channelUrl, snapshot, master, audioLanguage, audioUri,
                positionMs * 1_000, headers, onReady, onStatus)
                .also { it.start() }
        }
    }
}
