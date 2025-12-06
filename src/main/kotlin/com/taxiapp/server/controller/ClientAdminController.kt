package com.taxiapp.server.controller

import com.taxiapp.server.dto.client.ClientDto
import com.taxiapp.server.service.ClientAdminService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/clients")
// @PreAuthorize ПРИБРАНО
class ClientAdminController(
    private val clientAdminService: ClientAdminService
) {

    // (Read) Получить список всех клиентов
    @GetMapping
    fun getAllClients(): ResponseEntity<List<ClientDto>> {
        val clients = clientAdminService.getAllClients()
        return ResponseEntity.ok(clients)
    }

    // (Update) "Заблокировать"
    @PatchMapping("/{id}/block") // Используем PATCH, т.к. это частичное обновление
    fun blockClient(@PathVariable id: Long): ResponseEntity<ClientDto> {
        val updatedClient = clientAdminService.blockClient(id)
        return ResponseEntity.ok(updatedClient)
    }

    // (Update) "Разблокировать"
    @PatchMapping("/{id}/unblock")
    fun unblockClient(@PathVariable id: Long): ResponseEntity<ClientDto> {
        val updatedClient = clientAdminService.unblockClient(id)
        return ResponseEntity.ok(updatedClient)
    }
}