package com.taxiapp.server.controller

import com.taxiapp.server.dto.support.SendSupportReplyRequest
import com.taxiapp.server.dto.support.SupportMessageDto
import com.taxiapp.server.dto.support.SupportTicketDto
import com.taxiapp.server.model.support.TicketStatus
import com.taxiapp.server.service.SupportService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/support/admin")
class SupportAdminController(
    private val supportService: SupportService
) {

    @GetMapping("/tickets")
    fun getTickets(@RequestParam(required = false) status: TicketStatus?): ResponseEntity<List<SupportTicketDto>> {
        return ResponseEntity.ok(supportService.getAllTickets(status))
    }

    @GetMapping("/tickets/{ticketId}/messages")
    fun getTicketMessages(@PathVariable ticketId: UUID): ResponseEntity<List<SupportMessageDto>> {
        return ResponseEntity.ok(supportService.getTicketMessages(ticketId))
    }

    @PostMapping("/tickets/{ticketId}/start")
fun startChat(@PathVariable ticketId: UUID): ResponseEntity<SupportMessageDto> {
    return ResponseEntity.ok(supportService.startChat(ticketId))
}

    @PostMapping("/tickets/{ticketId}/reply")
    fun replyToTicket(
        @PathVariable ticketId: UUID,
        @RequestBody request: SendSupportReplyRequest
    ): ResponseEntity<SupportMessageDto> {
        return ResponseEntity.ok(supportService.replyFromDispatcher(ticketId, request.text))
    }

    @PostMapping("/tickets/{ticketId}/close")
    fun closeTicket(@PathVariable ticketId: UUID): ResponseEntity<Void> {
        supportService.closeTicket(ticketId)
        return ResponseEntity.ok().build()
    }
}