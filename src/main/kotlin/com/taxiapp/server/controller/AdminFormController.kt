package com.taxiapp.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.taxiapp.server.model.setting.FormTemplate
import com.taxiapp.server.repository.FormTemplateRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
// ИСПРАВЛЕНИЕ ТУТ: Добавили /v1, чтобы совпадало с React
@RequestMapping("/api/v1/admin/forms") 
class AdminFormController(
    private val repository: FormTemplateRepository
) {
    private val mapper = ObjectMapper()

    // Получить список всех форм
    @GetMapping
    fun getAll(): List<FormTemplate> = repository.findAll()

    // Получить структуру конкретной формы
    @GetMapping("/{key}")
    fun getOne(@PathVariable key: String): ResponseEntity<Any> {
        val template = repository.findById(key)
        if (template.isPresent) {
            val jsonObject = mapper.readTree(template.get().schemaJson)
            return ResponseEntity.ok(jsonObject)
        }
        return ResponseEntity.notFound().build()
    }

    // Обновить форму
    @PutMapping("/{key}")
    fun update(@PathVariable key: String, @RequestBody schema: List<Map<String, Any>>): ResponseEntity<FormTemplate> {
        val jsonString = mapper.writeValueAsString(schema)
        
        val template = repository.findById(key).orElse(FormTemplate(key, jsonString))
        template.schemaJson = jsonString
        
        return ResponseEntity.ok(repository.save(template))
    }
}