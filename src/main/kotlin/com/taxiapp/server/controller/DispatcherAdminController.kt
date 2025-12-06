package com.taxiapp.server.controller

import com.taxiapp.server.dto.auth.MessageResponse
import com.taxiapp.server.dto.dispatcher.CreateDispatcherRequest
import com.taxiapp.server.dto.dispatcher.DispatcherDto
import com.taxiapp.server.service.DispatcherAdminService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/dispatchers")
// @PreAuthorize ПРИБРАНО
class DispatcherAdminController(
    private val dispatcherAdminService: DispatcherAdminService
) {

    @GetMapping
    fun getAllDispatchers(): ResponseEntity<List<DispatcherDto>> {
        return ResponseEntity.ok(dispatcherAdminService.getAllDispatchers())
    }

    @PostMapping
    fun createDispatcher(@Valid @RequestBody request: CreateDispatcherRequest): ResponseEntity<DispatcherDto> {
        val dto = dispatcherAdminService.createDispatcher(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(dto)
    }

    @PutMapping("/{id}")
    fun updateDispatcher(
        @PathVariable id: Long,
        @Valid @RequestBody request: CreateDispatcherRequest
    ): ResponseEntity<DispatcherDto> {
        val dto = dispatcherAdminService.updateDispatcher(id, request)
        return ResponseEntity.ok(dto)
    }

    @DeleteMapping("/{id}")
    fun deleteDispatcher(@PathVariable id: Long): ResponseEntity<MessageResponse> {
        dispatcherAdminService.deleteDispatcher(id)
        return ResponseEntity.ok(MessageResponse("Диспетчер $id видалений"))
    }
    
    // Ми можемо використовувати існуючі е-поінти /admin/clients для блокування/розблокування,
    // оскільки Диспетчер - це такий самий 'User'.
    // Або ми можемо додати їх сюди для ясності.
}