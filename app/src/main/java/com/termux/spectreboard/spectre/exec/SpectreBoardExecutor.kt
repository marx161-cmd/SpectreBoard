// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.exec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

// Named tmux session on every environment — attach with: tmux attach -t spectreboard
private const val SESSION = "spectreboard"

private const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
private const val TERMUX_HOME = "/data/data/com.termux/files/home"

// ---------- modes & targets ----------

enum class ExecutionMode(
    val prefix: String,
    val isRemote: Boolean,
    val sshUser: String? = null,
    val sshHost: String? = null,
) {
    LOCAL("@local", false),
    COMRADE("@comrade", true, sshUser = "comrade", sshHost = "comrade");

    companion object {
        fun fromPrefix(token: String): ExecutionMode? =
            entries.firstOrNull { it.prefix == token }
    }
}

// ---------- per-call options ----------

data class ExecOptions(
    val notify: Boolean = true,
    val tag: String? = null
)

// ---------- parsed command ----------

data class ParsedCommand(
    val mode: ExecutionMode,
    val command: String,
    val options: ExecOptions
)

/**
 * CommandParser — tiny DSL of leading tokens.
 *
 * Supported tokens (any order, all optional):
 *   @local | @comrade   target environment
 *   !nonotify           suppress the dispatch notification
 *   !tag=NAME           label shown in the notification
 *
 * Everything after the recognised tokens is the command verbatim.
 */
object CommandParser {
    fun parse(raw: String, defaultMode: ExecutionMode): ParsedCommand {
        val tokens = raw.trim().split(Regex("\\s+"))
        var mode: ExecutionMode? = null
        var notify = true
        var tag: String? = null
        var consumed = 0

        for (tok in tokens) {
            when {
                ExecutionMode.fromPrefix(tok) != null -> { mode = ExecutionMode.fromPrefix(tok)!!; consumed++ }
                tok == "!nonotify"                    -> { notify = false; consumed++ }
                tok.startsWith("!tag=")               -> { tag = tok.removePrefix("!tag="); consumed++ }
                else                                  -> break
            }
        }

        return ParsedCommand(
            mode = mode ?: defaultMode,
            command = tokens.drop(consumed).joinToString(" ").trim(),
            options = ExecOptions(notify = notify, tag = tag)
        )
    }
}

// ---------- executor ----------

class SpectreBoardExecutor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    init { ensureChannels() }

    fun dispatch(rawText: String, defaultMode: ExecutionMode) {
        val parsed = CommandParser.parse(rawText, defaultMode)
        if (parsed.command.isBlank()) {
            notify(parsed.options, "SpectreBoard", "Empty command.")
            return
        }
        scope.launch { sendToSession(parsed) }
    }

    fun runLocal(rawText: String)  = dispatch(rawText, ExecutionMode.LOCAL)
    fun runRemote(rawText: String) = dispatch(rawText, ExecutionMode.COMRADE)

    // ---------- tmux dispatch ----------

    // Base64-encode the command so it survives every quoting layer intact.
    // Remote shell decodes with: echo BASE64 | base64 -d
    private fun b64(command: String): String =
        Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    // Bash one-liner that creates the session if absent, then sends the command.
    private fun tmuxScript(encoded: String): String =
        "tmux new-session -d -s $SESSION 2>/dev/null; " +
        "tmux send-keys -t $SESSION -- \"\$(echo $encoded | base64 -d)\" Enter"

    private fun sendToSession(parsed: ParsedCommand) {
        val encoded = b64(parsed.command)
        val script  = tmuxScript(encoded)

        val termuxArgs: Array<String> = if (parsed.mode.isRemote) {
            // SSH to the remote host, single-quote the script so the local bash
            // passes it verbatim. Remote shell expands the base64 substitution.
            val ssh = "ssh -o BatchMode=yes -o ConnectTimeout=10 " +
                      "${parsed.mode.sshUser}@${parsed.mode.sshHost} " +
                      singleQuote(script)
            arrayOf("-c", ssh)
        } else {
            arrayOf("-c", script)
        }

        // Run via Termux's own bash — gives access to Termux's PATH and SSH keys.
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", termuxArgs)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            putExtra("com.termux.RUN_COMMAND_TERMINAL", false)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            val target = if (parsed.mode.isRemote)
                "${parsed.mode.sshHost}:$SESSION" else "local:$SESSION"
            notify(parsed.options, "→ $target", parsed.command.take(150))
        } catch (e: Exception) {
            notify(parsed.options, "SpectreBoard error", e.message ?: "failed to start Termux service")
        }
    }

    private fun singleQuote(s: String) = "'${s.replace("'", "'\\''")}'"

    // ---------- notifications ----------

    private val notifId = AtomicInteger(2000)

    private fun notify(opts: ExecOptions, title: String, body: String) {
        if (!opts.notify) return
        val tagPart = opts.tag?.let { "[$it] " } ?: ""
        postNotification("$tagPart$title", body)
    }

    private fun postNotification(title: String, body: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId.incrementAndGet(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently drop
        }
    }

    private fun ensureChannels() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "SpectreBoard Exec", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        private const val CHANNEL = "spectreboard_exec"
    }
}
