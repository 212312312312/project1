package com.taxiapp.server.model.enums

enum class CityGrade {
    GRADE_A,
    GRADE_B
}

enum class CarBodyType(val labelUk: String) {
    STANDARD("Седан (Стандарт)"),
    COMFORT("Комфорт"),
    BUSINESS("Бізнес"),
    UNIVERSAL("Універсал"),
    SUV("Кросовер / Позашляховик"),
    MINIBUS("Мікроавтобус")
}

enum class TariffStatus {
    BUSINESS,
    COMFORT,
    STANDARD,
    RESTRICTED
}

enum class CarColor(val key: String, val labelUk: String, val hexCode: String) {
    WHITE("WHITE", "Білий", "#F5F6F8"),
    BLACK("BLACK", "Чорний", "#1C1D21"),
    SILVER("SILVER", "Сріблястий", "#C4C7CE"),
    GRAY("GRAY", "Сірий", "#6E737B"),
    BLUE("BLUE", "Синій", "#1E56A0"),
    RED("RED", "Червоний", "#C82333"),
    GREEN("GREEN", "Зелений", "#2E7D32"),
    YELLOW("YELLOW", "Жовтий", "#FBC02D"),
    BROWN("BROWN", "Коричневий", "#5D4037"),
    BEIGE("BEIGE", "Бежевий", "#D7CCC8"),
    ORANGE("ORANGE", "Помаранчевий", "#EF6C00");

    companion object {
        fun fromRawString(raw: String?): CarColor {
            if (raw.isNullOrBlank()) return WHITE
            val clean = raw.trim().lowercase().replace('і', 'и')

            // 1. Прямое совпадение по ключу или названию
            entries.find {
                it.key.equals(raw.trim(), ignoreCase = true) ||
                it.labelUk.lowercase().replace('і', 'и') == clean
            }?.let { return it }

            // 2. Умный парсинг для старых записей БД и партнерки EvoS
            return when {
                clean.contains("чорн") || clean.contains("черн") || clean.contains("black") -> BLACK
                clean.contains("сриб") || clean.contains("серебр") || clean.contains("silver") -> SILVER
                clean.contains("сир") || clean.contains("сер") || clean.contains("асфальт") || clean.contains("графит") || clean.contains("gray") || clean.contains("grey") -> GRAY
                clean.contains("син") || clean.contains("блакит") || clean.contains("голуб") || clean.contains("blue") -> BLUE
                clean.contains("червон") || clean.contains("красн") || clean.contains("борд") || clean.contains("вишн") || clean.contains("red") -> RED
                clean.contains("зелен") || clean.contains("хак") || clean.contains("оливк") || clean.contains("green") -> GREEN
                clean.contains("жовт") || clean.contains("желт") || clean.contains("yellow") -> YELLOW
                clean.contains("коричн") || clean.contains("шоколад") || clean.contains("brown") -> BROWN
                clean.contains("беж") || clean.contains("золот") || clean.contains("пісоч") || clean.contains("шампан") || clean.contains("beige") -> BEIGE
                clean.contains("помаранч") || clean.contains("оранж") || clean.contains("orange") -> ORANGE
                clean.contains("бил") || clean.contains("бел") || clean.contains("white") -> WHITE
                else -> WHITE
            }
        }
    }
}

enum class CarVisualType {
    STANDARD,
    COMFORT,
    BUSINESS,
    UNIVERSAL,
    SUV,
    MINIBUS;

    companion object {
        fun resolveForCar(
            carType: String?,
            carModel: String?,
            allowedTariffNames: Collection<String> = emptyList()
        ): CarVisualType {
            val rawType = (carType ?: "").trim().lowercase().replace('і', 'и')
            val rawModel = (carModel ?: "").trim().lowercase()
            val tariffsJoined = allowedTariffNames.joinToString(" ").lowercase().replace('і', 'и')

            // 1. Сначала проверяем ярко выраженный кузов (Бус / Универсал / Кроссовер)
            if (rawType.contains("minibus") || rawType.contains("микроавтобус") || rawType.contains("бус") ||
                rawModel.contains("vito") || rawModel.contains("viano") || rawModel.contains("transporter") ||
                rawModel.contains("multivan") || rawModel.contains("trafic") || rawModel.contains("vivaro") ||
                rawModel.contains("sprinter") || rawModel.contains("master")
            ) {
                return MINIBUS
            }

            if (rawType.contains("universal") || rawType.contains("универсал")) {
                return UNIVERSAL
            }

            if (rawType.contains("suv") || rawType.contains("кросовер") || rawType.contains("кроссовер") || rawType.contains("позашляховик")) {
                return SUV
            }

            // 2. Сначала проверяем явный carType из базы данных
            if (rawType.contains("business") || rawType.contains("бизнес")) return BUSINESS
            if (rawType.contains("comfort") || rawType.contains("комфорт")) return COMFORT
            if (rawType.contains("standard") || rawType.contains("стандарт")) return STANDARD

            // 3. Только если carType пустой — берем класс по тарифам водителя
            if (tariffsJoined.contains("business") || tariffsJoined.contains("бизнес")) return BUSINESS
            if (tariffsJoined.contains("comfort") || tariffsJoined.contains("комфорт")) return COMFORT

            return STANDARD
        }
    }
}