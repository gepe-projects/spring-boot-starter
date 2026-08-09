package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.AuthApi;
import com.gepe.app.auth.api.CurrentUser;
import com.gepe.app.auth.internal.entity.User;
import com.gepe.app.auth.internal.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthApiImpl implements AuthApi {

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
