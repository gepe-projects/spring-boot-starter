package com.gepe.app.auth.internal.entity;

import com.gepe.app.auth.api.dto.UserStatus;
import com.gepe.app.platform.support.Uuidv7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users", schema = "auth")
@Getter
@Setter
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles", schema = "auth",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role"))
    private Set<Role> roles = new HashSet<>();

    protected User() {}

    public User(String email, Instant emailVerifiedAt) {
        this.email = email;
        this.emailVerifiedAt = emailVerifiedAt;
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = Uuidv7.generate();
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
        if (statusChangedAt == null) statusChangedAt = createdAt;
    }

    public void markEmailVerified() {
        if (emailVerifiedAt == null) emailVerifiedAt = Instant.now();
    }

    /** Ganti status akun; catat {@code statusChangedAt} hanya bila status benar-benar berubah. */
    public void changeStatus(UserStatus newStatus) {
        if (newStatus == null || newStatus == status) {
            return;
        }
        this.status = newStatus;
        this.statusChangedAt = Instant.now();
    }

    public void addRole(Role role) {
        roles.add(role);
    }

    /** Replace penuh set role (dipakai admin assign roles) — clear+addAll pada set persistent. */
    public void replaceRoles(Set<Role> newRoles) {
        roles.clear();
        roles.addAll(newRoles);
    }
}
