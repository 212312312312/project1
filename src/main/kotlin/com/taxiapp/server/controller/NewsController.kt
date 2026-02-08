package com.taxiapp.server.controller

import com.taxiapp.server.dto.news.NewsDto
import com.taxiapp.server.model.news.NewsTarget
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.UserRepository
import com.taxiapp.server.service.NewsService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.security.Principal

@RestController
@RequestMapping("/api/v1")
class NewsController(
    private val newsService: NewsService,
    private val userRepository: UserRepository
) {

    // --- ДЛЯ КЛІЄНТА ---
    @GetMapping("/client/news")
    fun getNewsForClient(principal: Principal): ResponseEntity<List<NewsDto>> {
        val userLogin = principal.name
        
        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
             user = userRepository.findByUserPhone(userLogin)
                  .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }

        if (user !is Client) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Тільки для клієнтів")
        }

        // Отримуємо новини тільки для CLIENT або ALL
        return ResponseEntity.ok(newsService.getNewsForClient(user))
    }

    // --- ДЛЯ ВОДІЯ (НОВЕ) ---
    @GetMapping("/driver/news")
    fun getNewsForDriver(principal: Principal): ResponseEntity<List<NewsDto>> {
        val userLogin = principal.name

        var user = userRepository.findByUserLogin(userLogin).orElse(null)
        if (user == null) {
            user = userRepository.findByUserPhone(userLogin)
                .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Користувача не знайдено") }
        }

        if (user !is Driver) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Тільки для водіїв")
        }

        // Отримуємо новини тільки для DRIVER або ALL
        return ResponseEntity.ok(newsService.getNewsForDriver(user))
    }

    // --- ДЛЯ ДИСПЕТЧЕРА (АДМІНА) ---
    
    // Оновлений метод створення з картинкою
    @PostMapping("/admin/news", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createNews(
        @RequestParam("title") title: String,
        @RequestParam("content") content: String,
        @RequestParam("target") targetStr: String, // CLIENT, DRIVER, ALL
        @RequestParam("image", required = false) image: MultipartFile?
    ): ResponseEntity<NewsDto> {
        
        val target = try {
            NewsTarget.valueOf(targetStr.uppercase())
        } catch (e: Exception) {
            NewsTarget.ALL
        }

        return ResponseEntity.ok(newsService.createNews(title, content, target, image))
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