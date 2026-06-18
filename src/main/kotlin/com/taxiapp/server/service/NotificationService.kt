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
                .putData("orderId", order.uuid.toString()) // 🔥 ИСПРАВЛЕНО: Теперь передаем строковый UUID для контракта accept
                .putData("idLong", order.id.toString())    // 🔥 ДОБАВЛЕНО: Числовой ID шлем отдельно для системных нотификаций
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
            logger.info(">>> PUSH (Data Only) отправлен водителю ${driver.id} для заказа UUID: ${order.uuid}")
        }
    }

    /**
     * МЕТОД 2: Запрос подтверждения предварительного заказа (ORDER_CONFIRMATION).
     */
    fun sendConfirmationRequest(driver: Driver, order: TaxiOrder) {
        val token = driver.fcmToken
        if (token.isNullOrEmpty()) {
            logger.error(">>> ПОМИЛКА: Немає FCM токена для предварительного заказа водія ${driver.id}")
            return
        }

        val message = try {
            Message.builder()
                .setToken(token)
                .putData("type", "ORDER_CONFIRMATION")
                .putData("orderId", order.uuid.toString()) // 🔥 ИСПРАВЛЕНО: Передаем строковый UUID
                .putData("idLong", order.id.toString())    // 🔥 ДОБАВЛЕНО: Числовой ID отдельно
                .putData("price", order.price.toString())
                .putData("address", order.fromAddress ?: "")
                .putData("time", order.scheduledAt.toString())
                .setAndroidConfig(
                    com.google.firebase.messaging.AndroidConfig.builder()
                        .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                        .setTtl(60000) // Живет 60 секунд (время на подтверждение)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            logger.error(">>> Помилка формування FCM повідомлення підтвердження: ${e.message}")
            return
        }

        // 🔥 ИСПРАВЛЕНО: Завернуто в sendAfterCommit для транзакционной безопасности
        sendAfterCommit {
            FirebaseMessaging.getInstance().send(message)
            logger.info(">>> CONFIRMATION REQUEST sent to driver ${driver.id} для заказа UUID: ${order.uuid}")
        }
    }

    /**
     * МЕТОД 3: Сохранение системных уведомлений в историю БД и отправка в шторку.
     */
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
     * МЕТОД 4: Для НОВОСТЕЙ и КЛИЕНТОВ (Standard Notification).
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
                .setNotification(notification) // Это заставляет Android показать уведомление автоматически
                .putData("type", "NEWS") // Можно добавить тип, чтобы открывать NewsActivity по клику
                .build()

            FirebaseMessaging.getInstance().send(message)
            logger.info(">>> PUSH (News) отправлен на токен: ${token.take(10)}...")
        } catch (e: Exception) {
            logger.error(">>> Ошибка FCM (News): ${e.message}")
        }
    }

    /**
     * МЕТОД 5: Отправка Data Push клиенту об изменении статуса заказа.
     */
    fun sendOrderStatusToClient(token: String?, orderId: Long, status: String, title: String, body: String) {
        if (token.isNullOrEmpty()) return

        try {
            val message = Message.builder()
                .setToken(token)
                // ВАЖЛИВО: Використовуємо ТІЛЬКИ putData (Data Push), без setNotification!
                // Це не створить нове сповіщення автоматично, а розбудит MyFirebaseMessagingService клиента
                .putData("type", "ORDER_STATUS_UPDATE")
                .putData("orderId", orderId.toString())
                .putData("status", status)
                .putData("title", title)
                .putData("body", body)
                .setAndroidConfig(
                    com.google.firebase.messaging.AndroidConfig.builder()
                        .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                        .build()
                )
                .build()

            FirebaseMessaging.getInstance().send(message)
            // 🔥 ИСПРАВЛЕНО: Убран экранирующий бэкслеш \, чтобы логи выводились корректно
            logger.info(">>> PUSH (Data Status Update) відправлено клієнту на токен: ${token.take(10)}...")
        } catch (e: Exception) {
            // 🔥 ИСПРАВЛЕНО: Убран экранирующий бэкслеш \, добавлен вывод стек-трейса
            logger.error(">>> Помилка FCM (Status Update): ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * МЕТОД 6: Уведомления о новых сообщениях чата.
     */
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
            e.printStackTrace()
        }
    }

    /**
     * Вспомогательный метод для безопасной отправки сетевых запросов строго ПОСЛЕ коммита транзакции БД.
     */
    private fun sendAfterCommit(task: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    try {
                        task()
                    } catch (e: Exception) {
                        // --- ИСПРАВЛЕНО: Логи исправлены на чистый вывод параметров ---
                        logger.error(">>> Критична помилка відправки FCM пуша після комміту: ${e.message}")
                        e.printStackTrace() // Выведет точную системную причину в лог сервера
                    }
                }
            })
        } else {
            // Если активной транзакции в текущем потоке нет, выполняем отправку мгновенно
            try {
                task()
            } catch (e: Exception) {
                logger.error(">>> Критична помилка відправки FCM пуша (без транзакції): ${e.message}")
                e.printStackTrace()
            }
        }
    }
}