package com.taxiapp.server.repository

import org.springframework.data.jpa.repository.Query
import com.taxiapp.server.model.user.Client
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface ClientRepository : JpaRepository<Client, Long> {
    
    // ДОБАВЛЕНО: Поиск по точному номеру (нужен для контроллера)
    fun findByUserPhone(userPhone: String): Optional<Client>

    // Поиск по части номера (для админки)
    fun findByUserPhoneContaining(userPhone: String): List<Client>

    @Query("""
        SELECT 
            COALESCE(c.utmSource, c.acquisitionSource), 
            c.utmMedium, 
            c.utmCampaign, 
            COUNT(c) 
        FROM Client c 
        GROUP BY COALESCE(c.utmSource, c.acquisitionSource), c.utmMedium, c.utmCampaign
    """)
    fun getTrafficSourceStats(): List<Array<Any>>
}