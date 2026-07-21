package com.termux.spectreboard.npud

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Client for npud, the resident NPU model daemon.
 *
 * npud keeps one warm worker process per model, so requests skip the
 * per-call model init that made local inference slow — and, on the in-process
 * JNI path, fatal: `nativeCreateEngine` SIGABRTs the whole app (this is what
 * killed Agora whenever a new chat triggered NPU title generation). Talking to
 * a separate process over a socket sidesteps that class of crash entirely,
 * which is the same reason SubAgent and the embedder have always been reliable.
 *
 * Reachable because every com.termux.* app shares Termux's UID, so the socket
 * under `$PREFIX/tmp` is ours to open.
 *
 * There is deliberately **no fallback** to in-process inference. A silent
 * fallback would mask a broken npud path — the app would quietly use the old
 * code, look fine, and never reveal that the integration regressed. Failures
 * here surface as errors instead.
 */
object NpudClient {

    const val SOCKET_PATH = "/data/data/com.termux/files/usr/tmp/npud.sock"

    /** Model kinds npud serves. Section names in npud.conf. */
    const val KIND_LLM = "llm"
    const val KIND_ASR = "asr"

    /** Cold start has to load the model; be generous rather than flap. */
    private const val DEFAULT_TIMEOUT_MS = 180_000

    data class Model(val kind: String, val name: String)

    class NpudException(message: String) : Exception(message)

    /** True when the daemon is up and accepting connections. */
    fun isAvailable(): Boolean = try {
        connect().close()
        true
    } catch (_: Exception) {
        false
    }

    private fun connect(): LocalSocket {
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM))
        return socket
    }

    /** Ask npud what it can serve. npud rescans on every call, so a model
     *  dropped into its directory shows up here without restarting anything. */
    fun list(): List<Model> = request("LIST") { reader ->
        val out = mutableListOf<Model>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line == "END") break
            if (line.startsWith("ERR ")) throw NpudException(line.removePrefix("ERR "))
            val parts = line.split(' ', limit = 2)
            if (parts.size == 2) out.add(Model(parts[0], parts[1]))
        }
        out
    }

    /** Load a model now rather than on first use, so the next request is warm. */
    fun warm(kind: String, model: String) {
        request("WARM $kind $model") { reader ->
            val line = reader.readLine() ?: throw NpudException("no response")
            if (line.startsWith("ERR ")) throw NpudException(line.removePrefix("ERR "))
        }
    }

    /**
     * Run one request against a model and return its response body.
     *
     * [payload] is passed to the worker verbatim, so this serves text prompts
     * for `llm` just as well as a raw worker command line for backends whose
     * interface isn't text (kokoro's `chain <bucket> <in_dir> <out.s16le>`).
     * Newlines are escaped by npud, which is line-oriented.
     */
    fun generate(
        kind: String,
        model: String,
        payload: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): String = request("GEN $kind $model $payload", timeoutMs) { reader ->
        val body = StringBuilder()
        var started = false
        while (true) {
            val line = reader.readLine() ?: throw NpudException("connection closed mid-response")
            when {
                line.startsWith("ERR ") -> throw NpudException(line.removePrefix("ERR "))
                line == "BEGIN" -> started = true
                line == "END" -> return@request body.toString()
                started -> {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(line)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        body.toString()
    }

    private fun <T> request(
        command: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        parse: (BufferedReader) -> T,
    ): T {
        val socket = try {
            connect()
        } catch (e: Exception) {
            throw NpudException(
                "npud is not reachable at $SOCKET_PATH (${e.message}). " +
                    "Is the daemon running? Check \$PREFIX/tmp/npud.log"
            )
        }
        return socket.use { s ->
            s.soTimeout = timeoutMs
            // A single newline terminates the command; npud reads line-wise.
            s.outputStream.write((command.replace("\n", "\\n") + "\n").toByteArray())
            s.outputStream.flush()
            parse(BufferedReader(InputStreamReader(s.inputStream)))
        }
    }
}
