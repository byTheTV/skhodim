package ru.ifmo.skhodim.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ifmo.skhodim.domain.AppUser;
import ru.ifmo.skhodim.domain.UserProfile;
import ru.ifmo.skhodim.exception.BusinessRuleException;
import ru.ifmo.skhodim.exception.NotFoundException;
import ru.ifmo.skhodim.repository.UserRepository;

import java.util.List;

/**
 * Бизнес-логика UC-01 (регистрация и вход) и профиль вкуса FR-2.
 */
@Service
public class UserService {

    private static final int MIN_INTERESTS = 3;
    private static final int MAX_INTERESTS = 5;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public long register(String email, String rawPassword, String nickname, List<Long> categoryIds) {
        validateInterests(categoryIds);
        if (email == null || email.isBlank() || rawPassword == null || rawPassword.length() < 8) {
            throw new BusinessRuleException("Email and password (min 8 chars) are required");
        }
        try {
            return userRepository.register(email.trim(), passwordEncoder.encode(rawPassword), nickname, categoryIds);
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException("Email already registered: " + email, e);
        }
    }

    public AppUser authenticate(String email, String rawPassword) {
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessRuleException("Invalid email or password"));
        if (user.banned()) {
            throw new BusinessRuleException("User is banned");
        }
        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            throw new BusinessRuleException("Invalid email or password");
        }
        return user;
    }

    public AppUser getUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    public UserProfile getProfile(long userId) {
        return userRepository.findProfile(userId)
                .orElseThrow(() -> new NotFoundException("Profile not found: " + userId));
    }

    public List<Long> getInterests(long userId) {
        return userRepository.findInterestIds(userId);
    }

    @Transactional
    public void updateProfile(long userId, String nickname, String avatarKey, String metro, List<Long> categoryIds) {
        getProfile(userId);
        if (nickname == null || nickname.isBlank()) {
            throw new BusinessRuleException("Nickname is required");
        }
        userRepository.updateProfile(userId, nickname.trim(), avatarKey, metro);
        if (categoryIds != null) {
            validateInterests(categoryIds);
            userRepository.replaceInterests(userId, categoryIds);
        }
    }

    public boolean checkPassword(AppUser user, String rawPassword) {
        return passwordEncoder.matches(rawPassword, user.passwordHash());
    }

    public void ban(long userId) {
        userRepository.setBanned(userId, true);
    }

    public void unban(long userId) {
        userRepository.setBanned(userId, false);
    }

    private static void validateInterests(List<Long> categoryIds) {
        if (categoryIds == null || categoryIds.size() < MIN_INTERESTS || categoryIds.size() > MAX_INTERESTS) {
            throw new BusinessRuleException(
                    "Interests count must be between " + MIN_INTERESTS + " and " + MAX_INTERESTS);
        }
    }
}
