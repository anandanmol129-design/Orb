package de.mm20.launcher2.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow


class NotificationRepository {
    private val _notifications: MutableStateFlow<List<Notification>> = MutableStateFlow(
        emptyList()
    )

    val notifications: Flow<List<Notification>> = _notifications

    internal fun setNotifications(notifications: List<Notification>) {
        _notifications.value = notifications
    }

    internal fun getNotifications(): List<Notification> = _notifications.value

    internal fun onNotificationPosted(notification: Notification) {
        _notifications.value = _notifications.value.filter { it.key != notification.key } + notification
    }

    internal fun onNotificationRemoved(key: String) {
        _notifications.value = _notifications.value.filter { it.key != key }
    }

    fun cancelNotification(notification: Notification) {
        NotificationService.getInstance()?.cancelNotification(notification.key)
    }

}