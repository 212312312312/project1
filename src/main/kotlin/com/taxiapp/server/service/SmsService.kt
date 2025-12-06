package com.taxiapp.server.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SmsService {

    private val logger = LoggerFactory.getLogger(SmsService::class.java)

    /**
     * Имитирует отправку SMS.
     * В реальном приложении здесь был бы вызов Twilio / Vonage API.
     */
    fun sendSms(phoneNumber: String, message: String) {
        logger.info("==================================================")
        logger.info("MOCK SMS SERVICE (ИМИТАЦИЯ ОТПРАВКИ SMS)")
        logger.info("НА НОМЕР: $phoneNumber")
        logger.info("СООБЩЕНИЕ: $message")
        logger.info("==================================================")
        
        // (Здесь мог бы быть 'throw Exception("Ошибка API")' для симуляции ошибки)
    }
}