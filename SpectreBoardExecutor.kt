package helium314.keyboard.spectre.exec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SpectreBoardExecutor
 *
 * The command-dispatch seam for the Termux suite. Anything in the ecosystem
 * (Kai, Poll-E, manual paste, Whisper output) can hand a string to this and it
 * runs the command on the chosen target, delivering the result as a NOTIFICATION
 * (never inline — the keyboard's job is input, not output display).
 *
 * ROUTING is decided two ways, in priority order:
 *   1. An optional PREFIX in the command text (a tiny DSL). This lets any app in
 *      the suite drive execution by just emitting a prefixed string — no API
 *      coupling. E.g.  "@comrade df -h"  runs remotely regardless of button state.
 *   2. The button-toggled DEFAULT mode, used when there is no prefix. This is the
 *      manual path: user toggles "local" or "remote", then sends bare text.
 *
 * ============================ ASSUMPTIONS TO VERIFY ============================
 * [E1] Local exec runs via `sh -c` in the app's process. In a Termux-adjacent
 *      app this inherits Termux's PATH only if launched in that environment.
 *      If commands like `python`/`curl` aren't found, the PATH needs setting
 *      (see localEnvPath) or commands must be absolute. VERIFY which binaries
 *      resolve from the keyboard's process vs a real Termux shell.
 * [E2] SSH to the homelab assumes KEY-BASED auth (no passphrase prompt). If a
 *      passphrase is required the process hangs until timeout. Set up ssh-agent
 *      or a passphrase-less key for non-interactive use.
 * [E3] SSH hostnames (comrade, etc.) must resolve — via ~/.ssh/config, Tailscale
 *      MagicDNS, or /etc/hosts. If DNS fails, SSH errors immediately.
 * [E4] POST_NOTIFICATIONS runtime permission (Android 13+) must be granted or
 *      notifications silently no-op. Request it once at keyboard setup.
 * [E5] The `ssh` binary must exist on the local side. In Termux that's the
 *      openssh package. If absent, remote mode errors with "ssh: not found".
 * ==============================================================================
 */

// ---------- modes & targets ----------

/**
 * Execution targets. Add new hosts here; each gets an optional text prefix so
 * the whole suite can route to it without any keyboard recompile beyond this enum.
 */
enum class ExecutionMode(
    val prefix: String,          // text DSL prefix, e.g. "@local"
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
        sshUser = "root",        // ASSUMPTION: adjust to your actual homelab user
        sshHost = "comrade",     // ASSUMPTION: must resolve (ssh config / Tailscale)
        defaultTimeoutSec = 60
    );

    companion object {
        /** Find a mode by its text prefix, or null if the text has no known prefix. */
        fun fromPrefix(token: String): ExecutionMode? =
            entries.firstOrNull { it.prefix == token }
    }
}

// ---------- per-call options (flags) ----------

/**
 * Modular flags per execution. Defaults are sane; the prefix DSL can override
 * some of these too (see flag tokens in CommandParser).
 *
 * @param notify        whether to post a result notification at all
 * @param silentNotify  post the notification without sound/vibration (HIGH vs LOW importance)
 * @param captureOutput if false, fire-and-forget (no output collected; notification just
 *                      reports started/exit code). Useful for long jobs you don't want to wait on.
 * @param timeoutSec    override the mode's default timeout
 * @param tag           optional label shown in the notification title (e.g. "Kai", "Poll-E")
 */
data class ExecOptions(
    val notify: Boolean = true,
    val silentNotify: Boolean = false,
    val captureOutput: Boolean = true,
    val timeoutSec: Long? = null,
    val tag: String? = null
)

// ---------- result ----------

sealed class ExecutionResult {
    data class Success(val output: String, val exitCode: Int) : ExecutionResult()
    data class Error(val message: String) : ExecutionResult()
    object Timeout : ExecutionResult()
    object Started : ExecutionResult()   // fire-and-forget acknowledgement
}

// ---------- parsed command ----------

/**
 * The result of parsing raw input text: which mode, which flags, and the actual
 * command with the prefix/flag tokens stripped off.
 */
data class ParsedCommand(
    val mode: ExecutionMode,
    val command: String,
    val options: ExecOptions
)

/**
 * CommandParser
 *
 * Turns raw text into a ParsedCommand. The DSL is intentionally tiny and
 * forgiving — leading tokens are consumed as long as they're recognised, then
 * everything else is the command verbatim.
 *
 * Supported leading tokens (any order, all optional):
 *   @local | @comrade        -> pick the target mode
 *   !silent                  -> silent notification
 *   !nonotify                -> suppress notification entirely
 *   !bg                      -> fire-and-forget (captureOutput = false)
 *   !tag=NAME                -> label the notification (no spaces in NAME)
 *
 * Anything after the recognised tokens is the command, untouched.
 * If no @mode token is present, `defaultMode` (from the toggled button) is used.
 *
 * Examples:
 *   "@comrade !bg !tag=train python train.py"  -> remote, fire-and-forget, tagged "train"
 *   "ls -la"                                    -> uses defaultMode, normal notify
 *   "@local !silent df -h"                      -> local, silent notification
 */
object CommandParser {

    fun parse(raw: String, defaultMode: ExecutionMode): ParsedCommand {
        val trimmed = raw.trim()
        var mode: ExecutionMode? = null
        var notify = true
        var silent = false
        var capture = true
        var tag: String? = null

        // Tokenize only the leading run of recognised tokens.
        val tokens = trimmed.split(Regex("\\s+"))
        var consumed = 0
        for (tok in tokens) {
            val modeMatch = ExecutionMode.fromPrefix(tok)
            when {
                modeMatch != null -> { mode = modeMatch; consumed++ }
                tok == "!silent" -> { silent = true; consumed++ }
                tok == "!nonotify" -> { notify = false; consumed++ }
                tok == "!bg" -> { capture = false; consumed++ }
                tok.startsWith("!tag=") -> { tag = tok.removePrefix("!tag="); consumed++ }
                else -> { /* first non-token = start of the real command */ }
            }
            if (ExecutionMode.fromPrefix(tok) == null &&
                tok != "!silent" && tok != "!nonotify" && tok != "!bg" &&
                !tok.startsWith("!tag=")
            ) break
        }

        val command = tokens.drop(consumed).joinToString(" ").trim()

        return ParsedCommand(
            mode = mode ?: defaultMode,
            command = command,
            options = ExecOptions(
                notify = notify,
                silentNotify = silent,
                captureOutput = capture,
                tag = tag
            )
        )
    }
}

// ---------- the executor ----------

class SpectreBoardExecutor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    /**
     * [E1] Optional PATH to prepend so common Termux binaries resolve from the
     * keyboard's process. Leave null to inherit whatever the process already has.
     * Typical Termux prefix: /data/data/com.termux/files/usr/bin
     */
    var localEnvPath: String? = "/data/data/com.termux/files/usr/bin"

    private val notifIdCounter = AtomicInteger(1000)

    init {
        ensureChannels()
    }

    /**
     * THE main entry point. Hand it raw text + the button-toggled default mode.
     * It parses the prefix DSL, runs on the right target, and posts a notification.
     *
     * This is what the toolbar buttons AND other suite apps call.
     */
    fun dispatch(rawText: String, defaultMode: ExecutionMode) {
        val parsed = CommandParser.parse(rawText, defaultMode)
        if (parsed.command.isBlank()) {
            postNotification(parsed.options, "Nothing to run", "Empty command after parsing.", isError = true)
            return
        }

        val timeout = parsed.options.timeoutSec ?: parsed.mode.defaultTimeoutSec

        scope.launch {
            if (!parsed.options.captureOutput) {
                // fire-and-forget: launch, immediately ack, let it run detached
                launch { runCommand(parsed, timeout) }  // result ignored
                postResult(parsed, ExecutionResult.Started)
                return@launch
            }
            val result = runCommand(parsed, timeout)
            postResult(parsed, result)
        }
    }

    // convenience wrappers for the two toolbar buttons
    fun runLocal(rawText: String) = dispatch(rawText, ExecutionMode.LOCAL)
    fun runRemote(rawText: String) = dispatch(rawText, ExecutionMode.COMRADE)

    // ---------- execution ----------

    private suspend fun runCommand(parsed: ParsedCommand, timeoutSec: Long): ExecutionResult {
        return withTimeoutOrNull(TimeUnit.SECONDS.toMillis(timeoutSec)) {
            try {
                val shellCmd = if (parsed.mode.isRemote) {
                    // [E2][E3][E5] key-based SSH, host must resolve, ssh binary must exist
                    "ssh ${parsed.mode.sshUser}@${parsed.mode.sshHost} bash -c ${singleQuote(parsed.command)}"
                } else {
                    parsed.command
                }

                val pb = ProcessBuilder("sh", "-c", shellCmd).redirectErrorStream(true)
                // [E1] prepend Termux bin to PATH for local runs if configured
                if (!parsed.mode.isRemote && localEnvPath != null) {
                    val env = pb.environment()
                    env["PATH"] = "${localEnvPath}:${env["PATH"] ?: ""}"
                }

                val process = pb.start()
                val output = BufferedReader(InputStreamReader(process.inputStream))
                    .use { it.readText() }
                val exit = process.waitFor()
                ExecutionResult.Success(output, exit)
            } catch (e: Exception) {
                ExecutionResult.Error(e.message ?: "unknown error")
            }
        } ?: ExecutionResult.Timeout
    }

    /** Wrap a command in single quotes for safe nesting inside the ssh bash -c arg. */
    private fun singleQuote(s: String): String {
        // Close-quote, escaped-quote, reopen — the standard POSIX trick for ' inside '...'
        val escaped = s.replace("'", "'\\''")
        return "'$escaped'"
    }

    // ---------- notifications ----------

    private fun postResult(parsed: ParsedCommand, result: ExecutionResult) {
        if (!parsed.options.notify) return
        val tagPart = parsed.options.tag?.let { "[$it] " } ?: ""
        val target = parsed.mode.name.lowercase()

        val (title, body, isErr) = when (result) {
            is ExecutionResult.Success -> {
                val ok = result.exitCode == 0
                Triple(
                    "${tagPart}${if (ok) "✓" else "✗"} $target (exit ${result.exitCode})",
                    result.output.ifBlank { "(no output)" }.take(800),
                    !ok
                )
            }
            is ExecutionResult.Error ->
                Triple("${tagPart}✗ $target error", result.message, true)
            ExecutionResult.Timeout ->
                Triple("${tagPart}✗ $target timeout", "Command exceeded its time limit.", true)
            ExecutionResult.Started ->
                Triple("${tagPart}▶ $target started", "Running in background (no output captured).", false)
        }
        postNotification(parsed.options, title, body, isErr)
    }

    private fun postNotification(opts: ExecOptions, title: String, body: String, isError: Boolean) {
        if (!opts.notify) return
        val channel = if (opts.silentNotify) CHANNEL_SILENT else CHANNEL_DEFAULT
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_menu_send) // ASSUMPTION: swap for a SpectreBoard icon
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentText(body.take(120))
            .setAutoCancel(true)
            .setPriority(if (opts.silentNotify) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)

        try {
            NotificationManagerCompat.from(context)
                .notify(notifIdCounter.incrementAndGet(), builder.build())
        } catch (e: SecurityException) {
            // [E4] POST_NOTIFICATIONS not granted — nothing we can do here but swallow.
        }
    }

    private fun ensureChannels() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_DEFAULT, "SpectreBoard Commands", NotificationManager.IMPORTANCE_DEFAULT)
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_SILENT, "SpectreBoard Commands (silent)", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_DEFAULT = "spectreboard_exec"
        private const val CHANNEL_SILENT = "spectreboard_exec_silent"
    }
}

/**
 * ===== INTEGRATION NOTES FOR DEEPSEEK =====
 *
 * Toolbar wiring:
 *   val executor = SpectreBoardExecutor(context)
 *   // "Execute local" button onClick:
 *   executor.runLocal(currentInputText())
 *   // "Execute remote" button onClick:
 *   executor.runRemote(currentInputText())
 *
 * The default-mode toggle (the on/off "execute mode" switch) just decides which
 * of runLocal/runRemote the SEND action calls when there's no @prefix in the text.
 *
 * Suite integration (Kai / Poll-E / Whisper):
 *   Any app emits a prefixed string and SpectreBoard routes it. No new API:
 *     "@comrade !bg !tag=train python ~/train.py"
 *     "@local !silent ls -la"
 *   This keeps the contract in TEXT, so adding a new producer needs zero
 *   keyboard changes — they just write the right prefix.
 *
 * Adding a new host (e.g. a second box): add one enum entry to ExecutionMode
 * with its @prefix, sshUser, sshHost. Everything else (parser, notifications,
 * routing) picks it up automatically.
 *
 * Permissions to add to the manifest / request at runtime:
 *   - android.permission.POST_NOTIFICATIONS (runtime, Android 13+)  [E4]
 *
 * Things to verify before trusting output: [E1]-[E5] at the top of this file.
 * The two most likely silent failures are [E1] PATH (local binaries not found)
 * and [E2] SSH passphrase (remote hangs to timeout).
 */
