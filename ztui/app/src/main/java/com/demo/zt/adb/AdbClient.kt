package com.demo.zt.adb

import android.util.Log
import com.demo.zt.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import com.demo.zt.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import com.demo.zt.adb.AdbProtocol.ADB_AUTH_TOKEN
import com.demo.zt.adb.AdbProtocol.A_AUTH
import com.demo.zt.adb.AdbProtocol.A_CLSE
import com.demo.zt.adb.AdbProtocol.A_CNXN
import com.demo.zt.adb.AdbProtocol.A_MAXDATA
import com.demo.zt.adb.AdbProtocol.A_OKAY
import com.demo.zt.adb.AdbProtocol.A_OPEN
import com.demo.zt.adb.AdbProtocol.A_STLS
import com.demo.zt.adb.AdbProtocol.A_STLS_VERSION
import com.demo.zt.adb.AdbProtocol.A_VERSION
import com.demo.zt.adb.AdbProtocol.A_WRTE
import com.demo.zt.compat.BuildUtils
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"
private const val CONNECT_TIMEOUT_MILLIS = 3_000
private const val AUTH_TIMEOUT_MILLIS = 5_000

/** sync 协议 DATA 包上限；实际用法见 [AdbClient.pushFile]，会按对端 A_MAXDATA 收敛。 */
private const val SYNC_CHUNK_MAX = 64 * 1024

class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) : Closeable {

    /** 单条连接上的流操作串行化，避免并发收发把消息搅在一起。 */
    private val streamLock = Any()

    /** 对端在 CNXN 里声明的单包上限。 */
    private var remoteMaxData = AdbProtocol.A_MAXDATA

    private var nextLocalId = 1

    /** shell 流固定用 1；其余流从 2 起，避免与 shell 撞号。 */
    private fun allocLocalId(): Int {
        if (nextLocalId < 2) nextLocalId = 2
        return nextLocalId++
    }

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false

    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    fun connect() {
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
        socket.tcpNoDelay = true
        socket.soTimeout = AUTH_TIMEOUT_MILLIS
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        if (message.command == A_STLS) {
            if (!BuildUtils.atLeast29) {
                error("Android 9 之前不支持通过 TLS 连接 adb")
            }
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.soTimeout = AUTH_TIMEOUT_MILLIS
            tlsSocket.startHandshake()
            Log.d(TAG, "握手成功")

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = read()
        } else if (message.command == A_AUTH) {
            if (message.command != A_AUTH && message.arg0 != ADB_AUTH_TOKEN) error("不是 A_AUTH ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != A_CNXN) error("不是 A_CNXN")
        // CNXN 的 arg1 才是对端可接受的单包上限（arg0 是协议版本号）
        if (message.arg1 > 0) remoteMaxData = message.arg1

        socket.soTimeout = 0
        if (useTls) tlsSocket.soTimeout = 0
    }

    /**
     * 读到属于指定流的消息为止。
     *
     * adb 协议里：收到的消息 arg1 是**本端** localId、arg0 是远端 id。
     * 关闭流时对端回的那条 CLSE 经常迟到，会被下一条 OPEN 读到；
     * 如果不按 arg1 过滤，就会用新的 localId 去回应旧流，把会话彻底搅乱。
     */
    private fun readFor(localId: Int): AdbMessage {
        while (true) {
            val m = read()
            if (m.arg1 == localId) return m
            // 别的流的残留消息：如果是 WRTE 就回个 ack 让对端能继续，然后丢弃
            if (m.command == A_WRTE) {
                runCatching { write(A_OKAY, m.arg1, m.arg0) }
            }
        }
    }

    /** 打开一个 adb 流（shell: / sync: 等），返回 (localId, remoteId)。 */
    fun openStream(service: String): Pair<Int, Int> {
        val localId = allocLocalId()
        write(A_OPEN, localId, 0, service)
        val message = readFor(localId)
        if (message.command != A_OKAY) error("打开 $service 失败: ${message.toStringShort()}")
        return localId to message.arg0
    }

    /** 关闭一个 adb 流；等对端回 CLSE，避免残留消息污染下一条流。 */
    fun closeStream(localId: Int, remoteId: Int) {
        runCatching {
            write(A_CLSE, localId, remoteId)
            val prev = socket.soTimeout
            socket.soTimeout = 1000
            try {
                while (true) {
                    val m = read()
                    if (m.arg1 != localId) continue          // 别的流的残留，跳过
                    when (m.command) {
                        A_CLSE -> return
                        A_WRTE -> write(A_OKAY, localId, m.arg0)
                        else -> Unit
                    }
                }
            } finally {
                socket.soTimeout = prev
            }
        }
    }

    /** 写一段数据到流上；adb 用 A_OKAY 做流控，这里等到对端确认再返回。 */
    private fun writeStreamData(localId: Int, remoteId: Int, data: ByteArray) {
        write(A_WRTE, localId, remoteId, data)
        while (true) {
            val m = readFor(localId)
            when (m.command) {
                A_OKAY -> return
                A_WRTE -> {
                    // 对端主动发来的数据（sync 的 OKAY/FAIL 响应），先缓存，由 waitSyncStatus 判定
                    pending += (m.data ?: ByteArray(0))
                    write(A_OKAY, localId, m.arg0)
                }
                A_CLSE -> {
                    // 关闭前可能已经收到 FAIL，优先用远端给的原因
                    val reason = pending.firstNotNullOfOrNull { parseSyncStatus(it) }
                        ?.takeIf { it.startsWith("FAIL") }
                        ?.removePrefix("FAIL")?.trim()
                    error(reason?.let { "远端拒绝：$it" } ?: "流被关闭")
                }
                else -> error("意外消息 ${m.toStringShort()}")
            }
        }
    }

    /** sync 响应缓存（OKAY/FAIL 包）。 */
    private val pending = ArrayDeque<ByteArray>()

    /** 若缓存里已有 FAIL，取出并抛出；返回 false 表示失败已发生且未写入任何数据。 */
    private fun checkPendingFailure(remotePath: String): Boolean {
        val it = pending.iterator()
        while (it.hasNext()) {
            val parsed = parseSyncStatus(it.next()) ?: continue
            if (parsed.startsWith("FAIL")) {
                it.remove()
                val msg = parsed.removePrefix("FAIL").trim()
                // SEND 阶段被拒：换格式重试；其它阶段直接抛
                if (msg.contains("ID_SEND")) return false
                error("push 失败：$msg（$remotePath）")
            }
        }
        return true
    }

    private fun parseSyncStatus(data: ByteArray): String? {
        if (data.size < 8) return null
        val id = String(data, 0, 4, Charsets.US_ASCII)
        if (id != "OKAY" && id != "FAIL") return null
        val len = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val msg = if (len > 0 && data.size >= 8 + len) String(data, 8, len, Charsets.UTF_8) else ""
        return id + msg
    }

    /**
     * 用 adb 的 sync 协议推送文件，等价于 `adb push`。
     * 走流式传输，不受 shell 命令行长度限制，可推几十 MB。
     *
     * Android 12 起 adbd 的 SEND 需要 `路径,模式` 形式（st_mode 十进制），
     * 老版本只接受纯路径；这里先按新格式发，被拒后再退回老格式。
     */
    fun pushFile(remotePath: String, content: ByteArray, mode: String = "644") {
        synchronized(streamLock) {
            pushFileLocked(remotePath, content, mode)
        }
    }

    private fun pushFileLocked(remotePath: String, content: ByteArray, mode: String) {
        val stMode = runCatching { Integer.parseInt(mode, 8) }.getOrDefault(0x1A4) // 0644
        // 普通文件类型位 0100000 + 权限位
        val modeWithType = 0x8000 or stMode
        // 注意顺序：adbd 先按 NUL 截断再找逗号，所以必须是 `路径,模式\0`
        val useOldFormat = !pushOnce(remotePath, content, "$remotePath,$modeWithType\u0000")
        if (useOldFormat) {
            pushOnce(remotePath, content, "$remotePath\u0000")
        }

        // sync 协议本身不带权限位（老版本协议），补一次 chmod 兜底
        if (mode.isNotEmpty()) {
            runShell("chmod $mode '$remotePath'")
        }
    }

    /** 返回 true 表示成功；SEND 被拒且尚未发送数据时返回 false，供调用方换格式重试。 */
    private fun pushOnce(remotePath: String, content: ByteArray, sendPayload: String): Boolean {
        val (localId, remoteId) = openStream("sync:")
        pending.clear()
        try {
            sendSync(localId, remoteId, "SEND", sendPayload.toByteArray(Charsets.UTF_8))
            // SEND 之后 adbd 会先用一个 sync OKAY/FAIL 回执表示能否创建目标文件
            if (!checkPendingFailure(remotePath)) return false

            var offset = 0
            // 单个 WRTE 的总载荷（8 字节 sync 头 + 数据）不能超过对端在 CNXN 里声明的上限
            val chunk = minOf(SYNC_CHUNK_MAX - 8, maxOf(remoteMaxData - 8, 512))
            while (offset < content.size) {
                val n = minOf(chunk, content.size - offset)
                sendSync(localId, remoteId, "DATA", content.copyOfRange(offset, offset + n))
                offset += n
                checkPendingFailure(remotePath)
            }
            // DONE 的载荷是 4 字节 mtime，用 0 表示不设置
            sendSync(localId, remoteId, "DONE", ByteArray(4))

            val result = waitSyncStatus(localId, remoteId)
            if (result.startsWith("FAIL")) {
                error("push 失败：${result.removePrefix("FAIL").trim()}（$remotePath）")
            }
            return true
        } finally {
            pending.clear()
            closeStream(localId, remoteId)
        }
    }

    private fun sendSync(localId: Int, remoteId: Int, id: String, payload: ByteArray) {
        val packet = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        packet.put(id.toByteArray(Charsets.US_ASCII))
        packet.putInt(payload.size)
        packet.put(payload)
        writeStreamData(localId, remoteId, packet.array())
    }

    /** 读到 OKAY / FAIL 为止。 */
    private fun waitSyncStatus(localId: Int, remoteId: Int): String {
        // 先看流控期间是否已经收到过响应
        while (pending.isNotEmpty()) {
            val parsed = parseSyncStatus(pending.removeFirst())
            if (parsed != null) return parsed
        }
        while (true) {
            val m = readFor(localId)
            when (m.command) {
                A_WRTE -> {
                    val data = m.data ?: ByteArray(0)
                    write(A_OKAY, localId, m.arg0)
                    parseSyncStatus(data)?.let { return it }
                }
                A_CLSE -> error("push 期间流被关闭")
                else -> Unit
            }
        }
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        // 同一条连接上并发收发会让消息串流，串行化所有流操作
        synchronized(streamLock) {
            shellCommandLocked(command, listener)
        }
    }

    private fun shellCommandLocked(command: String, listener: ((ByteArray) -> Unit)?) {
        // 每条命令用新的 localId，并按 arg1 过滤，避免上一条流的迟到 CLSE 被误读成本条输出
        val localId = allocLocalId()
        write(A_OPEN, localId, 0, "shell:$command")

        var message = readFor(localId)
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = readFor(localId)
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0) {
                            listener?.invoke(message.data!!)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        // 其它消息（上一条流遗留的延迟 ack）忽略，避免整条会话失效
                        Unit
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> {
                error("不是 A_OKAY 或 A_CLSE")
            }
        }
    }

    fun root(listener: ((ByteArray) -> Unit)?) {
        val localId = 1
        write(A_OPEN, localId, 0, "root:")

        var message = read()
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0) {
                            listener?.invoke(message.data!!)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        error("不是 A_WRTE 或 A_CLSE")
                    }
                }
            }

            else -> {
                Log.e(TAG, "不是A_OKAY响应")
                error("不是 A_OKAY")
            }
        }
    }

    fun tcpip(port: Int, listener: ((ByteArray) -> Unit)?) {
        val localId = 1
        write(A_OPEN, localId, 0, "tcpip:$port")

        var message = read()
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0) {
                            listener?.invoke(message.data!!)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        error("不是 A_WRTE 或 A_CLSE")
                    }
                }
            }

            else -> {
                Log.e(TAG, "not A_OKAY")
                error("不是 A_OKAY")
            }
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(
        AdbMessage(command, arg0, arg1, data)
    )

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(
        AdbMessage(
            command,
            arg0,
            arg1,
            data
        )
    )

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "写入 ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        
        val data: ByteArray?
        if (dataLength >= 0) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            data = null
        }
        
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "读取 ${message.toStringShort()}")
        return message
    }

    override fun close() {
        runCatching { plainInputStream.close() }
        runCatching { plainOutputStream.close() }
        runCatching { socket.close() }

        if (useTls) {
            runCatching { tlsInputStream.close() }
            runCatching { tlsOutputStream.close() }
            runCatching { tlsSocket.close() }
        }
    }
}


/** Convenience wrapper: run a shell command and return its collected output. */
fun AdbClient.runShell(command: String): String {
    val sb = StringBuilder()
    this.shellCommand(command) { sb.append(String(it, Charsets.UTF_8)) }
    return sb.toString()
}

/** Convenience wrapper: run in root context and return collected output. */
fun AdbClient.runRoot(): String {
    val sb = StringBuilder()
    this.root { sb.append(String(it, Charsets.UTF_8)) }
    return sb.toString()
}

/** 公开别名：执行 shell 命令并返回输出。 */
fun AdbClient.runShellPublic(command: String): String = this.runShell(command)
