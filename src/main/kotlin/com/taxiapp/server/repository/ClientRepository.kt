package com.taxiapp.server.repository

import com.taxiapp.server.model.user.Client
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ClientRepository : JpaRepository<Client, Long> {
    // ИЗМЕНЕНО: Имя метода теперь 'findByUserPhoneContaining'
    fun findByUserPhoneContaining(userPhone: String): List<Client>
}