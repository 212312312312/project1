package com.taxiapp.server.scheduler

import com.taxiapp.server.service.SupportService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SupportTicketScheduler(
    private val supportService: SupportService
) {
    private val log = LoggerFactory.getLogger(SupportTicketScheduler::class.java)

    @Scheduled(cron = "0 0 3 * * *") // Щодня о 03:00
    fun cleanupOldTickets() {
        try {
            supportService.deleteOldClosedTickets()
            log.info("Очищення старих закритих тікетів успішно виконано")
        } catch (e: Exception) {
            log.error("Помилка під час очищення тікетів: ${e.message}", e)
        }
    }
}