package app.granger.core.ai

import java.io.File

/** Why the librarian model failed its first check on this phone (release checklist A: hide Ask and say why). */
enum class CheckFailure {
    /** Granger stopped while the model was loading or answering its check: a native crash, or the app was closed. */
    CRASHED,
    /** The model threw while loading. */
    LOAD_FAILED,
    /** The model loaded but its check reply was wrong (a broken GPU driver writes garbage). */
    WRONG_REPLY,
    /** The check reply didn't come within the time allowed. */
    TOO_SLOW,
}

/**
 * What the self-check knows per model file, kept in [file] (one `key=state` line each) so a check under way survives
 * a crash: a key still `running` at the next launch is a [CheckFailure.CRASHED]. Every write goes to disk before the
 * model is touched. A [file] of null keeps nothing (no self-check, as in tests).
 */
class ModelCheckStore(private val file: File?) {
    sealed interface State {
        data object Running : State
        data object Passed : State
        data class Failed(val why: CheckFailure) : State
    }

    private val states = LinkedHashMap<String, State>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { f.readLines() }.getOrDefault(emptyList()).forEach { line ->
                val key = line.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@forEach
                states[key] = parse(line.substringAfter('=')) ?: return@forEach
            }
        }
        // A check under way when the process died: the model crashed it (or the app was closed meanwhile).
        val interrupted = states.filterValues { it == State.Running }.keys
        if (interrupted.isNotEmpty()) {
            interrupted.forEach { states[it] = State.Failed(CheckFailure.CRASHED) }
            save()
        }
    }

    val enabled: Boolean get() = file != null

    @Synchronized fun get(key: String): State? = states[key]

    @Synchronized fun set(key: String, state: State) {
        states[key] = state
        save()
    }

    /** Try again: forgets [key]'s failure, so the next load checks it afresh. */
    @Synchronized fun clear(key: String) {
        if (states.remove(key) != null) save()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(states.entries.joinToString("") { (k, v) -> "$k=${format(v)}\n" }.toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private fun format(state: State) = when (state) {
        State.Running -> "running"
        State.Passed -> "passed"
        is State.Failed -> "failed:${state.why.name}"
    }

    private fun parse(s: String): State? = when {
        s == "running" -> State.Running
        s == "passed" -> State.Passed
        s.startsWith("failed:") -> runCatching { State.Failed(CheckFailure.valueOf(s.removePrefix("failed:"))) }.getOrNull()
        else -> null
    }

    companion object {
        /** A model file's key: its name and size, so a new download of the same name is checked again. */
        fun keyOf(file: File) = "${file.name}:${file.length()}"
    }
}

/** The self-check's question and how its reply is judged: a short sum any working model gets right. */
object SelfCheck {
    const val PROMPT = "What is 2 + 2? Reply with the number only."
    const val MAX_TOKENS = 8
    const val TIMEOUT_MS = 60_000L

    fun passes(reply: String): Boolean {
        val r = reply.lowercase()
        return Regex("(?<!\\d)4(?!\\d)").containsMatchIn(r) || "four" in r
    }
}

/** The model is barred on this phone by its self-check ([why]). */
class ModelCheckFailedException(val why: CheckFailure) : Exception("The model failed its self-check: $why")
