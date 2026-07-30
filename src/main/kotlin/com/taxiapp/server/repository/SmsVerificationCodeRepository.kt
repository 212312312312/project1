package com.taxiapp.server.repository

import com.taxiapp.server.model.auth.SmsVerificationCode
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface SmsVerificationCodeRepository : JpaRepository<SmsVerificationCode, Long> {
    // Найти код по номеру телефона
    fun findByUserPhone(userPhone: String): Optional<SmsVerificationCode>
}