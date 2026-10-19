package com.taxiapp.server.controller

import com.taxiapp.server.dto.support.TelegramUpdate
import com.taxiapp.server.service.SupportService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/support/webhook")
class TelegramWebhookController(
    private val supportService: SupportService,
    @Value("\${telegram.bot.webhook-secret:}") private val webhookSecret: String
) {
    @PostMapping
    fun receiveTelegramUpdate(
        @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) secretHeader: String?,
        @RequestBody update: TelegramUpdate
    ): ResponseEntity<Void> {
        // Если в конфиге задан telegram.bot.webhook-secret — строго проверяем заголовок от серверов Telegram
        if (webhookSecret.isNotBlank() && secretHeader != webhookSecret) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        supportService.handleTelegramUpdate(update)
        return ResponseEntity.ok().build()
    }
}