package com.taxiapp.server.repository

import com.taxiapp.server.model.promo.PromoCode
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface PromoCodeRepository : JpaRepository<PromoCode, Long> {
    fun findByCode(code: String): Optional<PromoCode>
    fun existsByCode(code: String): Boolean
}