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
    open val id: Long = 0

    @Column(unique = true, nullable = false, updatable = false)
    open val uuid: String = java.util.UUID.randomUUID().toString()   

    @Column(unique = true, nullable = true)
    open var userLogin: String? = null 

    @Column(unique = true, nullable = true)
    open var userPhone: String? = null

    @Column(unique = true, nullable = true)
    open var email: String? = null

    @Column(nullable = true)
    open var passwordHash: String? = null

    @Column(nullable = false)
    open lateinit var fullName: String

    @Column(name = "deletion_requested_at")
    open var deletionRequestedAt: LocalDateTime? = null

    @Column(name = "acquisition_source", nullable = true)
    open var acquisitionSource: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    open lateinit var role: Role

    @Column(nullable = false)
    open var isBlocked: Boolean = false

    @Column(name = "fcm_token")
    open var fcmToken: String? = null

    @Column(name = "created_at")
    open var createdAt: LocalDateTime? = LocalDateTime.now()

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