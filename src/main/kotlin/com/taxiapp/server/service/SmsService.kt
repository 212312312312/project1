// ПОЛНОСТЬЮ ЗАМЕНИ СОДЕРЖИМОЕ ВСЕГО ФАЙЛА НА СЛЕДУЮЩИЙ ЧИСТЫЙ КОД:
package com.taxiapp.server.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SmsService {

    private val logger = LoggerFactory.getLogger(SmsService::class.java)

    /**
     * Отправка SMS. В продакшене здесь будет вызов API твоего провайдера (например, TurboSMS, Infobip и т.д.).
     */
    fun sendSms(phoneNumber: String, message: String) {
        logger.info("==================================================")
        logger.info("MOCK SMS SERVICE (ІМІТАЦІЯ)")
        logger.info("НА НОМЕР: $phoneNumber")
        logger.info("ПОВІДОМЛЕННЯ: $message")
        logger.info("==================================================")
    }
}