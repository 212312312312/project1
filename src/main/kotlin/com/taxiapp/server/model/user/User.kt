package com.taxiapp.server.model.user

import com.taxiapp.server.model.enums.Role
import jakarta.persistence.*
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.time.LocalDateTime

@Entity
@Table(name = "users")
@Inheritance(strategy = InheritanceType.JOINED)
open class User : UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Column(unique = true, nullable = true)
    var userLogin: String? = null 

    @Column(unique = true, nullable = true)
    var userPhone: String? = null

    // --- НОВЕ ПОЛЕ: EMAIL ---
    @Column(unique = true, nullable = true)
    var email: String? = null

    @Column(nullable = true)
    var passwordHash: String? = null

    @Column(nullable = false)
    lateinit var fullName: String

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var role: Role

    @Column(nullable = false)
    var isBlocked: Boolean = false

    @Column(name = "fcm_token")
    var fcmToken: String? = null

    // Зберігає дату реєстрації.
    @Column(name = "created_at")
    var createdAt: LocalDateTime? = LocalDateTime.now()

    // --- Реализация UserDetails ---

    override fun getAuthorities(): Collection<GrantedAuthority> {
        return listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
    }

    override fun getPassword(): String = passwordHash ?: ""
    
    override fun getUsername(): String = userLogin ?: userPhone ?: ""
    
    override fun isAccountNonExpired(): Boolean = true
    
    open override fun isAccountNonLocked(): Boolean = !isBlocked 
    
    override fun isCredentialsNonExpired(): Boolean = true
    override fun isEnabled(): Boolean = true
}