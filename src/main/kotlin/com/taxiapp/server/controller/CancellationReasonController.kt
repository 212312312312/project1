package com.taxiapp.server.controller

import com.taxiapp.server.model.order.CancellationReason
import com.taxiapp.server.repository.CancellationReasonRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class CancellationReasonController(
    private val repository: CancellationReasonRepository
) {

    // 1. Получить список активных причин с гарантированной фильтрацией (дефолт на DRIVER, если параметр не передан)
    @GetMapping("/cancellation-reasons")
    fun getActiveReasons(@RequestParam(required = false) target: String?): ResponseEntity<List<CancellationReason>> {
        val searchTarget = if (!target.isNullOrBlank()) target.uppercase() else "DRIVER"
        val reasons = repository.findAllByIsActiveTrueAndTarget(searchTarget)
        return ResponseEntity.ok(reasons)
    }

    @PostMapping("/admin/cancellation-reasons")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR', 'DISPATCHER', 'ROLE_DISPATCHER')")
    fun createReason(@RequestBody reason: CancellationReason): ResponseEntity<CancellationReason> {
        val normalizedReason = reason.copy(target = reason.target.uppercase())
        return ResponseEntity.ok(repository.save(normalizedReason))
    }

    @DeleteMapping("/admin/cancellation-reasons/{id}")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR', 'DISPATCHER', 'ROLE_DISPATCHER')")
    fun deleteReason(@PathVariable id: Long): ResponseEntity<Void> {
        repository.deleteById(id)
        return ResponseEntity.ok().build()
    }
}