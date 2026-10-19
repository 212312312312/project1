package com.taxiapp.server.service

import com.taxiapp.server.dto.news.NewsDto
import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.news.News
import com.taxiapp.server.model.news.NewsTarget
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.model.user.Driver
import com.taxiapp.server.repository.NewsRepository
import com.taxiapp.server.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

@Service
class NewsService(
    private val newsRepository: NewsRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val fileStorageService: FileStorageService
) {
    private val logger = LoggerFactory.getLogger(NewsService::class.java)

    fun createNews(title: String, content: String, target: NewsTarget, image: MultipartFile?): NewsDto {
        // 1. Зберігаємо картинку, якщо є
        var imageUrl: String? = null
        if (image != null && !image.isEmpty) {
            imageUrl = fileStorageService.saveFile(image)
        }

        // 2. Зберігаємо новину в БД
        val news = News(
            title = title,
            content = content,
            target = target,
            imageUrl = imageUrl
        )
        val saved = newsRepository.save(news)

        // 3. HIGH-LOAD PUSH-РОЗСИЛКА:
        // Замість userRepository.findAll() (що вивантажує 100k+ сутностей User у пам'ять і викликає OOM),
        // вибираємо лише рядки fcmToken напряму з індексу БД та відправляємо неблокуюче через sendAsync
        CompletableFuture.runAsync {
            try {
                val tokens = when (target) {
                    NewsTarget.ALL -> userRepository.findAllActiveFcmTokens()
                    NewsTarget.CLIENT -> userRepository.findAllActiveFcmTokensByRole(Role.CLIENT)
                    NewsTarget.DRIVER -> userRepository.findAllActiveFcmTokensByRole(Role.DRIVER)
                }

                logger.info(">>> [News Broadcast] Починаємо розсилку новини '{}' для {} отримувачів (target={})", title, tokens.size, target)

                tokens.forEachIndexed { index, token ->
                    notificationService.sendNotificationToToken(token, title, content)
                    // Легкий троттлінг кожні 500 пушів, щоб не переповнити внутрішню чергу Netty в Firebase SDK
                    if ((index + 1) % 500 == 0) {
                        Thread.sleep(50)
                    }
                }
            } catch (e: Exception) {
                logger.error(">>> [News Broadcast] Помилка під час розсилки пушів новини: {}", e.message)
            }
        }

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
        val allRecentNews = newsRepository.findAllByCreatedAtAfterOrderByCreatedAtDesc(registrationDate)

        return allRecentNews
            .filter { it.target == NewsTarget.CLIENT || it.target == NewsTarget.ALL }
            .map { mapToDto(it) }
    }

    fun getNewsForDriver(driver: Driver): List<NewsDto> {
        val registrationDate = driver.createdAt ?: LocalDateTime.MIN
        val allRecentNews = newsRepository.findAllByCreatedAtAfterOrderByCreatedAtDesc(registrationDate)

        return allRecentNews
            .filter { it.target == NewsTarget.DRIVER || it.target == NewsTarget.ALL }
            .map { mapToDto(it) }
    }

    private fun mapToDto(news: News): NewsDto {
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val finalImageUrl = if (news.imageUrl != null) {
            "/uploads/${news.imageUrl}"
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