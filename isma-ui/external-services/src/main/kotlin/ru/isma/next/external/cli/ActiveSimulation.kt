package ru.isma.next.external.cli

import org.slf4j.LoggerFactory
import ru.isma.next.external.dtos.CachedSimulationResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * A running simulation runner process. Reads the runner's stdout (the ISMA
 * exchange binary format) on a background thread to accumulate the result in
 * a file and to expose the TIME column of each streamed row for progress
 * tracking.
 */
class ActiveSimulation(private val process: CliProcess) {

    private val logger = LoggerFactory.getLogger(ActiveSimulation::class.java)
    private val times = LinkedBlockingQueue<Double>()
    private val resultFile = File(
        CliWorkDir.root,
        "isma-simulation-${UUID.randomUUID().toString().replace("-", "")}.bin"
    )
    private var columnNames: List<String> = emptyList()
    // A virtual thread: the stream loop blocks on pipe reads and file writes for
    // the whole run, and a virtual thread parks instead of holding a platform thread.
    private val streamThread = Thread.ofVirtual().name("isma-runner-stream").start { streamResult() }

    /**
     * Blocks until the next TIME value of an integration step is available.
     * Returns `null` when the stream has ended (the run finished or was
     * cancelled) — there is no exception-based completion.
     */
    fun takeTime(): Double? {
        val time = times.take()
        return if (time == END_OF_STREAM) null else time
    }

    /**
     * Waits for the run to finish and returns the result.
     * Throws [CliToolException] when the run failed.
     */
    fun getResult(): CachedSimulationResult {
        val exitCode = process.waitExitCode()
        // wait for the stdout stream to drain so the result file is complete
        streamThread.join()
        if (exitCode != 0) {
            val stderr = process.stderr().trim()
            resultFile.delete()
            throw CliToolException("simulation-runner-cli", exitCode, stderr)
        }
        return CachedSimulationResult(resultFile, columnNames)
    }

    /** Cancels the run: graceful termination of the runner, hard kill as a fallback. */
    fun cancel() = process.terminate()

    private fun streamResult() {
        // Buffer the file sink and read whole rows: the runner emits one row per
        // integration step, and writing each 8-byte double straight to the file
        // back-pressures the pipe and stalls the runner. Bulk row reads plus a
        // buffered sink keep the pipe drained at full speed.
        val sink = BufferedOutputStream(resultFile.outputStream(), SINK_BUFFER_SIZE)
        val source = BufferedInputStream(process.stdout, SOURCE_BUFFER_SIZE)
        val tee = TeeStream(source, sink)
        try {
            val numColumns = readUInt16BE(tee)
            val names = Array(numColumns) { i ->
                val nameLength = readUInt16BE(tee)
                String(tee.readFully(nameLength), Charsets.UTF_8)
            }
            columnNames = names.toList()

            val row = ByteArray(numColumns * DOUBLE_BYTES)
            val rowView = ByteBuffer.wrap(row).order(ByteOrder.BIG_ENDIAN)
            while (true) {
                tee.readFully(row)
                times.add(rowView.getDouble(0))
            }
        } catch (e: EOFException) {
            // stream exhausted — the runner has finished writing
        } catch (e: Exception) {
            // the process was terminated or the stream broke mid-run
            logger.debug("Simulation stream ended: {}", e.message)
        } finally {
            times.add(END_OF_STREAM)
            tee.close()
        }
    }

    private fun readUInt16BE(tee: TeeStream): Int {
        val hi = tee.readByte()
        if (hi < 0) throw EOFException()
        val lo = tee.readByte()
        if (lo < 0) throw EOFException()
        return (hi shl 8) or lo
    }

    /** Reads from the source stream and tees every byte into the sink. */
    private class TeeStream(private val source: InputStream, private val sink: OutputStream) : Closeable {
        fun readByte(): Int {
            val b = source.read()
            if (b >= 0) sink.write(b)
            return b
        }

        fun readFully(count: Int): ByteArray {
            val buffer = ByteArray(count)
            readFully(buffer)
            return buffer
        }

        fun readFully(buffer: ByteArray) {
            var offset = 0
            while (offset < buffer.size) {
                val n = source.read(buffer, offset, buffer.size - offset)
                if (n <= 0) throw EOFException()
                offset += n
            }
            sink.write(buffer)
        }

        override fun close() {
            sink.flush()
            sink.close()
        }
    }

    private companion object {
        /** Queue terminator: integration times are finite, so this value never occurs in the stream. */
        const val END_OF_STREAM = Double.NEGATIVE_INFINITY
        const val DOUBLE_BYTES = 8
        const val SOURCE_BUFFER_SIZE = 32 * 1024
        const val SINK_BUFFER_SIZE = 8 * 1024
    }
}
