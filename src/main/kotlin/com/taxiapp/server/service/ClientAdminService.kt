package com.taxiapp.server.service

import com.taxiapp.server.dto.client.ClientDto
import com.taxiapp.server.model.auth.Blacklist
import com.taxiapp.server.repository.BlacklistRepository
import com.taxiapp.server.repository.ClientRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class ClientAdminService(
    private val clientRepository: ClientRepository,
    private val blacklistRepository: BlacklistRepository
) {

    @Transactional(readOnly = true)
    fun getAllClients(): List<ClientDto> {
        return clientRepository.findAll()
            .sortedBy { it.id }
            .map { ClientDto(it) }
    }

    @Transactional
    fun blockClient(clientId: Long): ClientDto {
        val client = clientRepository.findById(clientId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Клієнт не знайдений") }
        
        // 1. Блокуємо акаунт
        client.isBlocked = true
        val updatedClient = clientRepository.save(client)
        
        // 2. Додаємо в Чорний список
        if (client.userPhone != null && !blacklistRepository.existsByPhoneNumber(client.userPhone!!)) {
            blacklistRepository.save(Blacklist(phoneNumber = client.userPhone!!))
        }
        
        return ClientDto(updatedClient)
    }

    @Transactional
    fun unblockClient(clientId: Long): ClientDto {
        val client = clientRepository.findById(clientId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Клієнт не знайдений") }
        
        // 1. Розблокуємо акаунт
        client.isBlocked = false
        val updatedClient = clientRepository.save(client)
        
        // 2. --- ВИПРАВЛЕННЯ: Видаляємо з Чорного списку ---
        if (client.userPhone != null) {
            // Видаляємо, якщо він там є
            if (blacklistRepository.existsByPhoneNumber(client.userPhone!!)) {
                blacklistRepository.deleteByPhoneNumber(client.userPhone!!)
            }
        }
        
        return ClientDto(updatedClient)
    }
}