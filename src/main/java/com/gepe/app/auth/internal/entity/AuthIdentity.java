package com.gepe.app.auth.internal.entity;

import com.gepe.app.platform.support.Uuidv7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Satu baris = satu metode login untuk satu user.
 *  - provider "credentials" → provider_id = email, password_hash terisi
 *  - provider "google"     → provider_id = sub google, password_hash NULL
 */
@Entity
@Table(name = "auth_identities", schema = "auth")
@Getter
@Setter
public class AuthIdentity {

    public static final String PROVIDER_CREDENTIALS = "credentials";
    public static final String PROVIDER_GOOGLE = "google";

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_id", nullable = false, length = 255)
    private String providerId;

    @Column(length = 320)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuthIdentity() {}

    public AuthIdentity(UUID userId, String provider, String providerId, String email, String passwordHash) {
        this.userId = userId;
        this.provider = provider;
        this.providerId = providerId;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = Uuidv7.generate();
        if (createdAt == null) createdAt = Instant.now();
    }
}
