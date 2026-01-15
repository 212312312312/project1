package com.taxiapp.server.service

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.Driver
import org.slf4j.LoggerFactory // Використовуємо Logger
import org.springframework.stereotype.Service

@Service
class NotificationService {

    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    fun sendOrderOffer(driver: Driver, order: TaxiOrder) {
        // Беремо токен. Якщо driver.fcmToken null, пробуємо взяти з user (якщо вони розділені)
        // Але оскільки Driver наслідує User (або має спільну таблицю), це поле має бути доступне.
        val token = driver.fcmToken 

        if (token.isNullOrEmpty()) {
            logger.error(">>> ПОМИЛКА: У водія ID=${driver.id} (Phone=${driver.userPhone}) немає FCM токена! Пуш неможливий.")
            return
        }

        try {
            val message = Message.builder()
                .setToken(token)
                .setNotification(
                    Notification.builder()
                        .setTitle("Нове замовлення!")
                        .setBody("Вам запропоновано поїздку: ${order.price.toInt()} грн")
                        .build()
                )
                .putData("type", "ORDER_OFFER")
                .putData("orderId", order.id.toString())
                .putData("price", order.price.toString())
                .putData("address", order.fromAddress)
                .putData("click_action", "ORDER_OFFER_ACTIVITY")
                .setAndroidConfig(
                    com.google.firebase.messaging.AndroidConfig.builder()
                        .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH) // Високий пріоритет
                        .setTtl(20000) // Живе 20 секунд (як таймер оферу)
                        .build()
                )
                .build()

            FirebaseMessaging.getInstance().send(message)
            logger.info(">>> PUSH (OFFER) УСПІШНО відправлено водію ID=${driver.id} на токен: ${token.take(10)}...")
        } catch (e: Exception) {
            logger.error(">>> CRITICAL ERROR відправки FCM: ${e.message}")
            e.printStackTrace()
        }
    }
    
    // Старий метод
    fun sendNotificationToToken(token: String, title: String, body: String) {
         // ... (код без змін)
    }
}