package com.taxiapp.server.controller

import com.taxiapp.server.model.enums.TransactionType
import com.taxiapp.server.repository.WalletTransactionRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/stats")
class AdminStatsController(
    private val walletTransactionRepository: WalletTransactionRepository
) {

    @GetMapping("/company-balance")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun getCompanyBalance(): ResponseEntity<Map<String, Any>> {
        // Считаем сумму всех комиссий (ABS нужен, чтобы -15.0 стало 15.0)
        val totalCommission = walletTransactionRepository.sumTotalByOperationType(TransactionType.COMMISSION) ?: 0.0
        
        return ResponseEntity.ok(mapOf(
            "totalBalance" to totalCommission,
            "currency" to "UAH"
        ))
    }

    // --- НОВЫЙ МЕТОД: История транзакций для админки ---
    @GetMapping("/transactions")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun getAllTransactions(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<Map<String, Any>> {
        val pageable = PageRequest.of(page, size, Sort.by("createdAt").descending())
        val pageResult = walletTransactionRepository.findAll(pageable)
        
        // Преобразуем в простой JSON, чтобы не тянуть лишние связи Hibernate
        val dtos = pageResult.content.map { tx ->
            mapOf(
                "id" to tx.id,
                "driverId" to (tx.driver?.id ?: 0),
                "driverName" to (tx.driver?.fullName ?: "Невідомий водій"),
                "driverPhone" to (tx.driver?.userPhone ?: ""),
                "amount" to tx.amount,
                "operationType" to tx.operationType, // DEPOSIT, COMMISSION, etc.
                "description" to (tx.description ?: ""),
                "createdAt" to tx.createdAt
            )
        }

        return ResponseEntity.ok(mapOf(
            "content" to dtos,
            "totalPages" to pageResult.totalPages,
            "totalElements" to pageResult.totalElements,
            "currentPage" to pageResult.number
        ))
    }
}