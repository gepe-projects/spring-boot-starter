package com.gepe.app.auth.internal.repository;

import com.gepe.app.auth.api.UserStatus;
import com.gepe.app.auth.internal.entity.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Keyset page untuk admin listing — AGENTS.md §4: ORDER BY (created_at DESC, id DESC),
     * id sebagai tiebreaker terakhir. Memakai index {@code idx_users_created}.
     */
    @Query("""
           select u from User u
           where u.createdAt < :afterCreatedAt
              or (u.createdAt = :afterCreatedAt and u.id < :afterId)
           order by u.createdAt desc, u.id desc
           """)
    List<User> findAdminPage(@Param("afterCreatedAt") Instant afterCreatedAt,
                             @Param("afterId") UUID afterId,
                             Pageable pageable);

    /**
     * Versi ber-filter status — memakai index {@code idx_users_status_created}
     * (status, created_at DESC, id DESC).
     */
    @Query("""
           select u from User u
           where u.status = :status
             and (u.createdAt < :afterCreatedAt or (u.createdAt = :afterCreatedAt and u.id < :afterId))
           order by u.createdAt desc, u.id desc
           """)
    List<User> findAdminPageByStatus(@Param("status") UserStatus status,
                                     @Param("afterCreatedAt") Instant afterCreatedAt,
                                     @Param("afterId") UUID afterId,
                                     Pageable pageable);

    /** Jumlah user yang memegang role tertentu (dipakai guard "admin terakhir"). */
    @Query("select count(distinct u) from User u join u.roles r where r.name = :roleName")
    long countUsersWithRole(@Param("roleName") String roleName);

    /** Jumlah user dengan role tertentu DAN status tertentu (dipakai guard "super admin aktif terakhir"). */
    @Query("""
           select count(distinct u) from User u join u.roles r
           where r.name = :roleName and u.status = :status
           """)
    long countUsersWithRoleAndStatus(@Param("roleName") String roleName,
                                     @Param("status") UserStatus status);
}
