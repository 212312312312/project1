package com.taxiapp.server.service

import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.common.util.concurrent.MoreExecutors
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.taxiapp.server.model.notification.DriverNotification
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.DriverNotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class NotificationService(
    private val notificationRepository: DriverNotificationRepository
) {

    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    private fun sendFirebaseAsync(message: Message, contextInfo: String) {
        try {
            val future = FirebaseMessaging.getInstance().sendAsync(message)
            ApiFutures.addCallback(
                future,
                object : ApiFutureCallback<String> {
                    override fun onSuccess(messageId: String?) {
                        logger.info(">>> PUSH OK [{}]: {}", contextInfo, messageId)
                    }

                    override fun onFailure(t: Throwable) {
                        logger.warn(">>> PUSH ERROR [{}]: {}", contextInfo, t.message)
                    }
                },
                MoreExecutors.directExecutor()
            )
        } catch (e: Exception) {
            logger.error(">>> Помилка ініціалізації FCM sendAsync [{}]: {}", contextInfo, e.message)
        }
    }

    /**
     * МЕТОД 1: Для ВОДИТЕЛЕЙ (Data-only) — передаем полный набор полей заказа,
     * чтобы OrderOfferActivity при старте из FCM сразу отображала маршрут, сектора, тариф и способ оплаты!
     */
    fun sendOrderOffer(driver: Driver, order: TaxiOrder) {
        val token = driver.fcmToken
        if (token.isNullOrEmpty()) {
            logger.warn(">>> Немає FCM токена для водія {}", driver.id)
            return
        }

        val message = try {
            Message.builder()
                .setToken(token)
                .putData("type", "ORDER_OFFER")
                .putData("orderId", order.uuid.toString())
                .putData("idLong", (order.id ?: 0L).toString())
                .putData("price", order.price.toString())
                .putData("distance", ((order.distanceMeters ?: 0) / 1000.0).toString())
                .putData("distanceMeters", (order.distanceMeters ?: 0).toString())
                .putData("address", order.fromAddress)
                .putData("toAddress", order.toAddress)
                .putData("originLat", (order.originLat ?: 0.0).toString())
                .putData("originLng", (order.originLng ?: 0.0).toString())
                .putData("destLat", (order.destLat ?: 0.0).toString())
                .putData("destLng", (order.destLng ?: 0.0).toString())
                .putData("tariffName", order.tariffName ?: order.tariff.name)
                .putData("paymentMethod", order.paymentMethod)
                .putData("fromSector", order.originSector?.name ?: "")
                .putData("toSector", order.destinationSector?.name ?: "")
                .putData("comment", order.comment ?: "")
                .putData("polyline", order.googleRoutePolyline ?: "")
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setTtl(20000)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            logger.error(">>> Помилка формування FCM повідомлення (Driver): ${e.message}")
            return
        }

        sendAfterCommit {
            sendFirebaseAsync(message, "OrderOffer driver=${driver.id} order=${order.uuid}")
        }
    }

    /**
     * МЕТОД 2: Запрос подтверждения предварительного заказа (ORDER_CONFIRMATION).
     */
    fun sendConfirmationRequest(driver: Driver, order: TaxiOrder) {
        val token = driver.fcmToken
        if (token.isNullOrEmpty()) {
            logger.warn(">>> Немає FCM токена для попереднього замовлення водія {}", driver.id)
            return
        }

        val message = try {
            Message.builder()
                .setToken(token)
                .putData("type", "ORDER_CONFIRMATION")
                .putData("orderId", order.uuid.toString())
                .putData("idLong", (order.id ?: 0L).toString())
                .putData("price", order.price.toString())
                .putData("address", order.fromAddress)
                .putData("toAddress", order.toAddress)
                .putData("time", order.scheduledAt.toString())
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setTtl(60000)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            logger.error(">>> Помилка формування FCM повідомлення підтвердження: ${e.message}")
            return
        }

        sendAfterCommit {
            sendFirebaseAsync(message, "OrderConfirmation driver=${driver.id} order=${order.uuid}")
        }
    }

    fun saveAndSend(driver: Driver, title: String, body: String, type: String) {
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

        if (!driver.fcmToken.isNullOrEmpty()) {
            sendNotificationToToken(driver.fcmToken!!, title, body)
        }
    }

    fun sendNotificationToToken(token: String, title: String, body: String) {
        if (token.isEmpty()) return

        try {
            val notification = Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build()

            val message = Message.builder()
                .setToken(token)
                .setNotification(notification)
                .putData("type", "NEWS")
                .build()

            sendFirebaseAsync(message, "News token=${token.take(10)}...")
        } catch (e: Exception) {
            logger.error(">>> Ошибка FCM (News): ${e.message}")
        }
    }

    fun sendOrderStatusToClient(token: String?, orderId: Long, status: String, title: String, body: String) {
        if (token.isNullOrEmpty()) return

        val message = try {
            Message.builder()
                .setToken(token)
                .putData("type", "ORDER_STATUS_UPDATE")
                .putData("orderId", orderId.toString())
                .putData("status", status)
                .putData("title", title)
                .putData("body", body)
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            logger.error(">>> Помилка формування FCM повідомлення (Status Update): ${e.message}")
            return
        }

        sendAfterCommit {
            sendFirebaseAsync(message, "StatusUpdate order=$orderId status=$status")
        }
    }

    fun sendPhotoControlRequestNotification(driver: Driver, deadlineAt: LocalDateTime?) {
        val token = driver.fcmToken ?: run {
            logger.warn("У водителя ID ${driver.id} отсутствует fcmToken!")
            return
        }

        val timeStr = deadlineAt?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: ""

        val message = Message.builder()
            .setToken(token)
            .putAllData(
                mapOf(
                    "type" to "PHOTO_CONTROL_REQUEST",
                    "title" to "Потрібно пройти фотоконтроль",
                    "body" to "Пройдіть фотоконтроль до $timeStr"
                )
            )
            .build()

        sendAfterCommit {
            sendFirebaseAsync(message, "PhotoControlRequest driver=${driver.id}")
        }
    }

    fun sendPhotoControlApprovedNotification(driver: Driver) {
        saveAndSend(
            driver,
            "Фотоконтроль пройдено",
            "Все чудово! Обмеження знято, ви можете приймати замовлення.",
            "PHOTO_CONTROL_APPROVED"
        )
    }

    fun sendPhotoControlRejectedNotification(driver: Driver, reason: String?) {
        val msg = if (!reason.isNullOrBlank()) "Причина: $reason. Пройдіть повторний фотоконтроль."
        else "Вашу роботу обмежено до успішного проходження фотоконтролю."
        saveAndSend(
            driver,
            "Фотоконтроль відхилено",
            msg,
            "PHOTO_CONTROL_REJECTED"
        )
    }

    fun sendPhotoControlExpiredNotification(driver: Driver) {
        saveAndSend(
            driver,
            "Час фотоконтролю вичерпано",
            "Вашу роботу обмежено до успішного проходження фотоконтролю.",
            "PHOTO_CONTROL_EXPIRED"
        )
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

            sendAfterCommit {
                sendFirebaseAsync(message, "Chat order=$orderId")
            }
        } catch (e: Exception) {
            logger.error(">>> Помилка FCM (Chat): ${e.message}")
        }
    }

    private fun sendAfterCommit(task: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    try {
                        task()
                    } catch (e: Exception) {
                        logger.error(">>> Критична помилка відправки FCM пуша після комміту: ${e.message}")
                    }
                }
            })
        } else {
            try {
                task()
            } catch (e: Exception) {
                logger.error(">>> Критична помилка відправки FCM пуша (без транзакції): ${e.message}")
            }
        }
    }

    fun sendOrderCancelToDriver(driver: Driver, order: TaxiOrder) {
        val token = driver.fcmToken
        if (token.isNullOrBlank()) return

        try {
            val message = Message.builder()
                .setToken(token)
                .putData("type", "ORDER_CANCEL")
                .putData("orderId", order.uuid.toString())
                .putData("title", "Замовлення скасовано")
                .putData("body", "Клієнт скасував замовлення")
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build()
                )
                .build()

            sendAfterCommit {
                sendFirebaseAsync(message, "OrderCancel driver=${driver.id} order=${order.uuid}")
            }
        } catch (e: Exception) {
            logger.error("Не вдалося відправити пуш про скасування водію: ${e.message}")
        }
    }

    fun sendDiscountReminderNotification(token: String?, percent: Int, expiresAt: LocalDateTime) {
        if (token.isNullOrBlank()) return

        val timeStr = expiresAt.format(DateTimeFormatter.ofPattern("HH:mm, dd.MM"))
        val title = "Встигніть скористатися знижкою $percent%!"
        val body = "Ваша персональна знижка $percent% діє до $timeStr. Замовте поїздку вигідно!"

        try {
            val notification = Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build()

            val message = Message.builder()
                .setToken(token)
                .setNotification(notification)
                .putData("type", "DISCOUNT_EXPIRING")
                .putData("percent", percent.toString())
                .putData("expiresAt", timeStr)
                .build()

            sendFirebaseAsync(message, "DiscountReminder $percent%")
        } catch (e: Exception) {
            logger.error(">>> Помилка FCM (Discount Reminder): ${e.message}")
        }
    }
}