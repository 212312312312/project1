package com.taxiapp.server.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component
import java.security.Key
import java.util.*
import java.util.function.Function

@Component
class JwtUtils(
    // --- ИЗМЕНЕНИЕ: Берем ключ из application.properties ---
    @Value("\${jwt.secret}") private val secretKeyString: String
) {

    private fun getSignInKey(): Key {
        val keyBytes = Decoders.BASE64.decode(secretKeyString) // Используем переменную из конструктора
        return Keys.hmacShaKeyFor(keyBytes)
    }

    fun extractUsername(token: String): String? {
        return extractClaim(token, Claims::getSubject)
    }

    // --- ДОДАНО ЦЕЙ МЕТОД ---
    // Потрібен для отримання ID водія в DriverAppController
    fun extractUserId(token: String): Long {
        val claims = extractAllClaims(token)
        // JWT бібліотека може повертати числа як Integer, тому надійно приводимо до Long
        return (claims["userId"] as Number).toLong()
    }
    // ------------------------

    fun <T> extractClaim(token: String, claimsResolver: Function<Claims, T>): T {
        val claims = extractAllClaims(token)
        return claimsResolver.apply(claims)
    }

    // --- НОВЫЙ МЕТОД ДЛЯ REFRESH TOKEN ---
    fun generateRefreshToken(): String {
        return java.util.UUID.randomUUID().toString()
    }

    private fun extractAllClaims(token: String): Claims {
        return Jwts.parserBuilder()
            .setSigningKey(getSignInKey())
            .build()
            .parseClaimsJws(token)
            .body
    }

    fun generateToken(userDetails: UserDetails, userId: Long, role: String): String {
        val claims = HashMap<String, Any>()
        claims["userId"] = userId
        claims["role"] = role 
        return createToken(claims, userDetails.username)
    }

    private fun createToken(claims: Map<String, Any>, subject: String): String {
        return Jwts.builder()
            .setClaims(claims)
            .setSubject(subject)
            .setIssuedAt(Date(System.currentTimeMillis()))
            .setExpiration(Date(System.currentTimeMillis() + 1000 * 60 * 60 * 24)) // 24 години
            .signWith(getSignInKey(), SignatureAlgorithm.HS256)
            .compact()
    }

    fun validateToken(token: String, userDetails: UserDetails): Boolean {
        val username = extractUsername(token)
        return (username == userDetails.username && !isTokenExpired(token))
    }

    private fun isTokenExpired(token: String): Boolean {
        return extractExpiration(token).before(Date())
    }

    private fun extractExpiration(token: String): Date {
        return extractClaim(token, Claims::getExpiration)
    }
}