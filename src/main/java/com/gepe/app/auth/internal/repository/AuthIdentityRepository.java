package com.gepe.app.auth.internal.repository;

import com.gepe.app.auth.internal.entity.AuthIdentity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, UUID> {

    Optional<AuthIdentity> findByProviderAndProviderId(String provider, String providerId);

    List<AuthIdentity> findByUserId(UUID userId);

    boolean existsByUserIdAndProvider(UUID userId, String provider);
}
