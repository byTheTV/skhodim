# -*- coding: utf-8 -*-
"""
Генератор отчёта по Этапу 3 курсовой работы ИС «Сходим» (Университет ИТМО).
Запуск: D:\\_projs\\word\\.venv\\Scripts\\python.exe docs\\generate_report.py
Результат: Skhodim_Etap_3_Report.docx в корне проекта.
"""
import os
import docx
from docx.shared import Pt, Inches
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_PATH = os.path.join(ROOT, "Skhodim_Etap_3_Report.docx")
ARCH_PNG = os.path.join(ROOT, "docs", "architecture.png")

doc = docx.Document()
style = doc.styles["Normal"]
style.font.name = "Times New Roman"
style.font.size = Pt(12)


def title_page():
    lines = [
        "Университет ИТМО",
        "Факультет программной инженерии и компьютерной техники",
        "Курсовая работа",
        "Информационные системы",
        "Этап 3",
        "Информационная система «Сходим»",
        "Уровень хранения и уровень бизнес-логики",
        "Выполнили",
        "Студенты группы P3319",
        "Целиков Д.А.",
        "Тарасов В.П.",
        "Проверила",
        "Лисицина В.В.",
    ]
    for line in lines:
        p = doc.add_paragraph(line)
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    for _ in range(10):
        doc.add_paragraph()
    p = doc.add_paragraph("Санкт-Петербург")
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p = doc.add_paragraph("2026 г.")
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    doc.add_page_break()


def h1(text):
    doc.add_heading(text, level=1)


def h2(text):
    doc.add_heading(text, level=2)


def para(text):
    doc.add_paragraph(text)


def table(headers, rows):
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr = t.rows[0].cells
    for i, hd in enumerate(headers):
        hdr[i].text = hd
        for p in hdr[i].paragraphs:
            for r in p.runs:
                r.bold = True
    for row in rows:
        cells = t.add_row().cells
        for i, v in enumerate(row):
            cells[i].text = str(v)
    doc.add_paragraph()
    return t


def code_block(text):
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.font.name = "Consolas"
    run.font.size = Pt(10)


title_page()

h1("1 Назначение этапа и связь с этапами 1-2")
para(
    "Этап 3 переводит прецеденты UC-01..UC-16 и функциональные требования FR-1..FR-22, "
    "сформулированные на этапе 1, а также реляционную схему и pl/pgsql-подпрограммы из "
    "22 таблиц, разработанные на этапе 2 (sql/01_schema.sql .. sql/04_seed.sql), в работающее "
    "серверное приложение. Уровень хранения и уровень бизнес-логики реализованы на Spring Boot "
    "(Java 17): выбор согласован с руководителем как один из допустимых backend-стеков курсовой "
    "работы (Spring MVC-семейство)."
)
para(
    "Ключевое требование отчёта этапа 2 выполнено буквально: критические запросы и изменения "
    "данных (set_reaction, set_intention, send_request, resolve_request, get_messages, "
    "get_participants, search_catalog, recommend_events, expire_requests) вызываются из уровня "
    "хранения приложения через Spring JDBC (JdbcTemplate) как вызовы функций/процедур БД, без "
    "дублирования их логики SQL-запросами на уровне приложения."
)

h1("2 Диаграмма классов общей архитектуры")
para(
    "Диаграмма (docs/architecture.puml, собрана PlantUML) показывает четыре слоя: будущий "
    "уровень представления (этап 4), уровень бизнес-логики (service), уровень хранения "
    "(repository, JdbcTemplate) и доменную модель (record-классы, по одному на каждую из 22 "
    "таблиц схемы, плюс вспомогательные DTO результатов функций БД)."
)
if os.path.exists(ARCH_PNG):
    doc.add_picture(ARCH_PNG, width=Inches(6.3))
    cap = doc.add_paragraph("Рисунок 1 - Диаграмма классов общей архитектуры (Этап 3)")
    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER

h2("2.1 Пакеты и ответственность")
table(
    ["Пакет", "Назначение"],
    [
        ["domain", "22 record-класса сущностей БД этапа 2 плюс DTO результатов функций "
                   "(CatalogEntry, RecommendedEvent, ConversationParticipant)."],
        ["repository", "JdbcTemplate-репозитории: обычный DML для справочных операций и "
                        "обязательный вызов pl/pgsql-функций/процедур для критических операций."],
        ["service", "Бизнес-правила уровня приложения (валидация формы ввода, проверка "
                     "«действующий пользователь - участник операции»), перевод ошибок БД "
                     "в понятные исключения."],
        ["scheduler", "RequestExpirationScheduler вызывает expire_requests() по расписанию, "
                       "так как БД не может сработать по времени сама по себе (раздел 5.3 "
                       "отчёта этапа 2)."],
        ["exception", "BusinessRuleException и NotFoundException - единый словарь ошибок "
                       "уровня бизнес-логики для будущего уровня представления (этап 4)."],
    ],
)

h1("3 Уровень хранения")
para(
    "Уровень хранения реализован пакетом ru.ifmo.skhodim.repository. Подключение к PostgreSQL "
    "настраивается профилями Spring (application-local.yml - отдельная учебная БД "
    "skhodim_course, application-helios.yml - личная схема в базе studs на сервере helios). "
    "Используется Spring JDBC (starter-jdbc) без ORM: каждая колонка результата явно "
    "читается RowMapper-лямбдами (RowMappers, MoreRowMappers), что соответствует требованию "
    "«без дублирования SQL-логики» - весь нетривиальный SQL остаётся в sql/02_logic.sql и "
    "sql/03_queries_indexes.sql."
)

h2("3.1 Репозитории и подпрограммы БД этапа 2")
table(
    ["Репозиторий", "Подпрограммы/таблицы БД этапа 2", "Прецеденты"],
    [
        ["UserRepository", "app_user, user_profile, user_interest (одна транзакция - "
                            "отложенный constraint-триггер проверяет 3-5 интересов при COMMIT)",
         "UC-01, UC-14"],
        ["CatalogRepository", "search_catalog(...), recommend_events(...), event, "
                               "event_session, place, category, tag", "UC-02..UC-05, UC-08..UC-10"],
        ["ReactionIntentionRepository", "set_reaction(...), set_intention(...), "
                                         "user_has_active_intention(...)", "UC-05, UC-06, UC-08"],
        ["CompanionRepository", "send_request(...), resolve_request(...), get_participants(...), "
                                 "get_messages(...), expire_requests()", "UC-06, UC-07, UC-15"],
        ["FeedbackRepository", "event_feedback (INSERT проверяется триггером trg_review_check)",
         "UC-11"],
        ["ModerationRepository", "report (частичный уникальный индекс uq_report_open)",
         "UC-12, UC-16"],
        ["ImportRepository", "import_run, import_error (частичный уникальный индекс "
                              "uq_import_running)", "UC-13, UC-16"],
    ],
)
para(
    "Пример вызова критической функции (CatalogRepository.searchCatalog) показывает принцип, "
    "общий для всего уровня хранения: Java-код не формирует условия WHERE каталога заново, а "
    "лишь передаёт параметры в уже проверенную на этапе 2 функцию search_catalog:"
)
code_block(
    'jdbc.query(\n'
    '    "SELECT * FROM search_catalog(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",\n'
    '    (rs, n) -> new CatalogEntry(...),\n'
    '    query, categoryArray, latMin, latMax, lonMin, lonMax, from, to, limit, offset);'
)

h1("4 Уровень бизнес-логики")
para(
    "Пакет ru.ifmo.skhodim.service реализует сценарии первого этапа поверх репозиториев. "
    "Сервисы не дублируют проверки, уже выполняемые триггерами и функциями БД (например, "
    "совпадение сторон диалога, запрет писать после блокировки, правило окончания сеанса для "
    "отзыва), а лишь: (1) отсекают заведомо некорректный ввод до обращения к БД, (2) проверяют, "
    "что действующий пользователь имеет право на операцию (например, что отвечает на заявку "
    "именно получатель), и (3) переводят исключения PostgreSQL в предсказуемые "
    "BusinessRuleException/NotFoundException для будущего уровня представления."
)

h2("4.1 Сервисы и покрываемые прецеденты")
table(
    ["Сервис", "Ключевые операции", "Прецеденты"],
    [
        ["UserService", "register, getProfile, ban/unban", "UC-01, UC-14, UC-16"],
        ["CatalogService", "search, recommend, hide, cancelSession",
         "UC-02..UC-05, UC-08..UC-10, UC-12"],
        ["ReactionService", "react (LIKE/SKIP), setIntention, favorites", "UC-05, UC-06, UC-08"],
        ["CompanionService", "sendRequest, resolveRequest, sendMessage, notifications",
         "UC-06, UC-07, UC-15"],
        ["FeedbackService", "addComment, addReview, hide, delete", "UC-11"],
        ["ModerationService", "fileReport, queue, resolve", "UC-12, UC-16"],
        ["ImportService", "startRun, reportError, finish", "UC-13, UC-16"],
    ],
)

h2("4.2 Обработка ошибок и границы ответственности")
para(
    "Как и зафиксировано в отчёте этапа 2 (раздел 5.5 «Граница ответственности сервера»), "
    "функции и процедуры используют SECURITY INVOKER, а параметр действующего пользователя "
    "(p_user) сам по себе не является доказательством личности: клиент не подключается к БД "
    "напрямую. Эту проверку выполняет уровень бизнес-логики: например, "
    "CompanionService.resolveRequest отклоняет вызов, если actorId не совпадает с "
    "receiverId заявки, до обращения к функции resolve_request."
)
para(
    "Планировщик RequestExpirationScheduler компенсирует отсутствие у PostgreSQL "
    "собственного fixed-delay триггера по времени: процедура expire_requests() вызывается "
    "регулярно (app.request-expiration.fixed-delay-ms, по умолчанию 60000 мс) перед чтением "
    "очереди заявок, переводя устаревшие PENDING-заявки в EXPIRED."
)

h1("5 Тестирование")
para(
    "Уровень хранения и бизнес-логики проверен интеграционными тестами "
    "SkhodimServiceIntegrationTest (JUnit 5 + Spring Boot Test) поверх реальной локальной "
    "PostgreSQL (профиль test, схема из sql/01..04). Каждый тест выполняется в собственной "
    "транзакции с автоматическим откатом (@Transactional), что не искажает объёмы учебного "
    "набора из раздела 9 отчёта этапа 2."
)
table(
    ["Тест", "Что проверяет"],
    [
        ["userServiceReadsSeededAccount", "Чтение учётной записи и профиля из посева, "
                                           "диапазон 3-5 интересов"],
        ["registrationRejectsInvalidInterestCount", "UserService отклоняет регистрацию "
                                                      "с 2 категориями (правило FR-2)"],
        ["registrationRejectsDuplicateEmail", "Уникальность email без учёта регистра "
                                               "(uq_app_user_email_lower)"],
        ["catalogSearchAndRecommendationsUseDatabaseFunctions",
         "search_catalog и recommend_events вызываются и возвращают непустой результат"],
        ["hidingEventMakesItInvisibleViaTrigger", "Скрытое событие не отдаётся как видимое"],
        ["reactionServiceRejectsPublicSkip", "Бизнес-правило: SKIP не может быть публичным"],
        ["likeIsIdempotentAndVisibleInFavorites", "set_reaction идемпотентен (UPSERT), "
                                                   "лайк попадает в избранное"],
        ["sendRequestFailsWithoutActiveIntention", "Заявка невозможна без активного "
                                                     "намерения (триггер БД)"],
        ["fullCompanionFlowCreatesConversationAndMessage",
         "Полный путь UC-06/07: намерение -> заявка -> принятие -> диалог -> сообщение "
         "-> уведомление"],
        ["moderationRejectsDuplicateOpenReport", "Повторная открытая жалоба отклоняется "
                                                   "(uq_report_open)"],
        ["feedbackRejectsOutOfRangeRating", "Оценка вне диапазона 1-5 отклоняется сервисом"],
    ],
)
para("Все 11 тестов проходят успешно (mvn test -Dtest=SkhodimServiceIntegrationTest).")

h1("6 Развёртывание и конфигурация")
para(
    "Приложение собирается как исполняемый jar плагином spring-boot-maven-plugin "
    "(mvn package). Профиль выбирается флагом --spring.profiles.active:"
)
table(
    ["Профиль", "Назначение", "Переменные окружения"],
    [
        ["local", "Отдельная учебная БД skhodim_course на localhost (см. sql/README.md)",
         "SKHODIM_DB_PASSWORD"],
        ["helios", "Личная схема в базе studs на сервере helios, подключение к хосту pg",
         "SKHODIM_SCHEMA, SKHODIM_DB_PASSWORD"],
        ["test", "Интеграционные тесты сервисного слоя поверх локальной БД",
         "SKHODIM_DB_PASSWORD (по умолчанию postgres)"],
    ],
)
para(
    "Секреты (пароль, имя схемы) не коммитятся: передаются переменными окружения или "
    "src/test/resources/helios.local.properties, который исключён из репозитория (.gitignore), "
    "как и на этапе 2."
)

h1("7 Заключение")
para(
    "На этапе 3 разработан уровень хранения (Spring JDBC-репозитории, строго использующие "
    "pl/pgsql-функции и процедуры из sql/02_logic.sql и sql/03_queries_indexes.sql) и уровень "
    "бизнес-логики (сервисы, планировщик истечения заявок, единый словарь исключений) для всех "
    "прецедентов UC-01..UC-16, описанных на этапе 1. Построена диаграмма классов общей "
    "архитектуры. Корректность уровней хранения и бизнес-логики подтверждена 11 интеграционными "
    "тестами на реальной PostgreSQL. Следующий шаг - уровень представления (этап 4) поверх "
    "готовых сервисов."
)

doc.save(OUT_PATH)
print("Saved:", OUT_PATH)
