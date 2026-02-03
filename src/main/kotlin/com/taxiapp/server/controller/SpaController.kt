package com.taxiapp.server.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping

@Controller
class SpaController {

    // Этот метод ловит все маршруты, которые НЕ являются API, и перенаправляет их на React
    // Мы явно прописываем /driver-register, чтобы это точно сработало
    @RequestMapping(value = [
        "/", 
        "/login", 
        "/driver-register", 
        "/dashboard/**",
        "/{path:[^\\.]*}" // Ловит все остальные пути без точки (не файлы)
    ])
    fun forward(): String {
        // "forward:/index.html" означает: "Отдай файл index.html из папки static"
        return "forward:/index.html"
    }
}