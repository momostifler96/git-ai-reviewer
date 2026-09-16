package com.github.momostifler96.gitaireviewer.actions

import com.github.momostifler96.gitaireviewer.ai.AiClient
import com.github.momostifler96.gitaireviewer.ai.ChatMessage
import com.github.momostifler96.gitaireviewer.git.GitService
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
        var changes = GitService.collectDiff(root, GitService.Mode.STAGED, false, state.diffMaxChars)
        if (changes.diff.isBlank()) {
            val all = GitService.collectDiff(root, GitService.Mode.ALL, state.includeUntracked, state.diffMaxChars)
            if (all.diff.isBlank()) {
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
        val userPrompt = buildUserPrompt(changes)
        val timeoutMs = state.timeoutMs.toLong()

        object : Task.Backgroundable(
            project,
            "Git AI: drafting commit message with ${provider.name} (${provider.model})…",
            true,
        ) {
            private var raw: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val apiKey = ApiKeyStore.get(provider.name)
                raw = AiClient.complete(
                    provider = provider,
                    apiKey = apiKey,
                    messages = listOf(
                        ChatMessage("system", systemPrompt),
                        ChatMessage("user", userPrompt),
                    ),
                    timeoutMs = timeoutMs,
                )
            }

            override fun onSuccess() {
                val message = cleanCommitMessage(raw ?: return)
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

    private fun buildUserPrompt(changes: GitService.DiffResult): String {
        val sections = mutableListOf("Write the commit message for the following changes.")
        if (changes.stats.isNotBlank()) {
            sections += listOf("Diffstat:", "```", changes.stats.trim(), "```")
        }
        if (changes.truncated) {
            sections += "WARNING: the diff was truncated."
        }
        sections += listOf("Diff:", "```diff", changes.diff, "```")
        return sections.joinToString("\n")
    }

    private fun cleanCommitMessage(raw: String): String {
        val text = raw.trim()
        val fenced = Regex("^```[^\\n]*\\n([\\s\\S]*?)\\n?```\\s*$").find(text)
        return (fenced?.groupValues?.get(1) ?: text).trim()
    }
}
