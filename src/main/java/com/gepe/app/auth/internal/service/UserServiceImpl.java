package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.UserDto;
import com.gepe.app.auth.api.UserService;
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
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    public Optional<UserDto> findByUserId(UUID userId) {
        return userRepository.findById(userId).map(this::toUserDto);
    }

    @Override
    public Optional<UserDto> findByEmail(String email) {
        return userRepository.findByEmail(email).map(this::toUserDto);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    private UserDto toUserDto(User u) {
        return new UserDto(u.getId(), u.getEmail(), u.getEmailVerifiedAt() != null,
                RoleResolver.effectiveRoles(u));
    }
}
