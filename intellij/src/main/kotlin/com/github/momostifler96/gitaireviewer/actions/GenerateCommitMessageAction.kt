package com.github.momostifler96.gitaireviewer.actions

import com.github.momostifler96.gitaireviewer.ai.AiClient
import com.github.momostifler96.gitaireviewer.ai.ChatMessage
import com.github.momostifler96.gitaireviewer.git.GitService
import com.github.momostifler96.gitaireviewer.prompts.DEFAULT_DIFF_SUMMARY_PROMPT
import com.github.momostifler96.gitaireviewer.prompts.renderSystemPrompt
import com.github.momostifler96.gitaireviewer.settings.ApiKeyStore
import com.github.momostifler96.gitaireviewer.settings.AppSettings
import com.github.momostifler96.gitaireviewer.ui.CommitMessageDialog
import com.github.momostifler96.gitaireviewer.ui.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

class GenerateCommitMessageAction :
    AnAction("Generate Commit Message (AI)", "AI-generated commit message from the staged changes", null) {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val settings = AppSettings.getInstance()
        val provider = settings.commitProvider()
        if (provider == null) {
            Notifications.error(project, "Git AI: no AI provider is configured. Configure one in Settings > Tools > Git AI Reviewer.")
            return
        }
        val root = GitService.repoRoot(project)
        if (root == null) {
            Notifications.warning(project, "Git AI: no Git repository found for this project.")
            return
        }

        val state = settings.state
        var changes = GitService.collectDiff(
            root,
            GitService.Mode.STAGED,
            includeUntracked = false,
            maxChars = state.diffMaxChars,
            chunking = state.chunkingMode,
        )
        if (changes.chunks.none { it.isNotBlank() }) {
            val all = GitService.collectDiff(
                root,
                GitService.Mode.ALL,
                state.includeUntracked,
                state.diffMaxChars,
                state.chunkingMode,
            )
            if (all.chunks.none { it.isNotBlank() }) {
                Notifications.info(project, "Git AI: nothing to commit — no staged or uncommitted changes.")
                return
            }
            val useAll = Messages.showYesNoDialog(
                project,
                "Nothing is staged yet. Generate the commit message from ALL uncommitted changes\n(staged + unstaged + new files)?",
                "Git AI",
                Messages.getQuestionIcon(),
            )
            if (useAll != Messages.YES) {
                return
            }
            changes = all
        }

        val systemPrompt = renderSystemPrompt(state.commitSystemPrompt, state.outputLanguage)
        val summaryPrompt = renderSystemPrompt(DEFAULT_DIFF_SUMMARY_PROMPT, state.outputLanguage)
        val timeoutMs = state.timeoutMs.toLong()
        val partSuffix =
            if (changes.chunks.size > 1) " from ${changes.chunks.size} parts…" else "…"

        object : Task.Backgroundable(
            project,
            "Git AI: drafting commit message with ${provider.name} (${provider.model})$partSuffix",
            true,
        ) {
            private var message = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val apiKey = ApiKeyStore.get(provider.name)

                if (changes.chunks.size == 1) {
                    message = AiClient.complete(
                        provider, apiKey,
                        listOf(
                            ChatMessage("system", systemPrompt),
                            ChatMessage("user", buildCommitUserPrompt(changes, 0)),
                        ),
                        timeoutMs,
                    ).let(::cleanCommitMessage)
                    return
                }

                // Multi-part flow: summarize each part, then write one message from the summaries.
                val summaries = mutableListOf<String>()
                changes.chunks.forEachIndexed { index, _ ->
                    indicator.fraction = index.toDouble() / (changes.chunks.size + 1)
                    indicator.text = "summarizing part ${index + 1}/${changes.chunks.size}"
                    summaries += AiClient.complete(
                        provider, apiKey,
                        listOf(
                            ChatMessage("system", summaryPrompt),
                            ChatMessage("user", buildSummaryUserPrompt(changes, index)),
                        ),
                        timeoutMs,
                    )
                }

                indicator.fraction = 0.95
                indicator.text = "drafting commit message"
                message = AiClient.complete(
                    provider, apiKey,
                    listOf(
                        ChatMessage("system", systemPrompt),
                        ChatMessage("user", buildCommitFromSummariesPrompt(changes, summaries)),
                    ),
                    timeoutMs,
                ).let(::cleanCommitMessage)
            }

            override fun onSuccess() {
                if (message.isBlank()) {
                    Notifications.error(project, "Git AI: the provider returned an empty commit message.")
                    return
                }
                val dialog = CommitMessageDialog(project, message)
                dialog.show()
                if (dialog.isOK) {
                    Notifications.info(
                        project,
                        "Git AI: commit message copied to the clipboard — paste it into the commit message field (Ctrl+Shift+V).",
                    )
                }
            }

            override fun onThrowable(error: Throwable) {
                Notifications.error(project, error.message ?: "Git AI: the AI request failed.")
            }
        }.queue()
    }

    private fun buildCommitUserPrompt(changes: GitService.DiffResult, index: Int): String {
        val sections = mutableListOf("Write the commit message for the following changes.")
        if (changes.chunks.size > 1) {
            sections += "(This is part ${index + 1} of ${changes.chunks.size} of the diff.)"
        }
        if (changes.stats.isNotBlank()) {
            sections += listOf("Diffstat:", "```", changes.stats.trim(), "```")
        }
        if (changes.truncated) {
            sections += "WARNING: the diff was truncated."
        }
        sections += listOf("Diff:", "```diff", changes.chunks[index], "```")
        return sections.joinToString("\n")
    }

    private fun buildSummaryUserPrompt(changes: GitService.DiffResult, index: Int): String {
        return listOf(
            "Here is part ${index + 1} of ${changes.chunks.size} of the diff to commit:",
            "```diff",
            changes.chunks[index],
            "```",
        ).joinToString("\n")
    }

    private fun buildCommitFromSummariesPrompt(
        changes: GitService.DiffResult,
        summaries: List<String>,
    ): String {
        val sections = mutableListOf(
            "The changes to commit were too large for a single request.",
            "Here is a summary of each part of the diff:",
        )
        summaries.forEachIndexed { index, summary ->
            sections += "\nPart ${index + 1}:\n${summary.trim()}"
        }
        if (changes.stats.isNotBlank()) {
            sections += listOf("\nDiffstat of the full change set:", "```", changes.stats.trim(), "```")
        }
        sections += "\nWrite the commit message for these changes."
        return sections.joinToString("\n")
    }

    private fun cleanCommitMessage(raw: String): String {
        val text = raw.trim()
        val fenced = Regex("^```[^\\n]*\\n([\\s\\S]*?)\\n?```\\s*$").find(text)
        return (fenced?.groupValues?.get(1) ?: text).trim()
    }
}
