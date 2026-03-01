package com.taxiapp.server.controller

import com.taxiapp.server.dto.chat.ChatMessageDto
import com.taxiapp.server.dto.chat.SendMessageRequest
import com.taxiapp.server.service.ChatService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/chat") // <--- ИСПРАВЛЕНО: добавили /v1
class ChatController(private val chatService: ChatService) {

    // Получить историю сообщений при открытии экрана
    @GetMapping("/{orderId}")
    fun getMessages(@PathVariable orderId: Long): ResponseEntity<List<ChatMessageDto>> {
        return ResponseEntity.ok(chatService.getOrderMessages(orderId))
    }

    // Отправка сообщения от клиента
    @PostMapping("/client/{orderId}")
    fun sendFromClient(
        @PathVariable orderId: Long,
        @RequestBody request: SendMessageRequest
    ): ResponseEntity<ChatMessageDto> {
        return ResponseEntity.ok(chatService.sendMessage(orderId, "CLIENT", request.content))
    }

    // Отправка сообщения от водителя
    @PostMapping("/driver/{orderId}")
    fun sendFromDriver(
        @PathVariable orderId: Long,
        @RequestBody request: SendMessageRequest
    ): ResponseEntity<ChatMessageDto> {
        return ResponseEntity.ok(chatService.sendMessage(orderId, "DRIVER", request.content))
    }
}