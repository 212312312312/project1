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
        // Перевіряємо, чи вже ініціалізовано, щоб не було помилок при перезавантаженні контексту
        if (FirebaseApp.getApps().isEmpty()) {
            try {
                val resource = ClassPathResource("serviceAccountKey.json")
                // Перевірка на існування файлу, щоб не впало з незрозумілою помилкою
                if (!resource.exists()) {
                    throw RuntimeException("Файл serviceAccountKey.json не знайдено в resources!")
                }
                
                val serviceAccount = resource.inputStream

                val options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build()

                return FirebaseApp.initializeApp(options)
            } catch (e: IOException) {
                throw RuntimeException("Помилка читання serviceAccountKey.json: ${e.message}")
            }
        }
        return FirebaseApp.getInstance()
    }
}