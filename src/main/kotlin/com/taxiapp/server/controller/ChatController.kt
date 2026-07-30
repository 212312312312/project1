package com.taxiapp.server.controller

import com.taxiapp.server.dto.chat.ChatMessageDto
import com.taxiapp.server.dto.chat.SendMessageRequest
import com.taxiapp.server.service.ChatService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/chat")
class ChatController(private val chatService: ChatService) {

    // Получить историю сообщений (принимает или Long ID, или UUID String)
    @GetMapping("/{orderIdOrUuid}")
    fun getMessages(@PathVariable orderIdOrUuid: String): ResponseEntity<List<ChatMessageDto>> {
        return ResponseEntity.ok(chatService.getOrderMessages(orderIdOrUuid))
    }

    // Отправка сообщения от клиента
    @PostMapping("/client/{orderIdOrUuid}")
    fun sendFromClient(
        @PathVariable orderIdOrUuid: String,
        @RequestBody request: SendMessageRequest
    ): ResponseEntity<ChatMessageDto> {
        return ResponseEntity.ok(chatService.sendMessage(orderIdOrUuid, "CLIENT", request.content))
    }

    // Отправка сообщения от водителя
    @PostMapping("/driver/{orderIdOrUuid}")
    fun sendFromDriver(
        @PathVariable orderIdOrUuid: String,
        @RequestBody request: SendMessageRequest
    ): ResponseEntity<ChatMessageDto> {
        return ResponseEntity.ok(chatService.sendMessage(orderIdOrUuid, "DRIVER", request.content))
    }
}