package com.github.momostifler96.gitaireviewer.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object Notifications {

    fun info(project: Project?, message: String) = notify(project, message, NotificationType.INFORMATION)

    fun warning(project: Project?, message: String) = notify(project, message, NotificationType.WARNING)

    fun error(project: Project?, message: String) = notify(project, message, NotificationType.ERROR)

    private fun notify(project: Project?, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Git AI Review")
            .createNotification(message, type)
            .notify(project)
    }
}
