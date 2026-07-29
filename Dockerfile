# Этап 1: Сборка приложения
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Копируем pom.xml и исходный код сервера
COPY pom.xml .
COPY src ./src

# Собираем JAR-файл, пропуская тесты для скорости
RUN mvn clean package -DskipTests

# Этап 2: Минимальный образ для запуска
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Копируем собранный JAR-ник из предыдущего этапа
COPY --from=build /app/target/*.jar app.jar

# Cloud Run автоматически передает порт (обычно 8080) в переменную PORT
EXPOSE 8080

# Команда для запуска сервера
ENTRYPOINT ["java", "-jar", "app.jar"]