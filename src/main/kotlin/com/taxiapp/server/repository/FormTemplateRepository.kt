package com.taxiapp.server.repository

import com.taxiapp.server.model.setting.FormTemplate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface FormTemplateRepository : JpaRepository<FormTemplate, String> {
    // Стандартный метод findById уже есть в JpaRepository, 
    // дополнительные методы пока не нужны.
}