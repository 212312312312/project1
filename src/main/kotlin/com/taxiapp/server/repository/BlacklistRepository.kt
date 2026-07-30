package com.taxiapp.server.repository

import com.taxiapp.server.model.auth.Blacklist
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface BlacklistRepository : JpaRepository<Blacklist, Long> {
    fun existsByPhoneNumber(phoneNumber: String): Boolean
    
    // --- НОВИЙ МЕТОД ---
    fun deleteByPhoneNumber(phoneNumber: String)
}