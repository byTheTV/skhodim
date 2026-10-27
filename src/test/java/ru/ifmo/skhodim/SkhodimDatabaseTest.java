package ru.ifmo.skhodim;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Перед проверками заливает на helios скрипты 91 и 01–04.
 * Сами тесты смотрят данные и правила, а не факт запуска файлов.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkhodimDatabaseTest {

    private HeliosDatabase db;

    @BeforeAll
    void deploy() throws Exception {
        db = HeliosDatabase.connect();
        db.uploadScripts();
        db.apply("91_reset_objects.sql");
        db.apply("01_schema.sql");
        db.apply("02_logic.sql");
        db.apply("03_queries_indexes.sql");
        db.apply("04_seed.sql");
    }

    @AfterAll
    void disconnect() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("У каждого пользователя есть профиль и от 3 до 5 интересов")
    void usersHaveProfilesAndInterests() {
        assertEquals(100, count("SELECT count(*) FROM app_user"));
        assertEquals(100, count("SELECT count(*) FROM user_profile"));
        assertEquals(100, count("""
                SELECT count(*) FROM app_user u
                WHERE (SELECT count(*) FROM user_interest i WHERE i.user_id = u.id) BETWEEN 3 AND 5
                """));
        assertEquals(1, count("SELECT count(*) FROM app_user WHERE role = 'MODERATOR'"));
        assertEquals("MODERATOR", db.query("SELECT role FROM app_user WHERE email = 'demo100@example.org'"));
        assertEquals(0, count("""
                SELECT count(*) FROM (
                    SELECT lower(email) FROM app_user GROUP BY lower(email) HAVING count(*) > 1
                ) duplicates
                """));
        assertEquals("user1", db.query("""
                SELECT nickname FROM user_profile p
                JOIN app_user u ON u.id = p.user_id
                WHERE u.email = 'demo1@example.org'
                """));
    }

    @Test
    @Order(2)
    @DisplayName("Каталог и связи многие-ко-многим совпадают с отчётом")
    void catalogAndManyToManyMatchTheReport() {
        assertAll(
                () -> assertEquals(8, count("SELECT count(*) FROM category")),
                () -> assertEquals(5, count("SELECT count(*) FROM tag")),
                () -> assertEquals(20, count("SELECT count(*) FROM place")),
                () -> assertEquals(1000, count("SELECT count(*) FROM event")),
                () -> assertEquals(2000, count("SELECT count(*) FROM event_session")),
                () -> assertEquals(569, count("SELECT count(*) FROM event_reaction")),
                () -> assertEquals(8, count("SELECT count(*) FROM event_intention")),
                () -> assertEquals(4, count("SELECT count(*) FROM companion_request")),
                () -> assertEquals(1, count("SELECT count(*) FROM conversation")),
                () -> assertEquals(2, count("SELECT count(*) FROM message")),
                () -> assertEquals(21, count("SELECT count(*) FROM event_feedback")),
                () -> assertEquals(4, count("SELECT count(*) FROM report")),
                () -> assertEquals(1, count("SELECT count(*) FROM import_run")),
                () -> assertTrue(count("""
                        SELECT count(*) FROM event e
                        WHERE (SELECT count(*) FROM event_category ec WHERE ec.event_id = e.id) >= 2
                        """) > 0, "у события несколько категорий"),
                () -> assertTrue(count("SELECT count(*) FROM event_tag") > 0, "события связаны с тегами"),
                () -> assertTrue(count("""
                        SELECT count(*) FROM app_user u
                        WHERE (SELECT count(DISTINCT category_id) FROM user_interest i WHERE i.user_id = u.id) >= 3
                        """) == 100, "интерес пользователя — связь многие-ко-многим")
        );
    }

    @Test
    @Order(3)
    @DisplayName("Пользователь ищет афишу, получает рекомендации и читает свой диалог")
    void userScenarioUsesCriticalFunctions() {
        assertEquals("ACCEPTED", db.query("""
                SELECT cr.status
                FROM companion_request cr
                JOIN event_session es ON es.id = cr.session_id
                WHERE es.event_id = 10
                """));
        assertEquals("PENDING", db.query("""
                SELECT cr.status
                FROM companion_request cr
                JOIN event_session es ON es.id = cr.session_id
                WHERE es.event_id = 12
                """));
        assertEquals(2, count("SELECT count(*) FROM get_messages(1, (SELECT id FROM conversation LIMIT 1))"));
        assertEquals(2, count("SELECT count(*) FROM get_participants(2, (SELECT id FROM conversation LIMIT 1))"));
        assertTrue(count("SELECT count(*) FROM search_catalog(p_query => 'концерт', p_limit => 5)") > 0);
        assertTrue(count("SELECT count(*) FROM recommend_events(1, 5)") > 0);
    }

    @Test
    @Order(4)
    @DisplayName("Некорректные действия пользователя отклоняются")
    void invalidUserActionsAreRejected() {
        assertAll(
                () -> db.script("""
                        DO $$
                        BEGIN
                            BEGIN
                                INSERT INTO app_user (email, password_hash)
                                VALUES ('DEMO1@EXAMPLE.ORG', 'x');
                                RAISE EXCEPTION 'expected failure';
                            EXCEPTION WHEN unique_violation THEN
                                NULL;
                            WHEN OTHERS THEN
                                IF SQLERRM LIKE '%expected failure%' THEN RAISE; END IF;
                            END;
                        END $$;
                        """),
                () -> db.script("""
                        DO $$
                        BEGIN
                            BEGIN
                                DELETE FROM user_interest WHERE user_id = 1;
                                SET CONSTRAINTS ALL IMMEDIATE;
                                RAISE EXCEPTION 'expected failure';
                            EXCEPTION WHEN OTHERS THEN
                                IF SQLERRM LIKE '%expected failure%' THEN RAISE; END IF;
                            END;
                        END $$;
                        """),
                () -> db.script("""
                        DO $$
                        DECLARE
                            sess bigint;
                        BEGIN
                            SELECT id INTO sess
                            FROM event_session
                            WHERE event_id = 10 AND source_key = 'sess-10-1';
                            BEGIN
                                PERFORM send_request(1, 1, sess);
                                RAISE EXCEPTION 'expected failure';
                            EXCEPTION WHEN OTHERS THEN
                                IF SQLERRM LIKE '%expected failure%' THEN RAISE; END IF;
                            END;
                        END $$;
                        """),
                () -> db.script("""
                        DO $$
                        BEGIN
                            BEGIN
                                PERFORM get_messages(50, (SELECT id FROM conversation LIMIT 1));
                                RAISE EXCEPTION 'expected failure';
                            EXCEPTION WHEN OTHERS THEN
                                IF SQLERRM LIKE '%expected failure%' THEN RAISE; END IF;
                            END;
                        END $$;
                        """)
        );
        assertEquals(100, count("SELECT count(*) FROM app_user"));
        assertEquals(100, count("""
                SELECT count(*) FROM app_user u
                WHERE (SELECT count(*) FROM user_interest i WHERE i.user_id = u.id) BETWEEN 3 AND 5
                """));
    }

    private int count(String sql) {
        return Integer.parseInt(db.query(sql));
    }
}
