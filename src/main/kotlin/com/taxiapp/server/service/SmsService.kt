package com.taxiapp.server.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@Service
class SmsService {

    private val logger = LoggerFactory.getLogger(SmsService::class.java)
    
    // Тимчасове сховище кодів: Номер -> Код
    // У продакшені краще використовувати Redis
    private val smsCodes = ConcurrentHashMap<String, String>()

    /**
     * Генерує код, зберігає його та відправляє
     */
    fun sendSmsCode(phone: String) {
        // ВИПРАВЛЕНО: 6 цифр (від 100000 до 999999)
        val code = Random.nextInt(100000, 999999).toString()
        
        // Зберігаємо код у пам'ять
        smsCodes[phone] = code

        // Відправляємо (імітація)
        sendSms(phone, "Ваш код входу: $code")
    }

    /**
     * Перевіряє код. Якщо правильний — видаляє його.
     */
    fun verifySmsCode(phone: String, code: String): Boolean {
        // Універсальний код для тестів (Apple review / Google play review)
        if (code == "000000") return true 

        val savedCode = smsCodes[phone]
        if (savedCode != null && savedCode == code) {
            smsCodes.remove(phone) // Код одноразовий
            return true
        }
        return false
    }

    /**
     * Імітує відправку SMS (логер).
     */
    fun sendSms(phoneNumber: String, message: String) {
        logger.info("==================================================")
        logger.info("MOCK SMS SERVICE (ІМІТАЦІЯ)")
        logger.info("НА НОМЕР: $phoneNumber")
        logger.info("ПОВІДОМЛЕННЯ: $message")
        logger.info("==================================================")
    }
}