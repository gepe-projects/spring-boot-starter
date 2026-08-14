package com.gepe.app.auth.internal.repository;

import com.gepe.app.auth.internal.entity.SigningKey;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SigningKeyRepository extends JpaRepository<SigningKey, UUID> {

    Optional<SigningKey> findFirstByStatusOrderByNotBeforeDesc(SigningKey.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SigningKey s WHERE s.status = :status ORDER BY s.notBefore DESC")
    Optional<SigningKey> findActiveForUpdate(@Param("status") SigningKey.Status status);

    List<SigningKey> findByStatusIn(List<SigningKey.Status> statuses);

    List<SigningKey> findAllByOrderByNotBeforeDesc();

    List<SigningKey> findByStatusInAndNotAfterAfter(
            List<SigningKey.Status> statuses, Instant now);

    Optional<SigningKey> findByKidAndStatusIn(UUID kid, List<SigningKey.Status> statuses);
}
