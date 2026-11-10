package ru.ifmo.skhodim.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.ifmo.skhodim.domain.AppUser;
import ru.ifmo.skhodim.domain.UserProfile;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * Уровень хранения: учётные записи, профили и интересы (раздел 4.2 отчёта этапа 2).
 * Проверка 3–5 интересов выполняется отложенным constraint-триггером при COMMIT
 * (trg_user_interests_count в 02_logic.sql), поэтому вставки выполняются одной транзакцией.
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long register(String email, String passwordHash, String nickname, List<Long> categoryIds) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO app_user (email, password_hash) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, email);
            ps.setString(2, passwordHash);
            return ps;
        }, keyHolder);
        Object generated = keyHolder.getKeys() == null ? null : keyHolder.getKeys().get("id");
        if (!(generated instanceof Number number)) {
            throw new IllegalStateException("INSERT INTO app_user did not return id");
        }
        long userId = number.longValue();

        jdbc.update("INSERT INTO user_profile (user_id, nickname) VALUES (?, ?)", userId, nickname);
        replaceInterests(userId, categoryIds);
        return userId;
    }

    @Transactional
    public void updateProfile(
            long userId, String nickname, String avatarKey, String metro,
            String displayName, String bio, boolean notificationsEnabled
    ) {
        jdbc.update(
                "UPDATE user_profile SET nickname = ?, avatar_key = ?, metro = ?, display_name = ?, bio = ?, "
                        + "notifications_enabled = ? WHERE user_id = ?",
                nickname, avatarKey, metro, displayName, bio, notificationsEnabled, userId);
    }

    @Transactional
    public void replaceInterests(long userId, List<Long> categoryIds) {
        jdbc.update("DELETE FROM user_interest WHERE user_id = ?", userId);
        for (Long categoryId : categoryIds) {
            jdbc.update("INSERT INTO user_interest (user_id, category_id) VALUES (?, ?)", userId, categoryId);
        }
    }

    public Optional<AppUser> findById(long id) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM app_user WHERE id = ?", (rs, n) -> RowMappers.appUser(rs), id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<AppUser> findByEmail(String email) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM app_user WHERE lower(email) = lower(?)", (rs, n) -> RowMappers.appUser(rs), email));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<UserProfile> findProfile(long userId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM user_profile WHERE user_id = ?", (rs, n) -> RowMappers.userProfile(rs), userId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Long> findInterestIds(long userId) {
        return jdbc.queryForList(
                "SELECT category_id FROM user_interest WHERE user_id = ?", Long.class, userId);
    }

    public void setBanned(long userId, boolean banned) {
        jdbc.update("UPDATE app_user SET is_banned = ? WHERE id = ?", banned, userId);
    }
}
