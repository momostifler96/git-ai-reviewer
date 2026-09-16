package com.github.momostifler96.gitaireviewer.settings

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPasswordField

/** Add/Edit dialog for one provider. The API key is stored in the IDE password safe. */
class ProviderDialog(private val provider: AiProvider) : DialogWrapper(true) {

    private val nameField = JBTextField(provider.name)
    private val baseUrlField = JBTextField(provider.baseUrl)
    private val modelField = JBTextField(provider.model)
    private val temperatureField = JBTextField(provider.temperature?.toString() ?: "")
    private val maxTokensField = JBTextField(provider.maxTokens?.toString() ?: "")
    private val headersField = JBTextField(provider.headers)
    private val apiKeyField = JPasswordField(ApiKeyStore.get(provider.name).orEmpty())
    private val removeKeyCheckBox = JBCheckBox("Remove the stored API key")

    init {
        title = "AI Provider"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Name (unique)", nameField)
        .addLabeledComponent("Base URL (OpenAI-compatible)", baseUrlField)
        .addLabeledComponent("Model", modelField)
        .addLabeledComponent("Temperature (optional, e.g. 0.2)", temperatureField)
        .addLabeledComponent("Max tokens (optional)", maxTokensField)
        .addLabeledComponent(
            "Extra headers — 'Name: Value' pairs separated by ';'",
            headersField,
        )
        .addTooltip("Example: HTTP-Referer: https://myapp.dev; X-Title: My App")
        .addLabeledComponent("API key (stored in the IDE password safe)", apiKeyField)
        .addComponent(removeKeyCheckBox)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
        if (nameField.text.isBlank() || baseUrlField.text.isBlank() || modelField.text.isBlank()) {
            return com.intellij.openapi.ui.ValidationInfo("Name, base URL and model are required.", nameField)
        }
        if (temperatureField.text.isNotBlank() && temperatureField.text.toDoubleOrNull() == null) {
            return com.intellij.openapi.ui.ValidationInfo("Temperature must be a number.", temperatureField)
        }
        if (maxTokensField.text.isNotBlank() && maxTokensField.text.toIntOrNull() == null) {
            return com.intellij.openapi.ui.ValidationInfo("Max tokens must be an integer.", maxTokensField)
        }
        return null
    }

    override fun doOKAction() {
        provider.name = nameField.text.trim()
        provider.baseUrl = baseUrlField.text.trim()
        provider.model = modelField.text.trim()
        provider.temperature = temperatureField.text.trim().toDoubleOrNull()
        provider.maxTokens = maxTokensField.text.trim().toIntOrNull()
        provider.headers = headersField.text.trim()
        if (removeKeyCheckBox.isSelected) {
            ApiKeyStore.set(provider.name, null)
        } else {
            val key = String(apiKeyField.password).trim()
            if (key.isNotEmpty()) {
                ApiKeyStore.set(provider.name, key)
            }
        }
        super.doOKAction()
    }
}
