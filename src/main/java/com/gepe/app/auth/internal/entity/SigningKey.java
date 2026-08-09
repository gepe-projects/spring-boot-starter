package com.gepe.app.auth.internal.entity;

import com.gepe.app.platform.support.Uuidv7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "signing_keys", schema = "auth")
@Getter
@Setter
public class SigningKey {

    @Id
    private UUID kid;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    @Column(name = "private_key_cipher", nullable = false)
    private byte[] privateKeyCipher;

    @Column(name = "enc_key_id", nullable = false)
    private String encKeyId;

    @Column(name = "algorithm", nullable = false)
    private String algorithm = "RS256";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "not_before", nullable = false)
    private Instant notBefore;

    @Column(name = "not_after")
    private Instant notAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SigningKey() {}

    public SigningKey(UUID kid, String publicKey, byte[] privateKeyCipher, String encKeyId,
                      Status status, Instant notBefore, Instant notAfter) {
        this.kid = kid;
        this.publicKey = publicKey;
        this.privateKeyCipher = privateKeyCipher;
        this.encKeyId = encKeyId;
        this.status = status;
        this.notBefore = notBefore;
        this.notAfter = notAfter;
    }

    @PrePersist
    void prePersist() {
        if (kid == null) kid = Uuidv7.generate();
        if (createdAt == null) createdAt = Instant.now();
    }

    public enum Status {
        ACTIVE,
        PREVIOUS,
        RETIRED
    }
}
