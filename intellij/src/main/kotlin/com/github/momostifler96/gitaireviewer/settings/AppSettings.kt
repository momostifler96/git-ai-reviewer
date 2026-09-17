package com.github.momostifler96.gitaireviewer.settings

import com.github.momostifler96.gitaireviewer.prompts.DEFAULT_COMMIT_PROMPT
import com.github.momostifler96.gitaireviewer.prompts.DEFAULT_REVIEW_PROMPT
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@State(name = "GitAiReviewSettings", storages = [Storage("gitAiReview.xml")])
@Service(Level.APP)
class AppSettings : PersistentStateComponent<AppSettings.State> {

    data class State(
        var providers: MutableList<AiProvider> = mutableListOf(DEFAULT_PROVIDER),
        var activeProvider: String = "openai",
        var reviewProvider: String = "",
        var commitProvider: String = "",
        var outputLanguage: String = "English",
        var reviewSystemPrompt: String = DEFAULT_REVIEW_PROMPT,
        var commitSystemPrompt: String = DEFAULT_COMMIT_PROMPT,
        var diffMaxChars: Int = 60_000,
        var chunkingMode: String = "chunk",
        var includeUntracked: Boolean = true,
        var timeoutMs: Int = 300_000,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /** Providers with all required fields, in declaration order. */
    val providers: List<AiProvider>
        get() = myState.providers.filter { it.isComplete }

    val activeProviderName: String
        get() {
            val complete = providers
            return complete.firstOrNull { it.name == myState.activeProvider }?.name
                ?: complete.firstOrNull()?.name
                ?: ""
        }

    fun reviewProvider(): AiProvider? =
        providers.firstOrNull { it.name == myState.reviewProvider }
            ?: providers.firstOrNull { it.name == activeProviderName }

    fun commitProvider(): AiProvider? =
        providers.firstOrNull { it.name == myState.commitProvider }
            ?: providers.firstOrNull { it.name == activeProviderName }

    companion object {
        val DEFAULT_PROVIDER = AiProvider(
            name = "openai",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
        )

        fun getInstance(): AppSettings = service()
    }
}
