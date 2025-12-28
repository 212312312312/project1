package com.taxiapp.server.service

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import org.springframework.stereotype.Service

@Service
class NotificationService {

    // Відправка одному користувачу
    fun sendNotificationToToken(token: String, title: String, body: String) {
        try {
            val message = Message.builder()
                .setToken(token)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .putData("click_action", "FLUTTER_NOTIFICATION_CLICK") 
                .build()

            FirebaseMessaging.getInstance().send(message)
        } catch (e: Exception) {
            println("Помилка відправки FCM: ${e.message}")
        }
    }

    // Масова розсилка
    fun sendMulticast(tokens: List<String>, title: String, body: String) {
        tokens.forEach { token ->
            sendNotificationToToken(token, title, body)
        }
    }
}