package com.taxiapp.server.service

import com.taxiapp.server.dto.news.NewsDto
import com.taxiapp.server.model.news.News
import com.taxiapp.server.model.news.NewsTarget
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.repository.NewsRepository
import com.taxiapp.server.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

@Service
class NewsService(
    private val newsRepository: NewsRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val fileStorageService: FileStorageService // Інжектуємо сервіс файлів
) {

    fun createNews(title: String, content: String, target: NewsTarget, image: MultipartFile?): NewsDto {
        
        // 1. Зберігаємо картинку, якщо є
        var imageUrl: String? = null
        if (image != null && !image.isEmpty) {
            imageUrl = fileStorageService.saveFile(image) // Повертає шлях/ім'я файлу
        }

        // 2. Зберігаємо новину в БД
        val news = News(
            title = title,
            content = content,
            target = target,
            imageUrl = imageUrl
        )
        val saved = newsRepository.save(news)

        // 3. --- ЛОГІКА PUSH-ПОВІДОМЛЕНЬ (Таргетована) ---
        Thread {
            val users = when (target) {
                NewsTarget.ALL -> userRepository.findAll()
                NewsTarget.CLIENT -> userRepository.findAll().filter { it.role == Role.CLIENT }
                NewsTarget.DRIVER -> userRepository.findAll().filter { it.role == Role.DRIVER }
            }

            val tokens = users.mapNotNull { it.fcmToken }

            if (tokens.isNotEmpty()) {
                tokens.forEach { token ->
                    // Можна додати imageUrl в data пуш-повідомлення, якщо потрібно
                    notificationService.sendNotificationToToken(token, title, content)
                }
            }
        }.start()
        // ------------------------------

        return mapToDto(saved)
    }

    fun deleteNews(id: Long) {
        if (!newsRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Новина не знайдена")
        }
        newsRepository.deleteById(id)
    }

    fun getAllNewsForAdmin(): List<NewsDto> {
        return newsRepository.findAllByOrderByCreatedAtDesc()
            .map { mapToDto(it) }
    }

    fun getNewsForClient(client: Client): List<NewsDto> {
        val registrationDate = client.createdAt ?: LocalDateTime.MIN 
        
        // Беремо всі новини після дати реєстрації
        val allRecentNews = newsRepository.findAllByCreatedAtAfterOrderByCreatedAtDesc(registrationDate)
        
        // Фільтруємо: тільки для КЛІЄНТІВ або для ВСІХ
        return allRecentNews
            .filter { it.target == NewsTarget.CLIENT || it.target == NewsTarget.ALL }
            .map { mapToDto(it) }
    }

    fun getNewsForDriver(driver: Driver): List<NewsDto> {
        val registrationDate = driver.createdAt ?: LocalDateTime.MIN

        // Беремо всі новини після дати реєстрації
        val allRecentNews = newsRepository.findAllByCreatedAtAfterOrderByCreatedAtDesc(registrationDate)

        // Фільтруємо: тільки для ВОДІЇВ або для ВСІХ
        return allRecentNews
            .filter { it.target == NewsTarget.DRIVER || it.target == NewsTarget.ALL }
            .map { mapToDto(it) }
    }

    private fun mapToDto(news: News): NewsDto {
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        
        // Додаємо повний шлях до картинки, якщо вона є (наприклад, через /uploads/)
        // Або віддаємо як є, якщо фронт сам знає базовий URL
        val finalImageUrl = if (news.imageUrl != null) {
            "/uploads/${news.imageUrl}" // Припустимо, що контролер віддає статику з /uploads
        } else {
            null
        }

        return NewsDto(
            id = news.id,
            title = news.title,
            content = news.content,
            date = news.createdAt.format(formatter),
            target = news.target,
            imageUrl = finalImageUrl
        )
    }
}