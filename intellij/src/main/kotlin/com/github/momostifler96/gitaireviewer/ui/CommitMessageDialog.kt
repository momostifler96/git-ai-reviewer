package com.github.momostifler96.gitaireviewer.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

/** Editable commit message; OK copies the (possibly edited) message to the clipboard. */
class CommitMessageDialog(project: Project, message: String) : DialogWrapper(project, true) {

    private val textArea = JBTextArea(message).apply {
        rows = 10
        columns = 80
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "Git AI: Commit Message"
        setOKButtonText("Copy to Clipboard")
        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(textArea)

    override fun getPreferredFocusedComponent(): JComponent = textArea

    override fun doOKAction() {
        CopyPasteManager.getInstance().setContents(StringSelection(textArea.text))
        super.doOKAction()
    }
}
