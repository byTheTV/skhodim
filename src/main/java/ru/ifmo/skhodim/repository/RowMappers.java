package ru.ifmo.skhodim.repository;

import ru.ifmo.skhodim.domain.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;

/** Общие RowMapper-лямбды уровня хранения: перевод строк JDBC в доменные record'ы. */
final class RowMappers {

    private RowMappers() {
    }

    static OffsetDateTime ts(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    static BigDecimal nullableDecimal(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    static AppUser appUser(ResultSet rs) throws SQLException {
        return new AppUser(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getBoolean("is_banned"),
                ts(rs, "created_at")
        );
    }

    static UserProfile userProfile(ResultSet rs) throws SQLException {
        return new UserProfile(
                rs.getLong("user_id"),
                rs.getString("nickname"),
                rs.getString("avatar_key"),
                rs.getString("metro"),
                rs.getString("display_name"),
                rs.getString("bio"),
                rs.getBoolean("notifications_enabled")
        );
    }

    static Category category(ResultSet rs) throws SQLException {
        return new Category(rs.getLong("id"), rs.getString("slug"), rs.getString("name"));
    }

    static Tag tag(ResultSet rs) throws SQLException {
        return new Tag(rs.getLong("id"), rs.getString("name"));
    }

    static Place place(ResultSet rs) throws SQLException {
        return new Place(
                rs.getLong("id"),
                rs.getLong("source_id"),
                rs.getString("external_id"),
                rs.getString("name"),
                rs.getString("address"),
                rs.getString("metro"),
                nullableDecimal(rs, "latitude"),
                nullableDecimal(rs, "longitude")
        );
    }

    static Event event(ResultSet rs) throws SQLException {
        return new Event(
                rs.getLong("id"),
                rs.getLong("source_id"),
                rs.getString("external_id"),
                nullableLong(rs, "place_id"),
                rs.getString("title"),
                rs.getString("description"),
                nullableInt(rs, "age_limit"),
                nullableBoolean(rs, "is_free"),
                nullableDecimal(rs, "price_from"),
                rs.getString("price_text"),
                rs.getString("cover_url"),
                rs.getString("source_url"),
                rs.getString("ticket_url"),
                rs.getBoolean("published"),
                ts(rs, "hidden_at"),
                ts(rs, "imported_at")
        );
    }

    static EventSession eventSession(ResultSet rs) throws SQLException {
        return new EventSession(
                rs.getLong("id"),
                rs.getLong("event_id"),
                rs.getString("source_key"),
                ts(rs, "starts_at"),
                ts(rs, "ends_at"),
                ts(rs, "cancelled_at")
        );
    }
}
