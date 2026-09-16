package com.github.momostifler96.gitaireviewer.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.components.service
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JScrollPane

/** Owns the "Git AI Review" tool window content and updates it with review reports. */
@Service(Level.PROJECT)
class ReviewDisplayService(private val project: Project) {

    val editorPane: JEditorPane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
    }

    val component: JComponent = JScrollPane(editorPane)

    fun show(html: String) {
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = toolWindow()
            editorPane.text = html
            editorPane.caretPosition = 0
            toolWindow.activate(null)
        }
    }

    private fun toolWindow(): ToolWindow {
        val manager = ToolWindowManager.getInstance(project)
        val existing = manager.getToolWindow(TOOL_WINDOW_ID)
        if (existing != null) {
            if (existing.contentManager.contentCount == 0) {
                existing.contentManager.addContent(ContentFactory.getInstance().createContent(component, "", false))
            }
            return existing
        }
        val registered = manager.registerToolWindow(
            RegisterToolWindowTask(
                id = TOOL_WINDOW_ID,
                anchor = ToolWindowAnchor.BOTTOM,
                canCloseContent = false,
            ),
        )
        registered.contentManager.addContent(ContentFactory.getInstance().createContent(component, "", false))
        return registered
    }

    companion object {
        const val TOOL_WINDOW_ID = "Git AI Review"

        fun getInstance(project: Project): ReviewDisplayService = project.service()
    }
}
