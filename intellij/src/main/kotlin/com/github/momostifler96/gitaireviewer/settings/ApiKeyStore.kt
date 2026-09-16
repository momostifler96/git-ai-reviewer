package com.github.momostifler96.gitaireviewer.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.Callable
import javax.swing.SwingUtilities

/** Stores provider API keys in the IDE password safe (Keychain / Credential Manager / KeePass). */
object ApiKeyStore {

    private fun attributes(providerName: String) =
        CredentialAttributes("gitAiReview.apiKey.$providerName")

    private fun passwordSafe(): PasswordSafe =
        ApplicationManager.getApplication().getService(PasswordSafe::class.java)
            ?: throw RuntimeException("PasswordSafe service is not available")

    /**
     * PasswordSafe refuses access from the EDT, so when called on the EDT the call is
     * dispatched to a pooled thread and the (fast, local) result is awaited.
     */
    private fun <T> offEdt(block: () -> T): T {
        if (!SwingUtilities.isEventDispatchThread()) return block()
        return ApplicationManager.getApplication().executeOnPooledThread(Callable(block)).get()
    }

    fun get(providerName: String): String? = offEdt {
        passwordSafe().get(attributes(providerName))?.getPasswordAsString()
    }

    fun set(providerName: String, apiKey: String?) = offEdt {
        val attributes = attributes(providerName)
        if (apiKey.isNullOrBlank()) {
            passwordSafe().set(attributes, null)
        } else {
            passwordSafe().set(attributes, Credentials(null, apiKey))
        }
    }
}
