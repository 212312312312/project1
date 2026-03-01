package com.taxiapp.server.service

import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.Driver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import com.taxiapp.server.repository.DriverNotificationRepository
import com.taxiapp.server.model.notification.DriverNotification

@Service
class NotificationService(
    // ✅ ИСПРАВЛЕНИЕ: Репозиторий должен быть здесь, в конструкторе!
    private val notificationRepository: DriverNotificationRepository
) {

    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    /**
     * МЕТОД 1: Для ВОДИТЕЛЕЙ (Data-only).
     * Не создает уведомление в шторке сам. Будит приложение, чтобы открыть экран предложения.
     */
    fun sendOrderOffer(driver: Driver, order: TaxiOrder) {
        val token = driver.fcmToken

        if (token.isNullOrEmpty()) {
            logger.error(">>> ПОМИЛКА: Немає FCM токена для водія ${driver.id}")
            return
        }

        // 1. Собираем сообщение ДО коммита (пока доступны все данные из базы)
        val message = try {
            Message.builder()
                .setToken(token)
                // ВАЖНО: Тут НЕТ .setNotification(), только data
                .putData("type", "ORDER_OFFER")
                .putData("orderId", order.id.toString())
                .putData("price", order.price.toString())
                .putData("distance", ((order.distanceMeters ?: 0) / 1000.0).toString())
                .putData("address", order.fromAddress ?: "Адреса не вказана")
                .setAndroidConfig(
                    com.google.firebase.messaging.AndroidConfig.builder()
                        .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                        .setTtl(20000) // 20 секунд
                        .build()
                )
                .build()
        } catch (e: Exception) {
            logger.error(">>> Помилка формування FCM повідомлення (Driver): ${e.message}")
            return
        }

        // 2. Откладываем саму сетевую отправку на момент ПОСЛЕ успешного коммита транзакции
        sendAfterCommit {
            FirebaseMessaging.getInstance().send(message)
            logger.info(">>> PUSH (Data Only) отправлен водителю ${driver.id}")
        }
    }

    fun sendConfirmationRequest(driver: Driver, order: TaxiOrder) {
        val token = driver.fcmToken
        if (token.isNullOrEmpty()) return

        try {
            val message = Message.builder()
                .setToken(token)
                .putData("type", "ORDER_CONFIRMATION") // <--- НОВИЙ ТИП
                .putData("orderId", order.id.toString())
                .putData("price", order.price.toString())
                .putData("address", order.fromAddress ?: "")
                .putData("time", order.scheduledAt.toString())
                .setAndroidConfig(
                    com.google.firebase.messaging.AndroidConfig.builder()
                        .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                        .setTtl(60000) // Живе 60 секунд (час на підтвердження)
                        .build()
                )
                .build()

            FirebaseMessaging.getInstance().send(message)
            logger.info(">>> CONFIRMATION REQUEST sent to driver ${driver.id}")
        } catch (e: Exception) {
            logger.error("FCM Error: ${e.message}")
        }
    }

    fun saveAndSend(driver: Driver, title: String, body: String, type: String) {
        // 1. Сохраняем в БД
        try {
            val entity = DriverNotification(
                driver = driver,
                title = title,
                body = body,
                type = type
            )
            notificationRepository.save(entity)
        } catch (e: Exception) {
            logger.error("Ошибка сохранения уведомления в БД: ${e.message}")
        }

        // 2. Отправляем Push (если есть токен)
        if (!driver.fcmToken.isNullOrEmpty()) {
            sendNotificationToToken(driver.fcmToken!!, title, body)
        }
    }

    /**
     * МЕТОД 2: Для НОВОСТЕЙ и КЛИЕНТОВ (Standard Notification).
     * Создает уведомление в шторке (Tray). Используется в NewsService.
     */
    fun sendNotificationToToken(token: String, title: String, body: String) {
        if (token.isEmpty()) return

        try {
            val notification = Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build()

            val message = Message.builder()
                .setToken(token)
                .setNotification(notification) // Это заставляет Android показать уведомление
                .putData("type", "NEWS") // Можно добавить тип, чтобы открывать NewsActivity по клику
                .build()

            FirebaseMessaging.getInstance().send(message)
            logger.info(">>> PUSH (News) отправлен на токен: ${token.take(10)}...")
        } catch (e: Exception) {
            logger.error(">>> Ошибка FCM (News): ${e.message}")
        }
    }

    private fun sendAfterCommit(task: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    try {
                        task()
                    } catch (e: Exception) {
                        logger.error(">>> FCM Error in afterCommit: \${e.message}")
                    }
                }
            })
        } else {
            // Если транзакции нет, отправляем сразу
            try {
                task()
            } catch (e: Exception) {
                logger.error(">>> FCM Error: \${e.message}")
            }
        }
    }

    fun sendChatNotification(token: String?, title: String, body: String, orderId: Long) {
        if (token.isNullOrEmpty()) return
        try {
            val message = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .putData("type", "CHAT_MESSAGE")
                .putData("orderId", orderId.toString())
                .build()

            FirebaseMessaging.getInstance().send(message)
            logger.info(">>> PUSH (Chat) відправлено на токен: ${token.take(10)}...")
        } catch (e: Exception) {
            logger.error(">>> Помилка FCM (Chat): ${e.message}")
        }
    }
}
