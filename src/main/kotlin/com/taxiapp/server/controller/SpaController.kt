package com.taxiapp.server.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping

@Controller
class SpaController {

    // Ловит все клиентские веб-маршруты React и перенаправляет их на index.html
    @RequestMapping(value = [
        "/", 
        "/login", 
        "/driver-register", 
        "/driver-register/**",
        "/driver/**",
        "/add-car",       // 👈 ДОБАВЛЕНО: точное совпадение для /add-car
        "/add-car/**",
        "/photo-control/**",
        "/photo-upload/**",
        "/dashboard/**",
        "/{path:[^\\.]*}",
        "/{path1:[^\\.]*}/{path2:[^\\.]*}"
    ])
    fun forward(): String {
        return "forward:/index.html"
    }
}