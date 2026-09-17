package com.github.momostifler96.gitaireviewer.git

/**
 * Pure diff-chunking helpers: split a large diff into coherent parts that each fit
 * within a character budget, so each part can be sent in its own AI request.
 *
 * Splitting is hierarchical: per file first (`diff --git` boundaries), then per hunk
 * (`@@` boundaries, repeating the file header so every part stays self-describing),
 * and only as a last resort per line.
 */
object Chunking {

    fun splitIntoFileBlocks(outputs: List<String>): List<String> {
        val blocks = mutableListOf<String>()
        for (output in outputs) {
            if (output.isBlank()) continue
            val current = mutableListOf<String>()
            for (line in output.split('\n')) {
                if (line.startsWith("diff --git ") && current.isNotEmpty()) {
                    blocks += current.joinToString("\n")
                    current.clear()
                }
                current += line
            }
            if (current.isNotEmpty()) {
                blocks += current.joinToString("\n")
            }
        }
        return blocks.filter { it.isNotBlank() }
    }

    private fun hardSplitLines(text: String, maxChars: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = mutableListOf<String>()
        var length = 0
        for (line in text.split('\n')) {
            if (current.isNotEmpty() && length + line.length + 1 > maxChars) {
                chunks += current.joinToString("\n")
                current.clear()
                length = 0
            }
            current += line
            length += line.length + 1
        }
        if (current.isNotEmpty()) {
            chunks += current.joinToString("\n")
        }
        return chunks
    }

    private fun splitOversizedBlock(block: String, maxChars: Int): List<String> {
        val lines = block.split('\n')
        val headerLines = mutableListOf<String>()
        var index = 0
        while (index < lines.size && !lines[index].startsWith("@@ ")) {
            headerLines += lines[index]
            index++
        }
        // No hunk (binary file, mode change…) — only line-level splitting is possible.
        if (index >= lines.size) {
            return hardSplitLines(block, maxChars)
        }
        val header = headerLines.joinToString("\n")
        val pieces = mutableListOf<String>()
        val current = mutableListOf<String>()
        var length = header.length
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("@@ ") && current.isNotEmpty() && length + line.length + 1 > maxChars) {
                pieces += header + "\n" + current.joinToString("\n")
                current.clear()
                length = header.length
            }
            current += line
            length += line.length + 1
            index++
        }
        if (current.isNotEmpty()) {
            pieces += header + "\n" + current.joinToString("\n")
        }
        return pieces.flatMap { piece ->
            if (piece.length <= maxChars) listOf(piece) else hardSplitLines(piece, maxChars)
        }
    }

    fun buildChunks(blocks: List<String>, maxChars: Int): List<String> {
        val pieces = mutableListOf<String>()
        for (block in blocks) {
            if (block.length <= maxChars) {
                pieces += block
            } else {
                pieces += splitOversizedBlock(block, maxChars)
            }
        }
        val chunks = mutableListOf<String>()
        var current = ""
        for (piece in pieces) {
            if (current.isEmpty()) {
                current = piece
                continue
            }
            if (current.length + piece.length + 1 <= maxChars) {
                current += "\n$piece"
            } else {
                chunks += current
                current = piece
            }
        }
        if (current.isNotEmpty()) {
            chunks += current
        }
        return chunks
    }
}
