package com.taxiapp.server.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping

@Controller
class SpaController {

    // Ловит все клиентские веб-маршруты React и перенаправляет их на index.html,
    // исключая системные пути сокетов (/ws-taxi/**), REST API (/api/**) и статических загрузок (/uploads/**)
    @RequestMapping(value = [
        "/", 
        "/login", 
        "/driver-register", 
        "/driver-register/**",
        "/driver/**",
        "/add-car",
        "/add-car/**",
        "/photo-control/**",
        "/photo-upload/**",
        "/dashboard/**",
        "/{path:^(?!api|ws-taxi|uploads|error)[^\\.]*}",
        "/{path1:^(?!api|ws-taxi|uploads|error)[^\\.]*}/{path2:[^\\.]*}"
    ])
    fun forward(): String {
        return "forward:/index.html"
    }
}