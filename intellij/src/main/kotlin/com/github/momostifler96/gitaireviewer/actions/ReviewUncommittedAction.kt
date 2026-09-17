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
        val changes = GitService.collectDiff(
            root,
            GitService.Mode.ALL,
            state.includeUntracked,
            state.diffMaxChars,
            state.chunkingMode,
        )
        if (changes.chunks.none { it.isNotBlank() }) {
            Notifications.info(project, "Git AI: no uncommitted changes to review.")
            return
        }

        val systemPrompt = renderSystemPrompt(state.reviewSystemPrompt, state.outputLanguage)
        val timeoutMs = state.timeoutMs.toLong()
        val partSuffix =
            if (changes.chunks.size > 1) " in ${changes.chunks.size} parts…" else "…"

        object : Task.Backgroundable(
            project,
            "Git AI: reviewing ${changes.files.size} file(s) with ${provider.name} (${provider.model})$partSuffix",
            true,
        ) {
            private var report: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val apiKey = ApiKeyStore.get(provider.name)
                val reports = mutableListOf<String>()
                changes.chunks.forEachIndexed { index, chunk ->
                    indicator.fraction = index.toDouble() / changes.chunks.size
                    indicator.text = "part ${index + 1}/${changes.chunks.size}"
                    reports += AiClient.complete(
                        provider = provider,
                        apiKey = apiKey,
                        messages = listOf(
                            ChatMessage("system", systemPrompt),
                            ChatMessage("user", buildReviewUserPrompt(changes, index)),
                        ),
                        timeoutMs = timeoutMs,
                    )
                }
                report = reports.joinToString("\n\n---\n\n")
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

    private fun buildReviewUserPrompt(changes: GitService.DiffResult, index: Int): String {
        val total = changes.chunks.size
        val sections = mutableListOf<String>()
        if (total > 1) {
            sections += "Review the following uncommitted changes (working tree of branch \"${changes.branch}\"). " +
                "This is part ${index + 1} of $total of the full diff — the other parts are sent in " +
                "separate requests; review only what is visible here."
        } else {
            sections += "Review the following uncommitted changes (working tree of branch \"${changes.branch}\")."
        }
        if (changes.stats.isNotBlank()) {
            sections += listOf("Diffstat of the full change set:", "```", changes.stats.trim(), "```")
        }
        if (changes.truncated) {
            sections += "WARNING: the diff below was truncated; review only what is visible."
        }
        sections += listOf("Diff:", "```diff", changes.chunks[index], "```")
        return sections.joinToString("\n")
    }
}
