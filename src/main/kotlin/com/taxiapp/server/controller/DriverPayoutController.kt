package com.taxiapp.server.controller

import com.taxiapp.server.dto.finance.ConfirmPayoutRequest
import com.taxiapp.server.dto.finance.DriverPayoutDto
import com.taxiapp.server.service.DriverPayoutService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.security.Principal

@RestController
@RequestMapping("/api/admin/payouts")
@PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER')")
class DriverPayoutController(
    private val payoutService: DriverPayoutService
) {
    @GetMapping("/pending")
    fun getPendingPayouts(): List<DriverPayoutDto> {
        return payoutService.getPendingPayouts()
    }

    @GetMapping("/archive")
    fun getPaidArchive(): List<DriverPayoutDto> {
        return payoutService.getPaidArchive()
    }

    @PostMapping("/confirm")
    fun confirmPayout(
        @RequestBody request: ConfirmPayoutRequest,
        principal: Principal
    ): DriverPayoutDto {
        return payoutService.confirmPayout(
            payoutId = request.payoutId,
            dispatcherName = principal.name
        )
    }
}