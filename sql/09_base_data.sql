-- 09_base_data.sql
-- Рабочие данные на пустой схеме: справочники и демонстрационные учётные записи.
-- Событий здесь нет: каталог заполняется только импортом реальных данных (UC-12).
-- Синтетический 04_seed.sql с 1000 событий нужен теперь только автотестам.
-- Запускать после 01, 02, 03 и 08.

\set ON_ERROR_STOP on
SET search_path TO :app_schema, public;

BEGIN;

INSERT INTO catalog_source (id, code, name, base_url)
VALUES (1, 'kudago', 'KudaGo', 'https://kudago.com/public-api/v1.4');

INSERT INTO category (id, slug, name) VALUES
    (1, 'concert', 'Концерт'),
    (2, 'theater', 'Театр'),
    (3, 'exhibition', 'Выставка'),
    (4, 'sport', 'Спорт'),
    (5, 'lecture', 'Лекция'),
    (6, 'festival', 'Фестиваль'),
    (7, 'cinema', 'Кино'),
    (8, 'workshop', 'Мастер-класс'),
    (9, 'party', 'Вечеринка'),
    (10, 'standup', 'Стендап'),
    (11, 'walk', 'Прогулка'),
    (12, 'kids', 'Детям');

-- Пароль всех демо-учёток: Demo12345! Это вымышленные люди, настоящих пользователей в проекте нет.
INSERT INTO app_user (id, email, password_hash, role) VALUES
    (1,   'demo1@example.org',   '$2a$10$NXtUMsxcq2MgKB2KGAXTue9.5pToxqgs2mtoMH2DHStbnvJWn1642', 'USER'),
    (2,   'demo2@example.org',   '$2a$10$NXtUMsxcq2MgKB2KGAXTue9.5pToxqgs2mtoMH2DHStbnvJWn1642', 'USER'),
    (3,   'demo3@example.org',   '$2a$10$NXtUMsxcq2MgKB2KGAXTue9.5pToxqgs2mtoMH2DHStbnvJWn1642', 'USER'),
    (4,   'demo4@example.org',   '$2a$10$NXtUMsxcq2MgKB2KGAXTue9.5pToxqgs2mtoMH2DHStbnvJWn1642', 'USER'),
    (5,   'demo5@example.org',   '$2a$10$NXtUMsxcq2MgKB2KGAXTue9.5pToxqgs2mtoMH2DHStbnvJWn1642', 'USER'),
    (6,   'demo6@example.org',   '$2a$10$NXtUMsxcq2MgKB2KGAXTue9.5pToxqgs2mtoMH2DHStbnvJWn1642', 'USER'),
    (7,   'demo7@example.org',   '$2a$10$NXtUMsxcq2MgKB2KGAXTue9.5pToxqgs2mtoMH2DHStbnvJWn1642', 'USER'),
    (8,   'demo8@example.org',   '$2a$10$NXtUMsxcq2MgKB2KGAXTue9.5pToxqgs2mtoMH2DHStbnvJWn1642', 'USER'),
    (9,   'demo9@example.org',   '$2a$10$NXtUMsxcq2MgKB2KGAXTue9.5pToxqgs2mtoMH2DHStbnvJWn1642', 'USER'),
    (100, 'demo100@example.org', '$2a$10$NXtUMsxcq2MgKB2KGAXTue9.5pToxqgs2mtoMH2DHStbnvJWn1642', 'MODERATOR');

INSERT INTO user_profile (user_id, nickname, display_name, avatar_key, metro, bio) VALUES
    (1,   'nikita_spb',  'Никита',    'sticker:1', 'Василеостровская',  'Ищу компанию на кино и выставки. Люблю долгие прогулки после сеанса.'),
    (2,   'anya_spb',    'Аня',       'sticker:2', 'Приморская',        'Люблю неспешное кино и выставки. Рада новой компании без неловких знакомств.'),
    (3,   'misha_kino',  'Миша',      'sticker:3', 'Горьковская',       'Хожу на всё, где после показа можно поговорить.'),
    (4,   'lera_art',    'Лера',      'sticker:4', 'Адмиралтейская',    'Искусство, дизайн и кофе после выставки.'),
    (5,   'sasha_jazz',  'Саша',      'sticker:5', 'Площадь Восстания', 'Джаз и камерные концерты. Сажусь поближе к сцене.'),
    (6,   'dasha_walks', 'Даша',      'sticker:6', 'Невский проспект',  'Прогулки, фото и маленькие площадки.'),
    (7,   'ilya_neva',   'Илья',      'sticker:7', 'Чернышевская',      'Лекции и книги. Всегда прихожу заранее.'),
    (8,   'alina_go',    'Алина',     'sticker:8', 'Петроградская',     'Спонтанно выбираюсь куда-нибудь по выходным.'),
    (9,   'maksim_live', 'Максим',    'sticker:1', 'Московская',        'Концерты и фестивали, лучше на открытом воздухе.'),
    (100, 'moderator',   'Модератор', 'sticker:2', NULL,                NULL);

INSERT INTO user_interest (user_id, category_id) VALUES
    (1, 7), (1, 3), (1, 1), (1, 11),
    (2, 7), (2, 3), (2, 11),
    (3, 7), (3, 5), (3, 2),
    (4, 3), (4, 8), (4, 5),
    (5, 1), (5, 6), (5, 9),
    (6, 11), (6, 3), (6, 6),
    (7, 5), (7, 2), (7, 7),
    (8, 9), (8, 10), (8, 1), (8, 6),
    (9, 1), (9, 6), (9, 4),
    (100, 1), (100, 3), (100, 5);

SELECT setval(pg_get_serial_sequence('catalog_source', 'id'), (SELECT max(id) FROM catalog_source));
SELECT setval(pg_get_serial_sequence('category', 'id'), (SELECT max(id) FROM category));
SELECT setval(pg_get_serial_sequence('app_user', 'id'), (SELECT max(id) FROM app_user));

COMMIT;
