package com.gepe.app.user.internal.entity;

import com.gepe.app.user.api.dto.Gender;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Profil 1:1 per auth user. userId = plain UUID bernilai auth.users.id (tanpa FK cross-schema).
 * Public karena dipakai lintas sub-package dalam module (mirror entity/User.java di module auth);
 * module LAIN tetap dilarang import ini — batas dijaga ModularityTests.
 *
 * <p>Schema "user" adalah reserved word di Postgres → WAJIB di-quote. Backtick `` `user` `` adalah
 * konvensi quoting Hibernate (dirender jadi {@code "user"} di SQL). Jangan ganti ke plain
 * {@code "user"} tanpa tanda kutip: setting {@code hibernate.auto_quote_keyword} sudah TIDAK ADA
 * di Hibernate 7, jadi identifier tidak akan pernah di-quote otomatis.
 */
@Entity
@Table(name = "profile", schema = "`user`")
@Getter
@Setter
public class UserProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(length = 50)
    private String nickname;

    @Column(name = "avatar_url", length = 2048)
    private String avatarUrl;

    @Column(length = 500)
    private String bio;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Gender gender;

    @Column(length = 30)
    private String phone;

    @Column(length = 255)
    private String location;

    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(nullable = false, length = 10)
    private String locale = "en";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected UserProfile() {}

    public UserProfile(UUID userId, String displayName, String avatarUrl) {
        this.userId = userId;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    public void touch() {
        updatedAt = Instant.now();
    }
}
