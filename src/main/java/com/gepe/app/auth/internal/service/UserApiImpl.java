package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.CurrentUser;
import com.gepe.app.auth.api.UserApi;
import com.gepe.app.auth.internal.entity.User;
import com.gepe.app.auth.internal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserApiImpl implements UserApi {

    private final UserRepository userRepository;

    @Override
    public Optional<CurrentUser> findByUserId(UUID userId) {
        return userRepository.findById(userId).map(this::toCurrentUser);
    }

    @Override
    public Optional<CurrentUser> findByEmail(String email) {
        return userRepository.findByEmail(email).map(this::toCurrentUser);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    private CurrentUser toCurrentUser(User u) {
        return new CurrentUser(u.getId(), u.getEmail(), u.getEmailVerifiedAt() != null);
    }
}
