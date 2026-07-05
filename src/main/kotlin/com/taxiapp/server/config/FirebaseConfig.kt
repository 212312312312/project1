package com.taxiapp.server.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import java.io.IOException

@Configuration
class FirebaseConfig {

    @Bean
    fun firebaseApp(): FirebaseApp {
        if (FirebaseApp.getApps().isEmpty()) {
            try {
                val resource = ClassPathResource("serviceAccountKey.json")
                
                // Если файл ключа физически есть в ресурсах — берем его (для локального запуска).
                // Если файла нет — переключаемся на автоматические права среды Google Cloud (для Cloud Run).
                val credentials = if (resource.exists()) {
                    GoogleCredentials.fromStream(resource.inputStream)
                } else {
                    GoogleCredentials.getApplicationDefault()
                }

                val options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build()

                return FirebaseApp.initializeApp(options)
            } catch (e: IOException) {
                throw RuntimeException("Помилка ініціалізації Firebase: ${e.message}")
            }
        }
        return FirebaseApp.getInstance()
    }
}