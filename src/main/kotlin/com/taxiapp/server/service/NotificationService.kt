package com.taxiapp.server.service

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.Driver
import org.springframework.stereotype.Service

@Service
class NotificationService {

    // Спеціальний метод для відправки пропозиції замовлення (Smart Dispatch)
    fun sendOrderOffer(driver: Driver, order: TaxiOrder) {
        // Перевіряємо, чи є у водія токен (припускаємо, що він збережений в полі fcmToken або окремій таблиці)
        val token = driver.fcmToken // <-- ВАЖЛИВО: Переконайся, що в Driver.kt є це поле, або дістань його з User
        
        if (token.isNullOrEmpty()) {
            println(">>> У водія ${driver.id} немає FCM токена. Пуш не відправлено.")
            return
        }

        try {
            // Формуємо DATA payload (дані для обробки програмою)
            val message = Message.builder()
                .setToken(token)
                // Можна додати візуальне сповіщення (опціонально)
                .setNotification(
                    Notification.builder()
                        .setTitle("Нове замовлення!")
                        .setBody("Вам запропоновано поїздку: ${order.price} грн")
                        .build()
                )
                // Головне: дані для MyFirebaseMessagingService
                .putData("type", "ORDER_OFFER")
                .putData("orderId", order.id.toString())
                .putData("price", order.price.toString())
                .putData("address", order.fromAddress)
                .putData("click_action", "ORDER_OFFER_ACTIVITY") // Для Android
                .build()

            FirebaseMessaging.getInstance().send(message)
            println(">>> PUSH (OFFER) відправлено водію ${driver.id} для замовлення ${order.id}")
        } catch (e: Exception) {
            println("Помилка відправки OFFER: ${e.message}")
        }
    }

    // Твої старі методи (залиш їх)
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
                .build()
            FirebaseMessaging.getInstance().send(message)
        } catch (e: Exception) {
            println("Помилка FCM: ${e.message}")
        }
    }
}