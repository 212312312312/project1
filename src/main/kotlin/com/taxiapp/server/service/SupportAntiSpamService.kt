package com.taxiapp.server.service

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class SupportAntiSpamService(
    private val telegramBotService: TelegramBotService
) {
    private val messageTimestamps = ConcurrentHashMap<Long, MutableList<Long>>()
    private val mutedUntilMap = ConcurrentHashMap<Long, Long>()
    private val lastMessageMap = ConcurrentHashMap<Long, Pair<String, Long>>()

    companion object {
        private const val MAX_MESSAGES = 10
        private const val WINDOW_MILLIS = 10_000L           // 10 секунд
        private const val MUTE_DURATION_MILLIS = 60_000L    // 1 минута мута
        private const val DUPLICATE_INTERVAL_MILLIS = 2_000L // 2 секунды на дубликат
    }

    fun isSpam(chatId: Long, text: String?): Boolean {
        val now = System.currentTimeMillis()

        // 1. Проверка активного мута
        val mutedUntil = mutedUntilMap[chatId] ?: 0L
        if (now < mutedUntil) {
            return true
        }

        val rawText = text?.trim() ?: ""

        // 2. Игнорирование одинаковых сообщений подряд (< 2 сек)
        val lastMsgInfo = lastMessageMap[chatId]
        if (lastMsgInfo != null && lastMsgInfo.first == rawText && (now - lastMsgInfo.second) < DUPLICATE_INTERVAL_MILLIS) {
            return true
        }
        if (rawText.isNotEmpty()) {
            lastMessageMap[chatId] = Pair(rawText, now)
        }

        // 3. Проверка частоты (Rate Limiting)
        val timestamps = messageTimestamps.computeIfAbsent(chatId) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeIf { now - it > WINDOW_MILLIS }
            timestamps.add(now)

            if (timestamps.size > MAX_MESSAGES) {
                mutedUntilMap[chatId] = now + MUTE_DURATION_MILLIS
                timestamps.clear()
                telegramBotService.sendMessage(
                    chatId,
                    "⚠️ Ви надсилаєте повідомлення занадто часто. Будь ласка, зачекайте 1 хвилину."
                )
                return true
            }
        }

        return false
    }
}