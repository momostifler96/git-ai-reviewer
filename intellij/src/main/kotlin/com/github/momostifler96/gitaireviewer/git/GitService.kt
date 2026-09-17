package com.github.momostifler96.gitaireviewer.git

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

/** Collects uncommitted changes by invoking the git CLI (same logic as the VS Code edition). */
object GitService {

    enum class Mode { STAGED, ALL }

    data class DiffResult(
        /** Coherent parts of the diff — one entry per AI request. */
        val chunks: List<String>,
        val files: List<String>,
        val stats: String,
        val branch: String,
        /** True only when the content was actually cut (chunking mode "truncate"). */
        val truncated: Boolean,
    )

    private const val MAX_UNTRACKED_FILES = 100
    private const val MAX_UNTRACKED_LINES = 1500

    fun repoRoot(project: Project): String? {
        val base = project.basePath ?: return null
        val (code, output) = run(base, "rev-parse", "--show-toplevel") ?: return null
        return if (code == 0) output.trim().ifEmpty { null } else null
    }

    private fun run(root: String, vararg args: String): Pair<Int, String>? {
        return try {
            val process = ProcessBuilder("git", *args)
                .directory(File(root))
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.errorStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            Pair(process.exitValue(), output)
        } catch (e: Exception) {
            null
        }
    }

    private fun hasHead(root: String): Boolean =
        run(root, "rev-parse", "--verify", "--quiet", "HEAD")?.first == 0

    private fun currentBranch(root: String): String =
        run(root, "rev-parse", "--abbrev-ref", "HEAD")?.second?.trim().orEmpty()

    private fun isBinary(bytes: ByteArray): Boolean =
        bytes.copyOfRange(0, minOf(bytes.size, 8000)).contains(0)

    private fun untrackedDiff(root: String, relativePath: String): String? {
        val file = File(root, relativePath)
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return null
        }
        val header = buildString {
            append("diff --git a/").append(relativePath).append(" b/").append(relativePath).append('\n')
            append("new file mode 100644\n")
            append("--- /dev/null\n")
            append("+++ b/").append(relativePath).append('\n')
        }
        if (isBinary(bytes)) {
            return header + "@@ -0,0 +1 @@\n+[binary file not shown]\n"
        }
        val lines = file.readText().split(Regex("\r?\n"))
            .toMutableList()
            .apply { if (isNotEmpty() && last().isEmpty()) removeAt(size - 1) }
        val truncated = lines.size > MAX_UNTRACKED_LINES
        val shown = if (truncated) lines.subList(0, MAX_UNTRACKED_LINES) else lines
        val body = shown.joinToString("\n") { "+$it" }
        val footer = if (truncated) "\n+... [file truncated after $MAX_UNTRACKED_LINES lines]" else ""
        return header + "@@ -0,0 +1," + shown.size + " @@\n$body\n$footer\n"
    }

    fun collectDiff(
        root: String,
        mode: Mode,
        includeUntracked: Boolean,
        maxChars: Int,
        chunking: String = "chunk",
    ): DiffResult {
        val withHead = hasHead(root)
        val branch = currentBranch(root)

        val outputs = mutableListOf<String>()
        val stats = mutableListOf<String>()
        val files = sortedSetOf<String>()

        fun push(baseArgs: List<String>) {
            val patch = run(root, *(listOf("diff") + baseArgs).toTypedArray())?.second.orEmpty()
            val names = (run(root, *(listOf("diff") + baseArgs + listOf("--name-only")).toTypedArray())?.second.orEmpty())
                .lines().map { it.trim() }.filter { it.isNotEmpty() }
            val stat = (run(root, *(listOf("diff") + baseArgs + listOf("--stat")).toTypedArray())?.second.orEmpty()).trim()
            if (patch.isNotBlank()) outputs += patch
            files += names
            if (stat.isNotEmpty()) stats += stat
        }

        when {
            mode == Mode.ALL && withHead -> push(listOf("HEAD"))
            mode == Mode.ALL -> {
                push(listOf("--cached"))
                push(emptyList())
            }
            else -> push(listOf("--cached"))
        }

        if (mode == Mode.ALL && includeUntracked) {
            val listed = run(root, "ls-files", "--others", "--exclude-standard")?.second.orEmpty()
            val untracked = listed.lines().map { it.trim() }.filter { it.isNotEmpty() }
            for (item in untracked.take(MAX_UNTRACKED_FILES)) {
                val patch = untrackedDiff(root, item)
                if (patch != null) {
                    outputs += patch
                    files += item
                }
            }
        }

        val blocks = Chunking.splitIntoFileBlocks(outputs)
        val statsText = stats.joinToString("\n")
        val branchText = branch.ifEmpty { "(no commits yet)" }

        if (chunking == "truncate") {
            val joined = blocks.joinToString("\n")
            return if (joined.length <= maxChars) {
                DiffResult(listOf(joined), files.toList(), statsText, branchText, truncated = false)
            } else {
                DiffResult(
                    chunks = listOf(joined.substring(0, maxChars) + "\n\n[... diff truncated at $maxChars characters ...]"),
                    files = files.toList(),
                    stats = statsText,
                    branch = branchText,
                    truncated = true,
                )
            }
        }

        return DiffResult(
            chunks = Chunking.buildChunks(blocks, maxChars),
            files = files.toList(),
            stats = statsText,
            branch = branchText,
            truncated = false,
        )
    }
}
