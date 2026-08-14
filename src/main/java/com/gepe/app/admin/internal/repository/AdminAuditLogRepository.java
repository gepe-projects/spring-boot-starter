package com.gepe.app.admin.internal.repository;

import com.gepe.app.admin.internal.entity.AdminAuditLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {

    /** Keyset page audit log — AGENTS.md §4: ORDER BY (created_at DESC, id DESC). */
    @Query("""
           select l from AdminAuditLog l
           where l.createdAt < :afterCreatedAt
              or (l.createdAt = :afterCreatedAt and l.id < :afterId)
           order by l.createdAt desc, l.id desc
           """)
    List<AdminAuditLog> findPage(@Param("afterCreatedAt") Instant afterCreatedAt,
                                 @Param("afterId") UUID afterId,
                                 Pageable pageable);
}
