package com.taxiapp.server.service

import com.taxiapp.server.dto.news.CreateNewsRequest
import com.taxiapp.server.dto.news.NewsDto
import com.taxiapp.server.model.news.News
import com.taxiapp.server.model.user.Client
import com.taxiapp.server.repository.NewsRepository
import com.taxiapp.server.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

@Service
class NewsService(
    private val newsRepository: NewsRepository, // <--- ТУТ БЫЛА ПРОПУЩЕНА ЗАПЯТАЯ
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
) {

    fun createNews(req: CreateNewsRequest): NewsDto {
        val news = News(
            title = req.title,
            content = req.content
        )
        val saved = newsRepository.save(news)

        // --- ЛОГІКА PUSH-ПОВІДОМЛЕНЬ ---
        val users = userRepository.findAll()
        val tokens = users.mapNotNull { it.fcmToken }

        if (tokens.isNotEmpty()) {
            Thread {
                notificationService.sendMulticast(tokens, req.title, req.content)
            }.start()
        }
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
        
        return newsRepository.findAllByCreatedAtAfterOrderByCreatedAtDesc(registrationDate)
            .map { mapToDto(it) }
    }

    private fun mapToDto(news: News): NewsDto {
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        return NewsDto(
            id = news.id,
            title = news.title,
            content = news.content,
            date = news.createdAt.format(formatter)
        )
    }
}