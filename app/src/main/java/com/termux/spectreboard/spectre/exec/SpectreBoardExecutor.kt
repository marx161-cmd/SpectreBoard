// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.exec

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * SpectreBoardExecutor
 *
 * Command-dispatch seam for the Termux suite. Anything in the ecosystem
 * (Kai, Poll-E, manual paste, Whisper output) can hand a string to this and it
 * runs the command on the chosen target, delivering the result via KDE Connect
 * share (appears as a desktop popup — no POST_NOTIFICATIONS permission needed).
 *
 * ROUTING is decided two ways, in priority order:
 *   1. An optional PREFIX in the command text (a tiny DSL). E.g. "@comrade df -h"
 *      runs remotely regardless of button state.
 *   2. The button-toggled DEFAULT mode, used when there is no prefix.
 *
 * ============================ ASSUMPTIONS TO VERIFY ============================
 * [E1] Local exec runs via `sh -c` in the keyboard's process. Termux binaries
 *      resolve via localEnvPath; absolute paths are safer for critical tools.
 * [E2] SSH to comrade assumes KEY-BASED auth (no passphrase prompt). Use a
 *      passphrase-less key or ssh-agent for non-interactive use.
 * [E3] SSH hostname "comrade" must resolve — via Tailscale MagicDNS or ~/.ssh/config.
 * [E4] KDE Connect (org.kde.kdeconnect_tp or org.kde.kdeconnect) must be installed
 *      and paired. If absent, notifications silently no-op.
 * [E5] The `ssh` binary must exist on the local side (openssh Termux package).
 * ==============================================================================
 */

// ---------- modes & targets ----------

enum class ExecutionMode(
    val prefix: String,
    val isRemote: Boolean,
    val sshUser: String? = null,
    val sshHost: String? = null,
    val defaultTimeoutSec: Long
) {
    LOCAL(
        prefix = "@local",
        isRemote = false,
        defaultTimeoutSec = 30
    ),
    COMRADE(
        prefix = "@comrade",
        isRemote = true,
        sshUser = "comrade",
        sshHost = "comrade",
        defaultTimeoutSec = 60
    );

    companion object {
        fun fromPrefix(token: String): ExecutionMode? =
            entries.firstOrNull { it.prefix == token }
    }
}

// ---------- per-call options ----------

data class ExecOptions(
    val notify: Boolean = true,
    val captureOutput: Boolean = true,
    val timeoutSec: Long? = null,
    val tag: String? = null
)

// ---------- result ----------

sealed class ExecutionResult {
    data class Success(val output: String, val exitCode: Int) : ExecutionResult()
    data class Error(val message: String) : ExecutionResult()
    object Timeout : ExecutionResult()
    object Started : ExecutionResult()
}

// ---------- parsed command ----------

data class ParsedCommand(
    val mode: ExecutionMode,
    val command: String,
    val options: ExecOptions
)

/**
 * CommandParser
 *
 * Turns raw text into a ParsedCommand. Leading tokens are consumed as long as
 * they're recognised; everything else is the command verbatim.
 *
 * Supported leading tokens (any order, all optional):
 *   @local | @comrade   -> pick the target mode
 *   !nonotify           -> suppress KDE Connect share entirely
 *   !bg                 -> fire-and-forget (captureOutput = false)
 *   !tag=NAME           -> label prepended to the notification (no spaces in NAME)
 *
 * Examples:
 *   "@comrade !bg !tag=train python train.py"   remote, fire-and-forget, tagged "train"
 *   "ls -la"                                     uses defaultMode, normal notify
 *   "@local !nonotify df -h"                     local, no notification
 */
object CommandParser {

    fun parse(raw: String, defaultMode: ExecutionMode): ParsedCommand {
        val trimmed = raw.trim()
        var mode: ExecutionMode? = null
        var notify = true
        var capture = true
        var tag: String? = null

        val tokens = trimmed.split(Regex("\\s+"))
        var consumed = 0
        for (tok in tokens) {
            val modeMatch = ExecutionMode.fromPrefix(tok)
            when {
                modeMatch != null        -> { mode = modeMatch; consumed++ }
                tok == "!nonotify"       -> { notify = false; consumed++ }
                tok == "!bg"             -> { capture = false; consumed++ }
                tok.startsWith("!tag=")  -> { tag = tok.removePrefix("!tag="); consumed++ }
                else                     -> break
            }
        }

        val command = tokens.drop(consumed).joinToString(" ").trim()

        return ParsedCommand(
            mode = mode ?: defaultMode,
            command = command,
            options = ExecOptions(notify = notify, captureOutput = capture, tag = tag)
        )
    }
}

// ---------- the executor ----------

class SpectreBoardExecutor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    /** [E1] Prepend Termux bin so common binaries resolve from the keyboard's process. */
    var localEnvPath: String? = "/data/data/com.termux/files/usr/bin"

    fun dispatch(rawText: String, defaultMode: ExecutionMode) {
        val parsed = CommandParser.parse(rawText, defaultMode)
        if (parsed.command.isBlank()) {
            kdeConnectSend("SpectreBoard", "Empty command after parsing.", parsed.options)
            return
        }

        val timeout = parsed.options.timeoutSec ?: parsed.mode.defaultTimeoutSec

        scope.launch {
            if (!parsed.options.captureOutput) {
                launch { runCommand(parsed, timeout) }
                kdeConnectSend(label(parsed, "started"), "Running in background.", parsed.options)
                return@launch
            }
            val result = runCommand(parsed, timeout)
            postResult(parsed, result)
        }
    }

    fun runLocal(rawText: String) = dispatch(rawText, ExecutionMode.LOCAL)
    fun runRemote(rawText: String) = dispatch(rawText, ExecutionMode.COMRADE)

    // ---------- execution ----------

    private suspend fun runCommand(parsed: ParsedCommand, timeoutSec: Long): ExecutionResult {
        return withTimeoutOrNull(TimeUnit.SECONDS.toMillis(timeoutSec)) {
            var process: Process? = null
            try {
                val shellCmd = if (parsed.mode.isRemote) {
                    // [E2][E3][E5]
                    "ssh ${parsed.mode.sshUser}@${parsed.mode.sshHost} bash -c ${singleQuote(parsed.command)}"
                } else {
                    parsed.command
                }

                val pb = ProcessBuilder("sh", "-c", shellCmd).redirectErrorStream(true)
                if (!parsed.mode.isRemote && localEnvPath != null) {
                    val env = pb.environment()
                    env["PATH"] = "${localEnvPath}:${env["PATH"] ?: ""}"
                }

                process = pb.start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                val exit = process.waitFor()
                ExecutionResult.Success(output, exit)
            } catch (e: Exception) {
                ExecutionResult.Error(e.message ?: "unknown error")
            } finally {
                process?.destroy()
            }
        } ?: ExecutionResult.Timeout
    }

    private fun singleQuote(s: String): String {
        val escaped = s.replace("'", "'\\''")
        return "'$escaped'"
    }

    // ---------- KDE Connect delivery ----------

    private fun postResult(parsed: ParsedCommand, result: ExecutionResult) {
        val (title, body) = when (result) {
            is ExecutionResult.Success -> {
                val ok = result.exitCode == 0
                label(parsed, if (ok) "✓" else "✗ exit ${result.exitCode}") to
                    result.output.ifBlank { "(no output)" }
            }
            is ExecutionResult.Error   -> label(parsed, "✗ error") to result.message
            ExecutionResult.Timeout    -> label(parsed, "✗ timeout") to "Command exceeded time limit."
            ExecutionResult.Started    -> return  // handled at dispatch site
        }
        kdeConnectSend(title, body, parsed.options)
    }

    private fun label(parsed: ParsedCommand, status: String): String {
        val tag = parsed.options.tag?.let { "[$it] " } ?: ""
        val target = parsed.mode.name.lowercase()
        return "$tag$status ($target)"
    }

    /**
     * [E4] Deliver result via KDE Connect's share plugin — appears as a desktop
     * popup without requiring POST_NOTIFICATIONS permission on the phone.
     * Tries the F-Droid build first, then the Play Store build.
     */
    private fun kdeConnectSend(title: String, body: String, opts: ExecOptions) {
        if (!opts.notify) return
        val text = "[$title]\n\n${body.take(2000)}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        for (pkg in listOf("org.kde.kdeconnect_tp", "org.kde.kdeconnect")) {
            try {
                context.startActivity(intent.apply { setPackage(pkg) })
                return
            } catch (_: ActivityNotFoundException) { }
        }
        // [E4] KDE Connect not installed — result is silently dropped.
    }
}

/**
 * ===== INTEGRATION NOTES =====
 *
 * Toolbar wiring:
 *   val executor = SpectreBoardExecutor(context)
 *   // "Run local" button:
 *   executor.runLocal(currentInputText())
 *   // "Run remote" button:
 *   executor.runRemote(currentInputText())
 *
 * Suite integration (Kai / Poll-E / Whisper):
 *   Emit a prefixed string — SpectreBoard routes it. No new API needed:
 *     "@comrade !bg !tag=train python ~/train.py"
 *     "@local !nonotify ls -la"
 *
 * Adding a new SSH host: add one enum entry to ExecutionMode with its @prefix,
 * sshUser, sshHost. Parser and routing pick it up automatically.
 */
