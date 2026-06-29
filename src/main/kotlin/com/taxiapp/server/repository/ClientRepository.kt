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

    @Query("SELECT c.utmSource, COUNT(c) FROM Client c GROUP BY c.utmSource")
fun getTrafficSourceStats(): List<Array<Any>>
}