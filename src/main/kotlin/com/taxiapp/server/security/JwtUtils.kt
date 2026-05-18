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
    @Value("\${jwt.secret}") private val secretKeyString: String,
    @Value("\${jwt.expiration}") private val jwtExpirationMs: Long // <-- ДОБАВЛЕНО
) {

    private fun getSignInKey(): Key {
        val keyBytes = Decoders.BASE64.decode(secretKeyString)
        return Keys.hmacShaKeyFor(keyBytes)
    }

    fun extractUsername(token: String): String? {
        return extractClaim(token, Claims::getSubject)
    }

    fun extractUserId(token: String): Long {
        val claims = extractAllClaims(token)
        return (claims["userId"] as Number).toLong()
    }

    fun <T> extractClaim(token: String, claimsResolver: Function<Claims, T>): T {
        val claims = extractAllClaims(token)
        return claimsResolver.apply(claims)
    }

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
            // --- ИЗМЕНЕНИЕ: Используем короткое время жизни ---
            .setExpiration(Date(System.currentTimeMillis() + jwtExpirationMs)) 
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