package com.taxiapp.server.controller

import com.taxiapp.server.service.SettingsService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/admin/settings")
class AdminSettingsController(
    private val settingsService: SettingsService
) {

    @GetMapping
    // ИСПРАВЛЕНИЕ: Разрешаем и 'ADMINISTRATOR', и 'ROLE_ADMINISTRATOR'
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun getSettings(): ResponseEntity<Map<String, String?>> {
        return ResponseEntity.ok(settingsService.getAllSettings())
    }

    @PostMapping("/upload")
    // ИСПРАВЛЕНИЕ: Разрешаем и 'ADMINISTRATOR', и 'ROLE_ADMINISTRATOR'
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun uploadImage(
        @RequestParam("key") key: String,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<Map<String, String>> {
        val url = settingsService.uploadSettingImage(key, file)
        return ResponseEntity.ok(mapOf("url" to url))
    }

    @PostMapping("/save")
    @PreAuthorize("hasAnyAuthority('ADMINISTRATOR', 'ROLE_ADMINISTRATOR')")
    fun saveSettings(@RequestBody settings: Map<String, String>): ResponseEntity<String> {
        settingsService.saveSettings(settings)
        return ResponseEntity.ok("Налаштування збережено")
    }
}