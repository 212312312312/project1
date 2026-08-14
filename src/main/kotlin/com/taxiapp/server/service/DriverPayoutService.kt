package com.taxiapp.server.service

import com.taxiapp.server.dto.finance.DriverPayoutDto
import com.taxiapp.server.model.enums.PayoutStatus
import com.taxiapp.server.model.finance.DriverPayout
import com.taxiapp.server.model.order.TaxiOrder
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.DriverPayoutRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class DriverPayoutService(
    private val payoutRepository: DriverPayoutRepository
) {
    @Transactional
    fun createPayout(driver: Driver, order: TaxiOrder?, amount: Double, comment: String?): DriverPayout {
        val payout = DriverPayout(
            driver = driver,
            order = order,
            amount = amount,
            status = PayoutStatus.PENDING,
            comment = comment,
            createdAt = LocalDateTime.now()
        )
        return payoutRepository.save(payout)
    }

    fun getPendingPayouts(): List<DriverPayoutDto> {
        return payoutRepository.findAllByStatusOrderByCreatedAtDesc(PayoutStatus.PENDING)
            .map { DriverPayoutDto(it) }
    }

    fun getPaidArchive(): List<DriverPayoutDto> {
        return payoutRepository.findAllByStatusOrderByCreatedAtDesc(PayoutStatus.PAID)
            .map { DriverPayoutDto(it) }
    }

    @Transactional
    fun confirmPayout(payoutId: Long, dispatcherName: String): DriverPayoutDto {
        val payout = payoutRepository.findById(payoutId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Запис виплати не знайдено") }

        if (payout.status == PayoutStatus.PAID) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Цю виплату вже закрито")
        }

        payout.status = PayoutStatus.PAID
        payout.paidAt = LocalDateTime.now()
        payout.paidByDispatcher = dispatcherName

        val saved = payoutRepository.save(payout)
        return DriverPayoutDto(saved)
    }
}