package ru.ifmo.skhodim.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ifmo.skhodim.domain.AppUser;
import ru.ifmo.skhodim.domain.UserProfile;
import ru.ifmo.skhodim.exception.BusinessRuleException;
import ru.ifmo.skhodim.exception.NotFoundException;
import ru.ifmo.skhodim.repository.UserRepository;

import java.util.List;

/**
 * Бизнес-логика UC-01 (регистрация), UC-14 (профиль) и UC-16 (блокировка пользователя).
 * Правило «3–5 интересов к концу транзакции» проверяется БД отложенным триггером
 * (trg_user_interests_count, sql/02_logic.sql); сервис лишь предварительно
 * отсекает заведомо некорректный ввод, не дублируя логику БД.
 */
@Service
public class UserService {

    private static final int MIN_INTERESTS = 3;
    private static final int MAX_INTERESTS = 5;

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public long register(String email, String rawPassword, String nickname, List<Long> categoryIds) {
        if (categoryIds == null || categoryIds.size() < MIN_INTERESTS || categoryIds.size() > MAX_INTERESTS) {
            throw new BusinessRuleException(
                    "Interests count must be between " + MIN_INTERESTS + " and " + MAX_INTERESTS);
        }
        try {
            return userRepository.register(email, passwordEncoder.encode(rawPassword), nickname, categoryIds);
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException("Email already registered: " + email, e);
        }
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

    public boolean checkPassword(AppUser user, String rawPassword) {
        return passwordEncoder.matches(rawPassword, user.passwordHash());
    }

    /** UC-16: блокировка отменяет ожидающие заявки (trg_ban_cancels_requests). */
    public void ban(long userId) {
        userRepository.setBanned(userId, true);
    }

    public void unban(long userId) {
        userRepository.setBanned(userId, false);
    }
}
