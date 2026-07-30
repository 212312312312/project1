package com.taxiapp.server.service

import com.taxiapp.server.dto.dispatcher.CreateDispatcherRequest
import com.taxiapp.server.dto.dispatcher.DispatcherDto
import com.taxiapp.server.model.enums.Role
import com.taxiapp.server.model.user.User
import com.taxiapp.server.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class DispatcherAdminService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {

    // (Read) Отримати всіх DISPATCHER
    @Transactional(readOnly = true)
    fun getAllDispatchers(): List<DispatcherDto> {
        return userRepository.findAllByRole(Role.DISPATCHER).map { DispatcherDto(it) }
    }

    // (Create) Створити DISPATCHER
    @Transactional
    fun createDispatcher(request: CreateDispatcherRequest): DispatcherDto {
        if (userRepository.findByUserLogin(request.userLogin).isPresent) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Цей логін вже зайнятий")
        }
        if (request.password == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Пароль є обов'язковим для нового диспетчера")
        }

        val dispatcher = User().apply {
            this.userLogin = request.userLogin
            this.fullName = request.fullName
            this.passwordHash = passwordEncoder.encode(request.password)
            this.role = Role.DISPATCHER
            this.isBlocked = false
        }
        val saved = userRepository.save(dispatcher)
        return DispatcherDto(saved)
    }

    // (Update) Оновити DISPATCHER (тільки ПІБ, пароль опціонально)
    @Transactional
    fun updateDispatcher(id: Long, request: CreateDispatcherRequest): DispatcherDto {
        val dispatcher = userRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Диспетчер $id не знайдений") }
        
        if (dispatcher.role != Role.DISPATCHER) {
             throw ResponseStatusException(HttpStatus.FORBIDDEN, "Можна редагувати тільки диспетчерів")
        }

        // Перевірка на унікальність логіну (якщо він змінюється)
        if (dispatcher.userLogin != request.userLogin) {
            if (userRepository.findByUserLogin(request.userLogin).isPresent) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Цей логін вже зайнятий")
            }
            dispatcher.userLogin = request.userLogin
        }
        
        dispatcher.fullName = request.fullName
        
        // Оновлюємо пароль, ТІЛЬКИ якщо він був наданий
        if (request.password != null) {
            dispatcher.passwordHash = passwordEncoder.encode(request.password)
        }

        val saved = userRepository.save(dispatcher)
        return DispatcherDto(saved)
    }

    // (Delete) Видалити DISPATCHER
    @Transactional
    fun deleteDispatcher(id: Long) {
        val dispatcher = userRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Диспетчер $id не знайдений") }
            
        if (dispatcher.role != Role.DISPATCHER) {
             throw ResponseStatusException(HttpStatus.FORBIDDEN, "Можна видаляти тільки диспетчерів")
        }
        userRepository.deleteById(id)
    }
}