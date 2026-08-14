package com.gepe.app.user.internal.repository;

import com.gepe.app.user.internal.entity.UserProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    boolean existsByNickname(String nickname);

    boolean existsByNicknameAndUserIdNot(String nickname, UUID userId);
}
