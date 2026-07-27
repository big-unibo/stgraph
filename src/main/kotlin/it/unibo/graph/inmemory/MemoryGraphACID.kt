package it.unibo.graph.inmemory

import it.unibo.graph.interfaces.*
import it.unibo.graph.utils.DUMMY_ID
import it.unibo.graph.utils.PATH
import it.unibo.graph.utils.serialize
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentLinkedQueue

interface Flushable {
    fun flushToDisk()
}

class MemoryGraphACID(
    path: String = PATH + "graph_acid/",
    nodeFile: String = "nodes.dat",
    edgeFile: String = "edges.dat",
    propertyFile: String = "property.dat",
    val wal: WriteAheadLog = WriteAheadLog(path = path),
    nodes: MutableList<N> = ArrayList(),
    edges: MutableList<R> = ArrayList(),
    props: MutableList<P> = ArrayList(),
) : MemoryGraph(nodes, edges, props, path), Flushable {
    private val nodeChannel: FileChannel = FileChannel.open(File("$path/$nodeFile").toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
    private val edgeChannel: FileChannel = FileChannel.open(File("$path/$edgeFile").toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
    private val propertyChannel: FileChannel = FileChannel.open(File("$path/$propertyFile").toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)

    init {
        if (!Files.exists(Paths.get(path))) {
            Files.createDirectories(Paths.get(path))
        }
        wal.setFlushable(this)
    }

    // Generic function to read from disk and deserialize
    private fun <T> getFromDisk(channel: FileChannel, id: Long, size: Int, fromByteArray: (ByteArray, Any) -> T): T {
        val buffer = ByteBuffer.allocate(size) // Allocate buffer of appropriate size
        channel.read(buffer, size * id) // Read up to n bytes
        return fromByteArray(buffer.array(), this) // Deserialize using the provided method
    }

    // Wrapper functions for specific types
    fun getNodeFromDisk(id: Long): N {
        return getFromDisk(nodeChannel, id, NODE_SIZE) { bytes, _ -> N.fromByteArray(bytes, this) }
    }

    fun getEdgeFromDisk(id: Long): R {
        return getFromDisk(edgeChannel, id, EDGE_SIZE) { bytes, _ -> R.fromByteArray(bytes, this) }
    }

    fun getPropertyFromDisk(id: Long): P {
        return getFromDisk(propertyChannel, id, PROPERTY_SIZE) { bytes, _ -> P.fromByteArray(bytes, this) }
    }

    override fun clear() {
        super.clear()
        listOf(nodeChannel, edgeChannel, propertyChannel).forEach {
            it.truncate(0)
            it.position(0)
        }
        wal.clear()
    }

    companion object {
        fun <T> readObjectsFromFile(filePath: String, objectSize: Int, deserializer: (ByteArray) -> T, result: MutableList<T>, offset: Long = 0) {
            RandomAccessFile(filePath, "r").use { file ->
                val channel: FileChannel = file.channel
                channel.position(offset)
                val buffer = ByteArray(objectSize)
                while (true) {
                    val bytesRead = channel.read(ByteBuffer.wrap(buffer))
                    if (bytesRead < objectSize) break
                    result.add(deserializer(buffer.copyOf())) // Defensive copy
                }
            }
        }

        fun readFromDisk(path: String = PATH, nodeFile: String = "nodes.dat", edgeFile: String = "edges.dat", propertyFile: String = "property.dat"): MemoryGraphACID {
            val nodes = mutableListOf<N>()
            val edges = mutableListOf<R>()
            val props = mutableListOf<P>()
            val g = MemoryGraphACID(nodes = nodes, edges = edges, props = props, path = path)
            readObjectsFromFile("$path/$nodeFile", objectSize = NODE_SIZE, deserializer = { array -> N.fromByteArray(array, g) }, result = nodes)
            readObjectsFromFile("$path/$edgeFile", objectSize = EDGE_SIZE, deserializer = { array -> R.fromByteArray(array, g) }, result = edges)
            readObjectsFromFile("$path/$propertyFile", objectSize = PROPERTY_SIZE, deserializer = { array -> P.fromByteArray(array, g) }, result = props)
            return g
        }
    }

    @Throws(IOException::class)
    override fun flushToDisk() {
        while (true) {
            val it = wal.toWrite.poll() ?: break  // remove + get in O(1)
            if (!it.flushedOnLog) continue
            val file: FileChannel = when (it.file) {
                WALSource.Nodes -> nodeChannel
                WALSource.Edges -> edgeChannel
                else -> propertyChannel
            }

            file.position(it.offset)
            file.write(ByteBuffer.wrap(it.payload))
        }
    }

    @Throws(IOException::class)
    override fun addEdge(r: R): R {
        if (r.id != DUMMY_ID) { // if this is an edge belonging to the graph (and not to a TS)...
            wal.log(WALSource.Edges, r.id * EDGE_SIZE, r.serialize())
        }
        return super.addEdge(r)
    }

    @Throws(IOException::class)
    override fun addNode(n: N): N {
        wal.log(WALSource.Nodes, n.id * NODE_SIZE, n.serialize())
        return super.addNode(n)
    }

    @Throws(IOException::class)
    override fun addPropertyLocal(key: Long, p: P): P {
        wal.log(WALSource.Properties, p.id * PROPERTY_SIZE, p.serialize())
        return super.addPropertyLocal(key, p)
    }

    override fun close() {
        flushToDisk()
        super.close()
    }
}

enum class WALSource { Nodes, Edges, Properties }

class WALRecord(val file: WALSource, val offset: Long, val payload: ByteArray, var flushedOnLog: Boolean) :
    java.io.Serializable {
    init {
        if (offset < 0) {
            throw IllegalArgumentException("Offset must be greater than zero.")
        }
    }
}

class WriteAheadLog(fileName: String = "wal.log", path: String = PATH, val frequency: Int = 10_000) {
    private val logChannel: FileChannel
    val toWrite = ConcurrentLinkedQueue<WALRecord>()
    private var flushable: Flushable? = null
    private var i = 0
    init {
        if (frequency < 1) {
            throw IllegalArgumentException("Frequency must be greater than zero.")
        }
        if (!Files.exists(Paths.get(path))) {
            Files.createDirectories(Paths.get(path))
        }
        logChannel = FileChannel.open(File("$path/$fileName").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
    }

    fun log(fileChannel: WALSource, offset: Long, payload: ByteArray) {
        toWrite.offer(WALRecord(fileChannel, offset, payload, true))
        i = (i + 1) % frequency
        if (i == 0) {
            val serializedRecords = toWrite.map { serialize(it) }
            val buffer = ByteBuffer.allocate(serializedRecords.sumOf { it.size })
            serializedRecords.forEach { buffer.put(it) }
            buffer.flip()
            logChannel.write(buffer)
            logChannel.force(false)
            if (flushable != null) {
                flushable!!.flushToDisk()
            }
        }
    }

    fun setFlushable(memoryGraphACID: Flushable) {
        flushable = memoryGraphACID
    }

    fun clear() {
        i = 0
        toWrite.clear()
        logChannel.truncate(0)
        logChannel.position(0)
    }
}
