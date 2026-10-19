package com.taxiapp.server.controller

import com.taxiapp.server.dto.chat.ChatMessageDto
import com.taxiapp.server.dto.chat.SendMessageRequest
import com.taxiapp.server.model.user.User
import com.taxiapp.server.service.ChatService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/chat")
class ChatController(private val chatService: ChatService) {

    // Получить историю сообщений (принимает или Long ID, или UUID String)
    @GetMapping("/{orderIdOrUuid}")
    fun getMessages(
        @PathVariable orderIdOrUuid: String,
        @AuthenticationPrincipal user: User
    ): ResponseEntity<List<ChatMessageDto>> {
        return ResponseEntity.ok(chatService.getOrderMessages(user, orderIdOrUuid))
    }

    // Отправка сообщения от клиента
    @PostMapping("/client/{orderIdOrUuid}")
    fun sendFromClient(
        @PathVariable orderIdOrUuid: String,
        @RequestBody request: SendMessageRequest,
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ChatMessageDto> {
        return ResponseEntity.ok(chatService.sendMessage(user, orderIdOrUuid, "CLIENT", request.content))
    }

    // Отправка сообщения от водителя
    @PostMapping("/driver/{orderIdOrUuid}")
    fun sendFromDriver(
        @PathVariable orderIdOrUuid: String,
        @RequestBody request: SendMessageRequest,
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ChatMessageDto> {
        return ResponseEntity.ok(chatService.sendMessage(user, orderIdOrUuid, "DRIVER", request.content))
    }
}