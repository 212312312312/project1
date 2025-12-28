package com.taxiapp.server.controller

import com.taxiapp.server.dto.news.CreateNewsRequest
import com.taxiapp.server.dto.news.NewsDto
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.service.NewsService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.Principal

@RestController
@RequestMapping("/api/v1")
class NewsController(
    private val newsService: NewsService,
    private val userRepository: UserRepository // <-- Додаємо репозиторій користувачів
) {

    // --- ДЛЯ КЛІЄНТА (ОНОВЛЕНО) ---
    @GetMapping("/client/news")
    fun getNewsForClient(principal: Principal): ResponseEntity<List<NewsDto>> {
        val userLogin = principal.name
        
        // Шукаємо клієнта
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
             user = userRepository.findByUserPhone(userLogin)
                 .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }

        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Тільки для клієнтів")
        }

        // Передаємо об'єкт клієнта в сервіс для фільтрації по даті
        return ResponseEntity.ok(newsService.getNewsForClient(user))
    }

    // --- ДЛЯ ДИСПЕТЧЕРА (АДМІНА) ---
    
    @PostMapping("/admin/news")
    fun createNews(@RequestBody req: CreateNewsRequest): ResponseEntity<NewsDto> {
        return ResponseEntity.ok(newsService.createNews(req))
    }

    @GetMapping("/admin/news")
    fun getNewsForAdmin(): ResponseEntity<List<NewsDto>> {
        // Адмін бачить абсолютно всі новини
        return ResponseEntity.ok(newsService.getAllNewsForAdmin())
    }

    @DeleteMapping("/admin/news/{id}")
    fun deleteNews(@PathVariable id: Long): ResponseEntity<Void> {
        newsService.deleteNews(id)
        return ResponseEntity.noContent().build()
    }
}