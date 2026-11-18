package voice.core.torrent

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.alert_category_t
import org.libtorrent4j.swig.settings_pack
import voice.core.logging.api.Logger
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

sealed class TorrentStreamState {
  data object Idle : TorrentStreamState()
  data object Connecting : TorrentStreamState()
  data object FetchingMetadata : TorrentStreamState()
  data class Buffering(val progress: Float, val peers: Int, val downloadSpeed: Long) : TorrentStreamState()
  data class Ready(
    val filePath: String,
    val streamUrl: String = "",
    val fileIndex: Int = 0,
    val peers: Int = 0,
    val downloadSpeed: Long = 0,
    val uploadSpeed: Long = 0,
  ) : TorrentStreamState()
  data class Seeding(val filePath: String, val peers: Int, val uploadSpeed: Long, val ratio: Float) : TorrentStreamState()
  data class Error(val message: String) : TorrentStreamState()
}

data class TorrentStatus(
  val peers: Int = 0,
  val downloadSpeed: Long = 0,
  val uploadSpeed: Long = 0,
  val progress: Float = 0f,
  val ratio: Float = 0f,
  val state: TorrentStreamState = TorrentStreamState.Idle,
)

@Inject
@SingleIn(AppScope::class)
class TorrentStreamEngine(
  private val context: Context,
) {
  companion object {
    const val BUFFER_PIECES_AHEAD = 16
    const val STREAM_PORT = 19527

    private val AUDIO_EXTENSIONS = listOf(
      ".m4b", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".oga", ".opus", ".wav", ".wma",
    )

    val DEFAULT_TRACKERS = listOf(
      "udp://tracker.torrent.eu.org:451/announce",
      "udp://bittorrent-tracker.encrypt.net:1337/announce",
      "udp://open.stealth.si:80/announce",
      "udp://tracker.opentrackr.org:1337/announce",
      "udp://tracker.dler.org:6969/announce",
      "udp://tracker.openbittorrent.com:6969/announce",
      "udp://tracker.tiny-vps.com:6969/announce",
      "udp://p4p.arenabg.com:1337/announce",
      "http://ipv4announce.sktorrent.eu:6969/announce",
      "http://tracker.bt4g.com:2095/announce",
      "http://tracker.mywaifu.best:6969/announce",
      "udp://bittorrent-tracker.e-n-c-r-y-p-t.net:1337/announce",
      "http://bt.okmp3.ru:2710/announce",
    )

    private const val DHT_BOOTSTRAP_NODES =
      "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.libtorrent.org:25401"
  }

  data class StreamFileInfo(
    val fileIndex: Int,
    val chapterIndex: Int,
    val fileName: String,
    val fileSize: Long,
    val fileOffset: Long,
    val pieceLength: Int,
    val firstPiece: Int,
    val lastPiece: Int,
    val numPieces: Int,
  )

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile
  private var sessionManager: SessionManager? = null
  @Volatile
  private var currentHandle: TorrentHandle? = null
  @Volatile
  private var targetChapterIndex: Int = 0
  @Volatile
  private var targetFileName: String? = null
  @Volatile
  private var activeInfoHash: Sha1Hash? = null
  @Volatile
  private var currentFileInfo: StreamFileInfo? = null

  private var serverSocket: ServerSocket? = null
  var serverPort: Int = 0
    private set

  private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
  private val activeWindowLock = Any()
  private val activeWindowPieces = mutableSetOf<Int>()
  private val torrentLock = Any()

  private val _streamState = MutableStateFlow<TorrentStreamState>(TorrentStreamState.Idle)
  val streamState: StateFlow<TorrentStreamState> = _streamState

  private val _status = MutableStateFlow(TorrentStatus())
  val status: StateFlow<TorrentStatus> = _status

  private val saveDir: File
    get() = File(context.filesDir, "audiobooks").also { it.mkdirs() }

  @Volatile
  private var isStarted = false

  private val startMutex = Mutex()
  private val streamMutex = Mutex()

  private suspend fun ensureStarted() = withContext(Dispatchers.IO) {
    if (isStarted && sessionManager != null) return@withContext
    startMutex.withLock {
      if (isStarted && sessionManager != null) return@withLock
      try {
        val sp = SettingsPack()
        sp.setString(settings_pack.string_types.user_agent.swigValue(), "Voice/1.0 libtorrent")
        sp.setInteger(settings_pack.int_types.alert_mask.swigValue(), alert_category_t.all().to_int())
        sp.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
        sp.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), DHT_BOOTSTRAP_NODES)
        sp.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)
        sp.setInteger(settings_pack.int_types.active_downloads.swigValue(), 10)
        sp.setInteger(settings_pack.int_types.active_limit.swigValue(), 20)

        val sm = SessionManager(false)
        sm.addListener(alertListener)
        synchronized(torrentLock) {
          sm.start(SessionParams(sp))
          sessionManager = sm
        }
        isStarted = true

        startStreamServer()
        Logger.d("TorrentStreamEngine started on port $serverPort")
      } catch (e: Exception) {
        Logger.w(e, "Failed to start torrent engine")
        _streamState.value = TorrentStreamState.Error("Failed to start torrent engine: ${e.message}")
      }
    }
  }

  fun start() {
    if (isStarted && sessionManager != null) return
    scope.launch {
      ensureStarted()
    }
  }

  fun stop() {
    scope.launch {
      try {
        stopStreamServer()
        closeActiveStreamClients()
        synchronized(torrentLock) {
          currentHandle?.let { saveResumeData(it) }
          sessionManager?.stop()
          sessionManager = null
          currentHandle = null
        }
        currentFileInfo = null
        activeInfoHash = null
        isStarted = false
        synchronized(activeWindowLock) { activeWindowPieces.clear() }
        _streamState.value = TorrentStreamState.Idle
        _status.value = TorrentStatus()
      } catch (e: Exception) {
        Logger.w(e, "Failed to stop torrent engine")
      }
    }
  }

  suspend fun streamFromMagnet(
    magnetUri: String,
    chapterIndex: Int = 0,
    fileName: String? = null,
  ): String? {
    ensureStarted()
    val sm = sessionManager ?: run {
      _streamState.value = TorrentStreamState.Error("Engine not started")
      return null
    }
    val infoHash = runCatching {
      AddTorrentParams.parseMagnetUri(magnetUri).infoHashes.best
    }.getOrNull()

    return withContext(Dispatchers.IO) {
      streamMutex.withLock {
        try {
          val previousHash = activeInfoHash
          stopCurrentStream()
          activeInfoHash = infoHash

          if (infoHash != null && previousHash != null && previousHash != infoHash) {
            try {
              synchronized(torrentLock) {
                sm.find(previousHash)?.let { sm.remove(it) }
              }
            } catch (_: Exception) {}
          }

          targetChapterIndex = chapterIndex
          targetFileName = fileName
          _streamState.value = TorrentStreamState.Connecting

          val enrichedMagnet = buildString {
            append(magnetUri)
            for (tr in DEFAULT_TRACKERS) {
              val encoded = java.net.URLEncoder.encode(tr, "UTF-8")
              if (!magnetUri.contains(encoded) && !magnetUri.contains(tr)) {
                append("&tr=").append(encoded)
              }
            }
          }

          val handle = synchronized(torrentLock) {
            sm.download(enrichedMagnet, saveDir, TorrentFlags.DEFAULT_DONT_DOWNLOAD)
            _streamState.value = TorrentStreamState.FetchingMetadata
            infoHash?.let { sm.find(it) }
          }
          if (handle != null && handle.isValid) {
            currentHandle = handle
            if (getTorrentInfoSafely(handle) != null) {
              prioritizeFile(handle, targetChapterIndex, targetFileName)
              checkStreamReadiness(handle)
              updateBufferingIfNeeded(handle)
            }
          }
          val port = if (serverPort > 0) serverPort else STREAM_PORT
          "http://127.0.0.1:$port/stream"
        } catch (e: Exception) {
          Logger.w(e, "Failed to start stream")
          _streamState.value = TorrentStreamState.Error("Failed to start stream: ${e.message}")
          null
        }
      }
    }
  }

  fun stopCurrentStream() {
    val handle = currentHandle
    activeInfoHash = null
    currentFileInfo = null
    currentHandle = null
    closeActiveStreamClients()
    handle?.let { synchronized(torrentLock) { saveResumeData(it) } }
    synchronized(activeWindowLock) { activeWindowPieces.clear() }
    _streamState.value = TorrentStreamState.Idle
  }

  fun setTargetFile(chapterIndex: Int, fileName: String? = null) {
    targetChapterIndex = chapterIndex
    targetFileName = fileName
    val handle = currentHandle ?: activeInfoHash?.let { handleForHash(it) }
    handle?.let {
      prioritizeFile(it, chapterIndex, fileName)
      checkStreamReadiness(it)
    }
  }

  private fun getTorrentInfoSafely(handle: TorrentHandle?): TorrentInfo? {
    if (handle == null || !handle.isValid) return null
    return synchronized(torrentLock) {
      try {
        val status = handle.status()
        if (!status.hasMetadata()) return@synchronized null
        handle.torrentFile()
      } catch (_: Exception) {
        null
      }
    }
  }

  private fun isAudioFile(path: String): Boolean {
    val lower = path.lowercase()
    return AUDIO_EXTENSIONS.any { lower.endsWith(it) }
  }

  private fun resolveFileIndex(ti: TorrentInfo, chapterIndex: Int, fileName: String?): Int? {
    val fs = ti.files()
    val numFiles = ti.numFiles()
    if (numFiles <= 0) return null

    val targetName = fileName
      ?.substringAfterLast('/')
      ?.substringAfterLast('\\')
      ?.trim()
      ?.takeIf { it.isNotEmpty() }

    if (targetName != null) {
      for (i in 0 until numFiles) {
        val path = fs.filePath(i)
        val baseName = path.substringAfterLast('/').substringAfterLast('\\')
        if (baseName.equals(targetName, ignoreCase = true)) return i
      }
      for (i in 0 until numFiles) {
        if (fs.filePath(i).endsWith(targetName, ignoreCase = true)) return i
      }
    }

    val audioIndices = (0 until numFiles).filter { isAudioFile(fs.filePath(it)) }
    val candidates = audioIndices.ifEmpty { (0 until numFiles).toList() }
    return candidates.getOrNull(chapterIndex) ?: candidates.firstOrNull()
  }

  private fun prioritizeFile(handle: TorrentHandle, chapterIndex: Int, fileName: String?) {
    if (!handle.isValid) return
    val ti = getTorrentInfoSafely(handle) ?: return
    val fileIndex = resolveFileIndex(ti, chapterIndex, fileName) ?: return

    val info = synchronized(torrentLock) {
      try {
        val fs = ti.files()
        val numFiles = ti.numFiles()
        if (fileIndex !in 0 until numFiles) return@synchronized null

        val firstPiece = fs.pieceIndexAtFile(fileIndex)
        val lastPiece = fs.lastPieceIndexAtFile(fileIndex)
        val pieceLength = fs.pieceLength()
        val fileSize = fs.fileSize(fileIndex)
        val fileOffset = fs.fileOffset(fileIndex)
        val torrentFileName = fs.filePath(fileIndex)
        val numPieces = ti.numPieces()

        val filePriorities = Array(numFiles) { i ->
          if (i == fileIndex) Priority.TOP_PRIORITY else Priority.IGNORE
        }
        handle.prioritizeFiles(filePriorities)

        val allIgnore = Array(numPieces) { Priority.IGNORE }
        handle.prioritizePieces(allIgnore)

        for (p in firstPiece..lastPiece) {
          handle.piecePriority(p, Priority.DEFAULT)
        }

        handle.piecePriority(firstPiece, Priority.TOP_PRIORITY)
        handle.setPieceDeadline(firstPiece, 1000)
        if (firstPiece + 1 <= lastPiece) {
          handle.piecePriority(firstPiece + 1, Priority.TOP_PRIORITY)
          handle.setPieceDeadline(firstPiece + 1, 2000)
        }
        handle.piecePriority(lastPiece, Priority.TOP_PRIORITY)
        handle.setPieceDeadline(lastPiece, 3000)

        StreamFileInfo(
          fileIndex = fileIndex,
          chapterIndex = chapterIndex,
          fileName = torrentFileName,
          fileSize = fileSize,
          fileOffset = fileOffset,
          pieceLength = pieceLength,
          firstPiece = firstPiece,
          lastPiece = lastPiece,
          numPieces = numPieces,
        )
      } catch (e: Exception) {
        Logger.w(e, "Error prioritizing file in torrent")
        null
      }
    } ?: return

    currentFileInfo = info

    synchronized(activeWindowLock) { activeWindowPieces.clear() }
    updateSlidingWindow(handle, fileIndex, startByte = 0L)
  }

  private fun pieceIndexAtFileOffset(info: StreamFileInfo, filePos: Long): Int {
    val absoluteOffset = info.fileOffset + filePos.coerceAtLeast(0L)
    return (absoluteOffset / info.pieceLength).toInt().coerceIn(info.firstPiece, info.lastPiece)
  }

  fun updateSlidingWindow(handle: TorrentHandle, fileIndex: Int, startByte: Long) {
    if (!handle.isValid) return
    val info = currentFileInfo ?: return
    if (fileIndex != info.fileIndex) return

    val firstPiece = info.firstPiece
    val lastPiece = info.lastPiece

    val targetPiece = pieceIndexAtFileOffset(info, startByte)
    val windowEnd = minOf(targetPiece + BUFFER_PIECES_AHEAD, lastPiece)

    val newWindow = (targetPiece..windowEnd).toSet()
    val pinned = setOf(firstPiece, minOf(firstPiece + 1, lastPiece), lastPiece)

    synchronized(torrentLock) {
      synchronized(activeWindowLock) {
        for (oldPiece in activeWindowPieces) {
          if (oldPiece !in newWindow && oldPiece !in pinned) {
            try {
              handle.piecePriority(oldPiece, Priority.DEFAULT)
              handle.resetPieceDeadline(oldPiece)
            } catch (_: Exception) {}
          }
        }

        for (piece in newWindow) {
          try {
            handle.piecePriority(piece, Priority.TOP_PRIORITY)
            val deadlineMs = 1000 + (piece - targetPiece) * 1000
            handle.setPieceDeadline(piece, deadlineMs)
          } catch (_: Exception) {}
        }

        activeWindowPieces.clear()
        activeWindowPieces.addAll(newWindow)
      }
    }
  }

  private fun hasPiece(handle: TorrentHandle?, pieceIndex: Int): Boolean {
    if (handle == null || !handle.isValid) return false
    return synchronized(torrentLock) {
      try {
        handle.havePiece(pieceIndex)
      } catch (_: Exception) {
        false
      }
    }
  }

  private fun isActiveHandle(handle: TorrentHandle): Boolean {
    val active = activeInfoHash ?: return false
    return synchronized(torrentLock) {
      runCatching { handle.infoHash() == active }.getOrDefault(false)
    }
  }

  private fun checkStreamReadiness(handle: TorrentHandle) {
    if (!handle.isValid || !isActiveHandle(handle)) return
    val info = currentFileInfo ?: return
    val file = File(saveDir, info.fileName)

    val firstPieceReady = hasPiece(handle, info.firstPiece)
    val secondPieceReady = info.firstPiece + 1 > info.lastPiece || hasPiece(handle, info.firstPiece + 1)
    val lastPieceReady = info.firstPiece == info.lastPiece || hasPiece(handle, info.lastPiece)

    if (firstPieceReady && secondPieceReady && lastPieceReady && file.exists()) {
      val status = synchronized(torrentLock) {
        try { handle.status() } catch (_: Exception) { null }
      }
      val peers = status?.numPeers() ?: 0
      val dlSpeed = status?.downloadPayloadRate()?.toLong() ?: 0L
      val ulSpeed = status?.uploadPayloadRate()?.toLong() ?: 0L
      val port = if (serverPort > 0) serverPort else STREAM_PORT
      val streamUrl = "http://127.0.0.1:$port/stream?file=${info.fileIndex}"

      val current = _streamState.value
      if (current !is TorrentStreamState.Ready || current.fileIndex != info.fileIndex || current.streamUrl != streamUrl) {
        _streamState.value = TorrentStreamState.Ready(
          filePath = file.absolutePath,
          streamUrl = streamUrl,
          fileIndex = info.fileIndex,
          peers = peers,
          downloadSpeed = dlSpeed,
          uploadSpeed = ulSpeed,
        )
      }
    }
  }

  private fun updateBufferingIfNeeded(handle: TorrentHandle) {
    if (!handle.isValid || !isActiveHandle(handle)) return
    val current = _streamState.value
    if (current !is TorrentStreamState.Ready && current !is TorrentStreamState.Seeding && current !is TorrentStreamState.Error) {
      val status = synchronized(torrentLock) {
        try { handle.status() } catch (_: Exception) { return }
      }
      _streamState.value = TorrentStreamState.Buffering(
        progress = status.progress(),
        peers = status.numPeers(),
        downloadSpeed = status.downloadPayloadRate().toLong(),
      )
    }
  }

  private fun startStreamServer() {
    if (serverSocket != null && !serverSocket!!.isClosed) return
    val loopback = InetAddress.getByName("127.0.0.1")
    val ss = try {
      ServerSocket(STREAM_PORT, 50, loopback)
    } catch (_: Exception) {
      ServerSocket(0, 50, loopback)
    }
    serverSocket = ss
    serverPort = ss.localPort

    scope.launch(Dispatchers.IO) {
      while (isStarted && !ss.isClosed) {
        try {
          val client = ss.accept()
          activeSockets.add(client)
          scope.launch(Dispatchers.IO) {
            handleClient(client)
          }
        } catch (e: Exception) {
          if (isStarted && !ss.isClosed) {
            Logger.w(e, "Error accepting stream client")
          }
        }
      }
    }
  }

  fun getStreamUrl(fileIndex: Int = 0): String {
    val port = if (serverPort > 0) serverPort else STREAM_PORT
    return "http://127.0.0.1:$port/stream?file=$fileIndex"
  }

  private fun parseRequestedFileIndex(requestLine: String): Int? {
    val uri = requestLine.split(" ").getOrNull(1) ?: return null
    val queryParam = Regex("[?&](?:file|chapter|index)=(\\d+)").find(uri)?.groupValues?.get(1)?.toIntOrNull()
    if (queryParam != null) return queryParam
    return Regex("/stream/(\\d+)").find(uri)?.groupValues?.get(1)?.toIntOrNull()
  }

  private fun stopStreamServer() {
    try {
      serverSocket?.close()
    } catch (_: Exception) {}
    serverSocket = null
    serverPort = 0
  }

  private fun closeActiveStreamClients() {
    val sockets = activeSockets.toList()
    activeSockets.clear()
    for (s in sockets) {
      try { s.close() } catch (_: Exception) {}
    }
  }

  private fun handleClient(socket: Socket) {
    try {
      socket.tcpNoDelay = true
      socket.soTimeout = 60000
      val input = socket.getInputStream()
      val reader = input.bufferedReader(Charsets.UTF_8)

      val requestLine = reader.readLine() ?: return
      var rangeHeader: String? = null
      var line: String? = reader.readLine()
      while (!line.isNullOrEmpty()) {
        if (line.startsWith("Range:", ignoreCase = true)) {
          rangeHeader = line.substringAfter(":").trim()
        }
        line = reader.readLine()
      }

      val requestedIndex = parseRequestedFileIndex(requestLine)

      var metadataWait = 0
      while (currentFileInfo == null && metadataWait < 400 && isStarted && !socket.isClosed) {
        val h = currentHandle ?: activeInfoHash?.let { handleForHash(it) }
        if (h != null && h.isValid && currentFileInfo == null) {
          val ti = getTorrentInfoSafely(h)
          if (ti != null) {
            val reqIdx = requestedIndex ?: targetChapterIndex
            prioritizeFile(h, reqIdx, targetFileName)
          }
        }
        Thread.sleep(100)
        metadataWait++
      }

      val handle = currentHandle ?: activeInfoHash?.let { handleForHash(it) }
      if (requestedIndex != null && handle != null && handle.isValid && requestedIndex != currentFileInfo?.fileIndex) {
        prioritizeFile(handle, requestedIndex, null)
      }

      val info = currentFileInfo
      if (info == null || handle == null || !handle.isValid || !isActiveHandle(handle)) {
        Logger.w("Stream unavailable for request: $requestLine, info=${info != null}, handle=${handle != null}")
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 2\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.flush()
        return
      }

      val fileSize = info.fileSize
      val fileName = info.fileName
      val file = File(saveDir, fileName)

      var startByte = 0L
      var endByte = fileSize - 1
      if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
        val rangeVal = rangeHeader.removePrefix("bytes=").trim()
        val parts = rangeVal.split("-", limit = 2)
        val startPart = parts.getOrNull(0)?.trim().orEmpty()
        val endPart = parts.getOrNull(1)?.trim().orEmpty()
        if (startPart.isEmpty()) {
          val suffixLength = endPart.toLongOrNull() ?: 0L
          startByte = (fileSize - suffixLength).coerceAtLeast(0L)
        } else {
          startByte = startPart.toLongOrNull() ?: 0L
          if (endPart.isNotEmpty()) {
            endByte = endPart.toLongOrNull() ?: endByte
          }
        }
      }

      if (fileSize <= 0 || startByte > fileSize - 1) {
        val out = socket.getOutputStream()
        val response = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
          "Content-Range: bytes */$fileSize\r\n" +
          "Connection: close\r\n\r\n"
        out.write(response.toByteArray(Charsets.UTF_8))
        out.flush()
        return
      }
      startByte = startByte.coerceIn(0L, fileSize - 1)
      endByte = endByte.coerceIn(startByte, fileSize - 1)

      val contentLength = endByte - startByte + 1
      val mimeType = when {
        fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        fileName.endsWith(".m4b", ignoreCase = true) || fileName.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
        fileName.endsWith(".flac", ignoreCase = true) -> "audio/flac"
        fileName.endsWith(".ogg", ignoreCase = true) || fileName.endsWith(".oga", ignoreCase = true) -> "audio/ogg"
        else -> "audio/mpeg"
      }

      val header = buildString {
        append("HTTP/1.1 206 Partial Content\r\n")
        append("Content-Type: $mimeType\r\n")
        append("Accept-Ranges: bytes\r\n")
        append("Content-Range: bytes $startByte-$endByte/$fileSize\r\n")
        append("Content-Length: $contentLength\r\n")
        append("Connection: close\r\n")
        append("\r\n")
      }

      val out = socket.getOutputStream()
      if (requestLine.startsWith("HEAD")) {
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()
        return
      }

      updateSlidingWindow(handle, info.fileIndex, startByte)

      val firstRequestedPiece = pieceIndexAtFileOffset(info, startByte)
      var pieceWait = 0
      while (!hasPiece(handle, firstRequestedPiece) && !socket.isClosed && isStarted && pieceWait < 1200) {
        Thread.sleep(50)
        pieceWait++
      }
      if (!hasPiece(handle, firstRequestedPiece) || socket.isClosed || !isStarted) return

      var fileWait = 0
      while (!file.exists() && fileWait < 50 && isStarted && !socket.isClosed) {
        Thread.sleep(50)
        fileWait++
      }
      if (!file.exists()) return

      out.write(header.toByteArray(Charsets.UTF_8))
      out.flush()

      RandomAccessFile(file, "r").use { raf ->
        val buffer = ByteArray(64 * 1024)
        var currentPos = startByte
        val pieceLength = info.pieceLength

        while (currentPos <= endByte && !socket.isClosed && isStarted && handle.isValid) {
          val pieceIndex = pieceIndexAtFileOffset(info, currentPos)

          updateSlidingWindow(handle, info.fileIndex, currentPos)

          var pieceWait = 0
          while (!hasPiece(handle, pieceIndex) && !socket.isClosed && isStarted && pieceWait < 1500) {
            Thread.sleep(20)
            pieceWait++
          }
          if (!hasPiece(handle, pieceIndex) || socket.isClosed || !isStarted) break

          val absolutePos = info.fileOffset + currentPos
          val pieceStartAbsolute = (absolutePos / pieceLength) * pieceLength
          val pieceEndAbsolute = pieceStartAbsolute + pieceLength
          val chunkEnd = minOf(endByte + 1, pieceEndAbsolute - info.fileOffset)
          var bytesRemaining = (chunkEnd - currentPos).toInt()

          raf.seek(currentPos)
          while (bytesRemaining > 0 && !socket.isClosed) {
            val toRead = minOf(bytesRemaining, buffer.size)
            val read = raf.read(buffer, 0, toRead)
            if (read == -1) break
            out.write(buffer, 0, read)
            bytesRemaining -= read
            currentPos += read
          }
          out.flush()
        }
      }
    } catch (_: SocketException) {
      // Player seeked or closed connection
    } catch (e: Exception) {
      Logger.d("Stream client exception: ${e.message}")
    } finally {
      activeSockets.remove(socket)
      try { socket.close() } catch (_: Exception) {}
    }
  }

  private fun saveResumeData(handle: TorrentHandle) {
    synchronized(torrentLock) {
      try {
        handle.saveResumeData()
      } catch (e: Exception) {
        Logger.w(e, "Failed to save resume data")
      }
    }
  }

  private fun handleForHash(hash: Sha1Hash?): TorrentHandle? {
    val expected = activeInfoHash ?: return null
    if (hash == null || hash != expected) return null
    return synchronized(torrentLock) {
      try {
        sessionManager?.find(hash)?.takeIf { it.isValid }
      } catch (_: Exception) {
        null
      }
    }
  }

  private fun handleForAlert(alert: TorrentAlert<*>): TorrentHandle? {
    val hash = synchronized(torrentLock) {
      try { alert.handle().infoHash() } catch (_: Exception) { null }
    }
    return handleForHash(hash)
  }

  private val alertListener = object : AlertListener {
    override fun types(): IntArray? = null

    override fun alert(alert: Alert<*>?) {
      when (alert) {
        is AddTorrentAlert -> {
          val hash = synchronized(torrentLock) {
            runCatching { alert.params().infoHashes.best }.getOrNull()
              ?: runCatching { alert.handle().infoHash() }.getOrNull()
          }

          if (hash != null && hash != activeInfoHash) return

          if (alert.error().isError) {
            _streamState.value = TorrentStreamState.Error("Failed to add torrent: ${alert.error().message}")
            return
          }

          val handle = handleForHash(hash) ?: return
          currentHandle = handle
          val ti = getTorrentInfoSafely(handle)
          if (ti != null) {
            prioritizeFile(handle, targetChapterIndex, targetFileName)
            checkStreamReadiness(handle)
            updateBufferingIfNeeded(handle)
          }
        }

        is MetadataReceivedAlert -> {
          val handle = handleForAlert(alert) ?: return
          currentHandle = handle
          prioritizeFile(handle, targetChapterIndex, targetFileName)
          checkStreamReadiness(handle)
          updateBufferingIfNeeded(handle)
        }

        is PieceFinishedAlert -> {
          val handle = handleForAlert(alert) ?: return
          val status = synchronized(torrentLock) {
            try { handle.status() } catch (_: Exception) { null }
          } ?: return
          val progress = status.progress()
          val peers = status.numPeers()
          val dlSpeed = status.downloadPayloadRate().toLong()
          val ulSpeed = status.uploadPayloadRate().toLong()
          val ratio = if (status.allTimeDownload() > 0) status.allTimeUpload().toFloat() / status.allTimeDownload() else 0f

          _status.value = TorrentStatus(
            peers = peers,
            downloadSpeed = dlSpeed,
            uploadSpeed = ulSpeed,
            progress = progress,
            ratio = ratio,
            state = _streamState.value,
          )

          checkStreamReadiness(handle)
          when (val current = _streamState.value) {
            is TorrentStreamState.Ready -> {
              _streamState.value = current.copy(
                peers = peers,
                downloadSpeed = dlSpeed,
                uploadSpeed = ulSpeed,
              )
            }
            is TorrentStreamState.Error, is TorrentStreamState.Seeding -> {}
            else -> {
              _streamState.value = TorrentStreamState.Buffering(
                progress = progress,
                peers = peers,
                downloadSpeed = dlSpeed,
              )
            }
          }
        }

        is TorrentFinishedAlert -> {
          val handle = handleForAlert(alert) ?: return
          val info = currentFileInfo ?: return
          val isTargetFileComplete = (info.firstPiece..info.lastPiece).all { hasPiece(handle, it) }
          if (isTargetFileComplete) {
            val status = synchronized(torrentLock) {
              try { handle.status() } catch (_: Exception) { null }
            } ?: return
            _streamState.value = TorrentStreamState.Seeding(
              filePath = File(saveDir, info.fileName).absolutePath,
              peers = status.numPeers(),
              uploadSpeed = status.uploadPayloadRate().toLong(),
              ratio = if (status.allTimeDownload() > 0) status.allTimeUpload().toFloat() / status.allTimeDownload() else 0f,
            )
          }
        }

        else -> {}
      }
    }
  }
}
