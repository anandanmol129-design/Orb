package de.mm20.launcher2.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update


class NotificationRepository {
    private val _notifications: MutableStateFlow<List<Notification>> = MutableStateFlow(
        emptyList()
    )

    val notifications: Flow<List<Notification>> = _notifications

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    internal fun setConnected(connected: Boolean) {
        _isConnected.value = connected
    }

    internal fun setNotifications(notifications: List<Notification>) {
        _notifications.value = notifications
    }

    internal fun updateNotifications(transform: (List<Notification>) -> List<Notification>) {
        _notifications.update(transform)
    }

    internal fun getNotifications(): List<Notification> = _notifications.value

    internal fun onNotificationPosted(notification: Notification) {
        _notifications.update { current ->
            current.filter { it.key != notification.key } + notification
        }
    }

    internal fun onNotificationRemoved(key: String) {
        _notifications.update { current ->
            current.filter { it.key != key }
        }
    }

    fun cancelNotification(notification: Notification) {
        NotificationService.getInstance()?.cancelNotification(notification.key)
    }

}