package com.taxiapp.server.repository

import com.taxiapp.server.model.news.News
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface NewsRepository : JpaRepository<News, Long> {
    
    // Для адміна: показуємо всі новини
    fun findAllByOrderByCreatedAtDesc(): List<News>

    // --- НОВИЙ МЕТОД ---
    // Знайти новини, де дата створення ПІЗНІШЕ (After) ніж передана дата
    fun findAllByCreatedAtAfterOrderByCreatedAtDesc(date: LocalDateTime): List<News>
}