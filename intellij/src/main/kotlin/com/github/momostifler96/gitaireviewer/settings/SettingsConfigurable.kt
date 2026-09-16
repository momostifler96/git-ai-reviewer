package com.github.momostifler96.gitaireviewer.settings

import com.github.momostifler96.gitaireviewer.prompts.DEFAULT_COMMIT_PROMPT
import com.github.momostifler96.gitaireviewer.prompts.DEFAULT_REVIEW_PROMPT
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.table.AbstractTableModel

class SettingsConfigurable : Configurable {

    companion object {
        val LANGUAGES = listOf(
            "English", "Français", "Español", "Deutsch", "Italiano", "Português", "Nederlands",
            "Polski", "Türkçe", "Русский", "Українська", "العربية", "हिन्दी", "中文", "日本語",
            "한국어", "Tiếng Việt", "Indonesian",
        )
        private const val USE_DEFAULT = "<Use default provider>"
    }

    private class ProvidersTableModel : AbstractTableModel() {
        val rows: MutableList<AiProvider> = mutableListOf()

        private val columns = listOf("Name", "Base URL", "Model", "Extra headers")

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = with(rows[rowIndex]) {
            when (columnIndex) {
                0 -> name
                1 -> baseUrl
                2 -> model
                else -> headers
            }
        }
    }

    private val settings = AppSettings.getInstance()
    private val providersModel = ProvidersTableModel()
    private val providersTable = JBTable(providersModel).apply {
        getSelectionModel().selectionMode = ListSelectionModel.SINGLE_SELECTION
        autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
    }
    private val defaultProviderCombo = ComboBox<String>()
    private val reviewProviderCombo = ComboBox<String>()
    private val commitProviderCombo = ComboBox<String>()
    private val languageCombo = ComboBox(LANGUAGES.toTypedArray()).apply { isEditable = true }
    private val reviewPromptArea = JBTextArea(10, 70).apply { lineWrap = true; wrapStyleWord = true }
    private val commitPromptArea = JBTextArea(10, 70).apply { lineWrap = true; wrapStyleWord = true }
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(300, 10, 3600, 10))
    private val maxCharsSpinner = JSpinner(SpinnerNumberModel(60000, 1000, 1000000, 5000))
    private val includeUntrackedCheckBox = JBCheckBox("Include untracked (new) files in reviews")

    private var panel: JComponent? = null

    override fun getDisplayName(): String = "Git AI Reviewer"

    override fun createComponent(): JComponent {
        val providersPanel = ToolbarDecorator.createDecorator(providersTable)
            .setAddAction { addProvider() }
            .setEditAction { editSelectedProvider() }
            .setRemoveAction { removeSelectedProvider() }
            .disableUpAction()
            .disableDownAction()
            .createPanel()

        panel = FormBuilder.createFormBuilder()
            .addComponentFillVertically(providersPanel, 6)
            .addLabeledComponent("Default provider (all features)", defaultProviderCombo)
            .addLabeledComponent("Provider for code reviews", reviewProviderCombo)
            .addLabeledComponent("Provider for commit messages", commitProviderCombo)
            .addLabeledComponent("Output language ({language} placeholder)", languageCombo)
            .addSeparator()
            .addLabeledComponent("System prompt — code review", JBScrollPane(reviewPromptArea))
            .addLabeledComponent("System prompt — commit message", JBScrollPane(commitPromptArea))
            .addSeparator()
            .addLabeledComponent("Request timeout (seconds)", timeoutSpinner)
            .addLabeledComponent("Max diff size (characters)", maxCharsSpinner)
            .addComponent(includeUntrackedCheckBox)
            .addComponentFillVertically(JBScrollPane(null), 0)
            .panel
        return panel!!
    }

    private fun addProvider() {
        val provider = AiProvider(name = "new-provider", baseUrl = "https://api.openai.com/v1", model = "gpt-4o-mini")
        if (ProviderDialog(provider).showAndGet()) {
            providersModel.rows += provider
            providersModel.fireTableDataChanged()
            refreshProviderCombos()
        }
    }

    private fun editSelectedProvider() {
        val index = providersTable.selectedRow
        if (index < 0 || index >= providersModel.rows.size) return
        val provider = providersModel.rows[index]
        if (ProviderDialog(provider).showAndGet()) {
            providersModel.fireTableDataChanged()
            refreshProviderCombos()
        }
    }

    private fun removeSelectedProvider() {
        val index = providersTable.selectedRow
        if (index < 0 || index >= providersModel.rows.size) return
        val removed = providersModel.rows.removeAt(index)
        ApiKeyStore.set(removed.name, null)
        providersModel.fireTableDataChanged()
        refreshProviderCombos()
    }

    private fun refreshProviderCombos() {
        val names = providersModel.rows.map { it.name }

        fun fill(combo: ComboBox<String>, selected: String, withDefaultOption: Boolean) {
            combo.removeAllItems()
            if (withDefaultOption) combo.addItem(USE_DEFAULT)
            names.forEach(combo::addItem)
            val item = if (withDefaultOption && selected.isBlank()) USE_DEFAULT else selected
            combo.selectedItem = if (names.contains(item) || item == USE_DEFAULT) item else null
        }

        fill(defaultProviderCombo, settings.state.activeProvider, false)
        fill(reviewProviderCombo, settings.state.reviewProvider, true)
        fill(commitProviderCombo, settings.state.commitProvider, true)
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return providersModel.rows != state.providers ||
            defaultProviderCombo.selectedItem as? String != state.activeProvider ||
            comboValue(reviewProviderCombo) != state.reviewProvider ||
            comboValue(commitProviderCombo) != state.commitProvider ||
            (languageCombo.selectedItem as? String ?: "") != state.outputLanguage ||
            reviewPromptArea.text != state.reviewSystemPrompt ||
            commitPromptArea.text != state.commitSystemPrompt ||
            (timeoutSpinner.value as Number).toInt() * 1000 != state.timeoutMs ||
            (maxCharsSpinner.value as Number).toInt() != state.diffMaxChars ||
            includeUntrackedCheckBox.isSelected != state.includeUntracked
    }

    private fun comboValue(combo: ComboBox<String>): String =
        if (combo.selectedItem == USE_DEFAULT) "" else combo.selectedItem as? String ?: ""

    override fun reset() {
        val state = settings.state
        providersModel.rows.clear()
        providersModel.rows += state.providers.map { it.copy() }
        providersModel.fireTableDataChanged()
        refreshProviderCombos()
        languageCombo.selectedItem = state.outputLanguage
        reviewPromptArea.text = state.reviewSystemPrompt.ifBlank { DEFAULT_REVIEW_PROMPT }
        commitPromptArea.text = state.commitSystemPrompt.ifBlank { DEFAULT_COMMIT_PROMPT }
        timeoutSpinner.value = state.timeoutMs / 1000
        maxCharsSpinner.value = state.diffMaxChars
        includeUntrackedCheckBox.isSelected = state.includeUntracked
    }

    override fun apply() {
        if (providersModel.rows.any { !it.isComplete }) {
            throw ConfigurationException("Every provider needs a name, a base URL and a model.")
        }
        val state = settings.state
        state.providers = providersModel.rows.map { it.copy() }.toMutableList()
        state.activeProvider = defaultProviderCombo.selectedItem as? String ?: ""
        state.reviewProvider = comboValue(reviewProviderCombo)
        state.commitProvider = comboValue(commitProviderCombo)
        state.outputLanguage = (languageCombo.selectedItem as? String ?: "English").trim().ifEmpty { "English" }
        state.reviewSystemPrompt = reviewPromptArea.text.trim().ifBlank { DEFAULT_REVIEW_PROMPT }
        state.commitSystemPrompt = commitPromptArea.text.trim().ifBlank { DEFAULT_COMMIT_PROMPT }
        state.timeoutMs = (timeoutSpinner.value as Number).toInt() * 1000
        state.diffMaxChars = (maxCharsSpinner.value as Number).toInt()
        state.includeUntracked = includeUntrackedCheckBox.isSelected
        refreshProviderCombos()
    }
}
