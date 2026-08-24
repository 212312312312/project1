package com.taxiapp.server.scheduler

import com.taxiapp.server.repository.ClientPromoProgressRepository
import com.taxiapp.server.repository.PromoUsageRepository
import com.taxiapp.server.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

@Component
class PromoNotificationScheduler(
    private val promoUsageRepository: PromoUsageRepository,
    private val clientPromoProgressRepository: ClientPromoProgressRepository,
    private val notificationService: NotificationService,
    private val redisTemplate: StringRedisTemplate
) {
    private val logger = LoggerFactory.getLogger(PromoNotificationScheduler::class.java)

    // Запуск кожні 30 хвилин
    @Scheduled(fixedDelay = 1800000)
    @Transactional(readOnly = true)
    fun notifyExpiringDiscounts() {
        val now = LocalDateTime.now()

        // 1. 🛡️ Тихі години: з 23:00 до 07:00 пуші суворо заборонені
        val currentHour = now.hour
        if (currentHour < 7 || currentHour >= 23) {
            return
        }

        // 2. Вікно згоряння: шукаємо знижки, термін дії яких спливає через 2 - 24 години
        val windowStart = now.plusHours(2)
        val windowEnd = now.plusHours(24)

        // 3. Збір кандидатів: Промокоди
        val expiringPromoCodes = promoUsageRepository.findAllByIsUsedFalseAndExpiresAtBetween(windowStart, windowEnd)
        for (usage in expiringPromoCodes) {
            val client = usage.client
            val token = client.fcmToken
            val percent = usage.promoCode.discountPercent.toInt()
            val expiresAt = usage.expiresAt ?: continue

            trySendReminder(client.id!!, token, percent, expiresAt)
        }

        // 4. Збір кандидатів: Маркетингові нагороди за завдання
        val expiringRewards = clientPromoProgressRepository.findAllByIsRewardAvailableTrueAndRewardExpiresAtBetween(windowStart, windowEnd)
        for (progress in expiringRewards) {
            val client = progress.client
            val token = client.fcmToken
            val percent = progress.promoTask.discountPercent.toInt()
            val expiresAt = progress.rewardExpiresAt ?: continue

            trySendReminder(client.id!!, token, percent, expiresAt)
        }
    }

    private fun trySendReminder(clientId: Long, fcmToken: String?, percent: Int, expiresAt: LocalDateTime) {
        if (fcmToken.isNullOrBlank()) return

        // 5. 🛡️ Антиспам-кулдаун: максимум 1 повідомлення раз на 3.5 дні (84 години)
        val cooldownKey = "push:discount_reminder:$clientId"
        val isFirstInWindow = redisTemplate.opsForValue().setIfAbsent(cooldownKey, "sent", 84, TimeUnit.HOURS)

        if (isFirstInWindow == true) {
            notificationService.sendDiscountReminderNotification(fcmToken, percent, expiresAt)
            logger.info(">>> [Promo Push] Надіслано нагадування про знижку $percent% клієнту #$clientId")
        }
    }
}