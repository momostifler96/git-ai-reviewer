package com.github.momostifler96.gitaireviewer.actions

import com.github.momostifler96.gitaireviewer.ai.AiClient
import com.github.momostifler96.gitaireviewer.ai.ChatMessage
import com.github.momostifler96.gitaireviewer.git.GitService
import com.github.momostifler96.gitaireviewer.prompts.renderSystemPrompt
import com.github.momostifler96.gitaireviewer.settings.ApiKeyStore
import com.github.momostifler96.gitaireviewer.settings.AppSettings
import com.github.momostifler96.gitaireviewer.ui.Markdown
import com.github.momostifler96.gitaireviewer.ui.Notifications
import com.github.momostifler96.gitaireviewer.ui.ReviewDisplayService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

class ReviewUncommittedAction :
    AnAction("Review Uncommitted Changes (AI)", "AI review of the uncommitted changes", null) {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val settings = AppSettings.getInstance()
        val provider = settings.reviewProvider()
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
        val changes = GitService.collectDiff(root, GitService.Mode.ALL, state.includeUntracked, state.diffMaxChars)
        if (changes.diff.isBlank()) {
            Notifications.info(project, "Git AI: no uncommitted changes to review.")
            return
        }

        val systemPrompt = renderSystemPrompt(state.reviewSystemPrompt, state.outputLanguage)
        val userPrompt = buildUserPrompt(changes)
        val timeoutMs = state.timeoutMs.toLong()

        object : Task.Backgroundable(
            project,
            "Git AI: reviewing ${changes.files.size} file(s) with ${provider.name} (${provider.model})…",
            true,
        ) {
            private var report: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val apiKey = ApiKeyStore.get(provider.name)
                report = AiClient.complete(
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
                val markdown = report ?: return
                val html = Markdown.toHtml(
                    header = "${changes.files.size} file(s) · branch ${changes.branch} · " +
                        "${provider.name} · ${provider.model}",
                    markdown = markdown,
                )
                ReviewDisplayService.getInstance(project).show(html)
            }

            override fun onThrowable(error: Throwable) {
                Notifications.error(project, error.message ?: "Git AI: the AI request failed.")
            }
        }.queue()
    }

    private fun buildUserPrompt(changes: GitService.DiffResult): String {
        val sections = mutableListOf(
            "Review the following uncommitted changes (working tree of branch \"${changes.branch}\").",
        )
        if (changes.stats.isNotBlank()) {
            sections += listOf("Diffstat:", "```", changes.stats.trim(), "```")
        }
        if (changes.truncated) {
            sections += "WARNING: the diff below was truncated; review only what is visible."
        }
        sections += listOf("Diff:", "```diff", changes.diff, "```")
        return sections.joinToString("\n")
    }
}
