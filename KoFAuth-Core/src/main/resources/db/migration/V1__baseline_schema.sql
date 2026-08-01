-- =====================================================================================
--  KoFAuth — baseline schema
--  Этап 2. Источник истины для структуры БД: миграции Flyway в этом каталоге.
--
--  Соглашения:
--    * Движок InnoDB, кодировка utf8mb4 / utf8mb4_unicode_ci (совместимо с MySQL 8 и MariaDB).
--    * Все временные метки — DATETIME(3) в UTC. TIMESTAMP не используется из-за
--      предела 2038 года и неявной конверсии часового пояса соединения.
--    * IP-адреса — VARBINARY(16): вмещает и IPv4 (4 байта), и IPv6 (16 байт),
--      индексируется компактнее строки и корректно сравнивается.
--    * UUID — BINARY(16): 16 байт против 36 у CHAR(36), это заметно на индексе.
--    * Идентификаторы, уходящие наружу (session_id, challenge_id), — CHAR(36):
--      их нельзя связывать с внутренним автоинкрементом, чтобы не утекала мощность базы.
--    * Секреты (TOTP, OAuth-токены) хранятся в VARBINARY после AES-256-GCM.
--    * Одноразовые токены хранятся ТОЛЬКО как SHA-256 хэш: утечка дампа не даёт
--      возможности предъявить токен.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
--  users — центральная сущность аккаунта
-- -------------------------------------------------------------------------------------
CREATE TABLE users
(
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    uuid                  BINARY(16)      NOT NULL COMMENT 'UUID игрока (offline или premium)',
    username              VARCHAR(16)     NOT NULL COMMENT 'Ник в оригинальном регистре',
    lower_username        VARCHAR(16)     NOT NULL COMMENT 'Ключ поиска в нижнем регистре',

    password_hash         VARCHAR(100)    NOT NULL COMMENT 'BCrypt = 60 символов; запас на смену алгоритма',
    password_algorithm    VARCHAR(16)     NOT NULL DEFAULT 'BCRYPT',
    password_updated_at   DATETIME(3)     NULL,

    status                ENUM ('ACTIVE', 'LOCKED', 'BANNED', 'PENDING_DELETION')
                                          NOT NULL DEFAULT 'ACTIVE',
    premium               TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'Лицензионный аккаунт: пароль не спрашиваем',

    registration_ip       VARBINARY(16)   NOT NULL,
    registration_date     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    last_login_ip         VARBINARY(16)   NULL,
    last_login_at         DATETIME(3)     NULL,
    last_logout_at        DATETIME(3)     NULL,
    last_server           VARCHAR(64)     NULL,
    last_country          CHAR(2)         NULL,
    last_city             VARCHAR(64)     NULL,
    last_user_agent       VARCHAR(255)    NULL,

    failed_login_attempts INT UNSIGNED    NOT NULL DEFAULT 0,
    locked_until          DATETIME(3)     NULL COMMENT 'Временная блокировка после серии неудач',
    captcha_passed        TINYINT(1)      NOT NULL DEFAULT 0,

    two_factor_methods    SET ('TOTP', 'TELEGRAM', 'DISCORD', 'EMAIL')
                                          NOT NULL DEFAULT '' COMMENT 'Пусто = 2FA выключена',

    created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_users_uuid (uuid),
    UNIQUE KEY uk_users_lower_username (lower_username),
    KEY idx_users_status (status),
    KEY idx_users_last_login_ip (last_login_ip),
    KEY idx_users_registration_ip (registration_ip)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Аккаунты игроков KoF Network';

-- -------------------------------------------------------------------------------------
--  roles / permissions — RBAC
-- -------------------------------------------------------------------------------------
CREATE TABLE roles
(
    id           INT UNSIGNED NOT NULL AUTO_INCREMENT,
    name         VARCHAR(32)  NOT NULL COMMENT 'Машинное имя: player, moderator, admin',
    display_name VARCHAR(64)  NOT NULL,
    priority     INT          NOT NULL DEFAULT 0 COMMENT 'Больше = важнее; определяет отображаемую роль',
    color        CHAR(7)      NULL COMMENT 'HEX-цвет для сайта и Discord',
    is_default   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Выдаётся автоматически при регистрации',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_name (name),
    KEY idx_roles_priority (priority)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Роли RBAC';

CREATE TABLE permissions
(
    id          INT UNSIGNED NOT NULL AUTO_INCREMENT,
    node        VARCHAR(128) NOT NULL COMMENT 'Узел вида kofauth.admin.unlock',
    description VARCHAR(255) NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_permissions_node (node)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Права RBAC';

-- Связки many-to-many. В ТЗ явно не перечислены, но без них RBAC не нормализуется:
-- альтернатива — хранить список прав строкой, что ломает целостность и поиск.
CREATE TABLE role_permissions
(
    role_id       INT UNSIGNED NOT NULL,
    permission_id INT UNSIGNED NOT NULL,
    granted_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (role_id, permission_id),
    KEY idx_role_permissions_permission (permission_id),
    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE user_roles
(
    account_id BIGINT UNSIGNED NOT NULL,
    role_id    INT UNSIGNED    NOT NULL,
    granted_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    granted_by BIGINT UNSIGNED NULL,
    expires_at DATETIME(3)     NULL COMMENT 'NULL = бессрочно (для временных привилегий)',

    PRIMARY KEY (account_id, role_id),
    KEY idx_user_roles_role (role_id),
    KEY idx_user_roles_expires (expires_at),
    CONSTRAINT fk_user_roles_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_granted_by
        FOREIGN KEY (granted_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- -------------------------------------------------------------------------------------
--  devices — устройства, с которых заходил аккаунт
-- -------------------------------------------------------------------------------------
CREATE TABLE devices
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id      BIGINT UNSIGNED NOT NULL,

    fingerprint     CHAR(64)        NOT NULL COMMENT 'SHA-256 от (платформа + клиент + ОС + бренд)',
    display_name    VARCHAR(64)     NULL COMMENT 'Имя, заданное игроком в личном кабинете',
    platform        ENUM ('MINECRAFT', 'WEB', 'TELEGRAM', 'DISCORD', 'API', 'UNKNOWN')
                                    NOT NULL DEFAULT 'UNKNOWN',
    operating_system VARCHAR(48)    NULL,
    browser         VARCHAR(48)     NULL,
    client_brand    VARCHAR(48)     NULL COMMENT 'Для Minecraft: vanilla, lunarclient, fabric...',
    protocol_version INT            NULL,

    first_seen_ip   VARBINARY(16)   NOT NULL,
    last_seen_ip    VARBINARY(16)   NOT NULL,
    first_seen_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_seen_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    trusted         TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'Доверенное: не требует 2FA',
    trusted_at      DATETIME(3)     NULL,
    blocked         TINYINT(1)      NOT NULL DEFAULT 0,
    blocked_at      DATETIME(3)     NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_devices_account_fingerprint (account_id, fingerprint),
    KEY idx_devices_account (account_id),
    KEY idx_devices_last_seen (last_seen_at),
    CONSTRAINT fk_devices_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Устройства аккаунта';

-- -------------------------------------------------------------------------------------
--  sessions — активные сессии (MySQL = долговременная копия; горячее состояние в Redis)
-- -------------------------------------------------------------------------------------
CREATE TABLE sessions
(
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id          BIGINT UNSIGNED NOT NULL,
    device_id           BIGINT UNSIGNED NULL,

    public_id           CHAR(36)        NOT NULL COMMENT 'Внешний непредсказуемый идентификатор',
    type                ENUM ('GAME', 'WEB', 'TELEGRAM', 'DISCORD', 'API')
                                        NOT NULL DEFAULT 'GAME',

    ip                  VARBINARY(16)   NOT NULL,
    user_agent          VARCHAR(255)    NULL,
    country             CHAR(2)         NULL,
    city                VARCHAR(64)     NULL,
    server              VARCHAR(64)     NULL COMMENT 'Текущий сервер сети для GAME-сессий',

    issued_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_seen_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at          DATETIME(3)     NOT NULL COMMENT 'Скользящий срок: продлевается активностью',
    absolute_expires_at DATETIME(3)     NOT NULL COMMENT 'Жёсткий потолок жизни сессии',

    revoked             TINYINT(1)      NOT NULL DEFAULT 0,
    revoked_at          DATETIME(3)     NULL,
    revoked_reason      VARCHAR(64)     NULL COMMENT 'LOGOUT, PASSWORD_CHANGED, ADMIN, TIMEOUT, IP_MISMATCH',

    PRIMARY KEY (id),
    UNIQUE KEY uk_sessions_public_id (public_id),
    KEY idx_sessions_account_active (account_id, revoked, expires_at),
    KEY idx_sessions_expires (expires_at),
    KEY idx_sessions_device (device_id),
    CONSTRAINT fk_sessions_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_sessions_device
        FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Сессии всех платформ';

-- -------------------------------------------------------------------------------------
--  tokens — одноразовые и долгоживущие токены
--  Здесь же живут резервные коды TOTP (type = TOTP_RECOVERY): у них та же семантика
--  «хэш + одноразовое использование», отдельная таблица дублировала бы логику.
-- -------------------------------------------------------------------------------------
CREATE TABLE tokens
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id      BIGINT UNSIGNED NULL COMMENT 'NULL для токенов привязки до выбора аккаунта',
    session_id      BIGINT UNSIGNED NULL,

    token_hash      CHAR(64)        NOT NULL COMMENT 'SHA-256 от значения токена; сырое значение не хранится',
    type            ENUM ('REFRESH', 'EMAIL_VERIFY', 'PASSWORD_RESET', 'TELEGRAM_LINK',
                          'DISCORD_LINK', 'LOGIN_APPROVAL', 'TOTP_RECOVERY', 'API_KEY')
                                    NOT NULL,

    parent_token_id BIGINT UNSIGNED NULL COMMENT 'Цепочка ротации refresh-токенов для детекта повторного использования',

    issued_ip       VARBINARY(16)   NULL,
    issued_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at      DATETIME(3)     NOT NULL,

    used            TINYINT(1)      NOT NULL DEFAULT 0,
    used_at         DATETIME(3)     NULL,
    used_ip         VARBINARY(16)   NULL,

    revoked         TINYINT(1)      NOT NULL DEFAULT 0,
    revoked_at      DATETIME(3)     NULL,

    metadata        JSON            NULL COMMENT 'Контекст: телеграм-чат, целевой e-mail и т.п.',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tokens_hash (token_hash),
    KEY idx_tokens_account_type (account_id, type, used, revoked),
    KEY idx_tokens_expires (expires_at),
    KEY idx_tokens_session (session_id),
    KEY idx_tokens_parent (parent_token_id),
    CONSTRAINT fk_tokens_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_tokens_session
        FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_tokens_parent
        FOREIGN KEY (parent_token_id) REFERENCES tokens (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Токены: refresh, подтверждения, привязки, резервные коды';

-- -------------------------------------------------------------------------------------
--  emails — привязанные почтовые адреса
-- -------------------------------------------------------------------------------------
CREATE TABLE emails
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id        BIGINT UNSIGNED NOT NULL,

    email             VARCHAR(255)    NOT NULL COMMENT 'Адрес в исходном виде',
    email_lower       VARCHAR(255)    NOT NULL COMMENT 'Нормализованный адрес для поиска',

    verified          TINYINT(1)      NOT NULL DEFAULT 0,
    verified_at       DATETIME(3)     NULL,
    is_primary        TINYINT(1)      NOT NULL DEFAULT 1,

    notify_login      TINYINT(1)      NOT NULL DEFAULT 1,
    notify_security   TINYINT(1)      NOT NULL DEFAULT 1,
    notify_newsletter TINYINT(1)      NOT NULL DEFAULT 0,

    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_emails_account_address (account_id, email_lower),
    KEY idx_emails_lower (email_lower),
    KEY idx_emails_account_primary (account_id, is_primary),
    CONSTRAINT fk_emails_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Привязки e-mail';

-- -------------------------------------------------------------------------------------
--  telegram — привязка Telegram
-- -------------------------------------------------------------------------------------
CREATE TABLE telegram
(
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id             BIGINT UNSIGNED NOT NULL,

    telegram_id            BIGINT          NOT NULL COMMENT 'Telegram user id',
    chat_id                BIGINT          NOT NULL COMMENT 'Личный чат для уведомлений',
    username               VARCHAR(32)     NULL,
    first_name             VARCHAR(64)     NULL,
    last_name              VARCHAR(64)     NULL,
    language_code          VARCHAR(8)      NULL,

    notifications_enabled  TINYINT(1)      NOT NULL DEFAULT 1,
    login_approval_enabled TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'Подтверждение входа кнопкой',

    linked_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_telegram_account (account_id),
    UNIQUE KEY uk_telegram_id (telegram_id),
    CONSTRAINT fk_telegram_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Привязки Telegram';

-- -------------------------------------------------------------------------------------
--  discord — привязка Discord + OAuth2
-- -------------------------------------------------------------------------------------
CREATE TABLE discord
(
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id             BIGINT UNSIGNED NOT NULL,

    discord_id             BIGINT UNSIGNED NOT NULL COMMENT 'Snowflake',
    username               VARCHAR(32)     NULL,
    global_name            VARCHAR(32)     NULL,
    discriminator          CHAR(4)         NULL COMMENT 'Legacy-дискриминатор, обычно NULL',
    avatar_hash            VARCHAR(64)     NULL,

    notifications_enabled  TINYINT(1)      NOT NULL DEFAULT 1,
    login_approval_enabled TINYINT(1)      NOT NULL DEFAULT 0,

    oauth_access_token     VARBINARY(512)  NULL COMMENT 'AES-256-GCM',
    oauth_refresh_token    VARBINARY(512)  NULL COMMENT 'AES-256-GCM',
    oauth_expires_at       DATETIME(3)     NULL,
    oauth_scopes           VARCHAR(255)    NULL,

    linked_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_discord_account (account_id),
    UNIQUE KEY uk_discord_id (discord_id),
    CONSTRAINT fk_discord_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Привязки Discord';

-- -------------------------------------------------------------------------------------
--  totp — Google Authenticator
-- -------------------------------------------------------------------------------------
CREATE TABLE totp
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id        BIGINT UNSIGNED NOT NULL,

    secret            VARBINARY(255)  NOT NULL COMMENT 'Base32-секрет, зашифрованный AES-256-GCM',
    algorithm         ENUM ('SHA1', 'SHA256', 'SHA512') NOT NULL DEFAULT 'SHA1',
    digits            TINYINT UNSIGNED NOT NULL DEFAULT 6,
    period_seconds    SMALLINT UNSIGNED NOT NULL DEFAULT 30,

    enabled           TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'Включается только после подтверждения кодом',
    confirmed_at      DATETIME(3)     NULL,
    last_used_counter BIGINT          NULL COMMENT 'Защита от повторного использования того же кода',

    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_totp_account (account_id),
    CONSTRAINT fk_totp_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'TOTP-секреты (Google Authenticator)';

-- -------------------------------------------------------------------------------------
--  captcha — выданные и решённые челленджи
-- -------------------------------------------------------------------------------------
CREATE TABLE captcha
(
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id           BIGINT UNSIGNED NULL COMMENT 'NULL, если капча выдана до регистрации',
    player_uuid          BINARY(16)      NULL COMMENT 'Для незарегистрированных игроков',

    challenge_id         CHAR(36)        NOT NULL,
    type                 ENUM ('BLOCK_SELECT', 'TEXT_INPUT', 'BUTTON_CLICK', 'GUI_GRID', 'MAP_IMAGE')
                                         NOT NULL,
    expected_answer_hash CHAR(64)        NOT NULL COMMENT 'SHA-256 ответа: сам ответ в БД не лежит',

    attempts             SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    max_attempts         SMALLINT UNSIGNED NOT NULL DEFAULT 3,

    ip                   VARBINARY(16)   NOT NULL,
    status               ENUM ('PENDING', 'PASSED', 'FAILED', 'EXPIRED') NOT NULL DEFAULT 'PENDING',

    issued_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at           DATETIME(3)     NOT NULL,
    resolved_at          DATETIME(3)     NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_captcha_challenge (challenge_id),
    KEY idx_captcha_account_status (account_id, status),
    KEY idx_captcha_uuid (player_uuid),
    KEY idx_captcha_expires (expires_at),
    CONSTRAINT fk_captcha_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'CAPTCHA-челленджи';

-- -------------------------------------------------------------------------------------
--  login_history — история попыток входа (успешных и неудачных)
-- -------------------------------------------------------------------------------------
CREATE TABLE login_history
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id        BIGINT UNSIGNED NULL COMMENT 'NULL, если ник не существует',
    device_id         BIGINT UNSIGNED NULL,

    username_attempt  VARCHAR(16)     NOT NULL COMMENT 'Что именно ввёл клиент',
    success           TINYINT(1)      NOT NULL,
    result            VARCHAR(32)     NOT NULL COMMENT 'LoginResultType: SUCCESS, BAD_PASSWORD, LOCKED, ...',

    ip                VARBINARY(16)   NOT NULL,
    country           CHAR(2)         NULL,
    city              VARCHAR(64)     NULL,
    isp               VARCHAR(96)     NULL,
    user_agent        VARCHAR(255)    NULL,

    source            ENUM ('MINECRAFT', 'WEB', 'TELEGRAM', 'DISCORD', 'API') NOT NULL DEFAULT 'MINECRAFT',
    server            VARCHAR(64)     NULL,
    protocol_version  INT             NULL,
    two_factor_method VARCHAR(16)     NULL COMMENT 'Каким вторым фактором подтверждён вход',

    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_login_history_account_time (account_id, created_at),
    KEY idx_login_history_ip_time (ip, created_at),
    KEY idx_login_history_success_time (success, created_at),
    CONSTRAINT fk_login_history_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_login_history_device
        FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'История входов';

-- -------------------------------------------------------------------------------------
--  security_logs — сквозной аудит всех значимых действий
--  event_type — VARCHAR, а не ENUM: новые типы событий не должны требовать миграции.
-- -------------------------------------------------------------------------------------
CREATE TABLE security_logs
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id BIGINT UNSIGNED NULL,
    actor_id   BIGINT UNSIGNED NULL COMMENT 'Кто выполнил действие, если это админ',

    event_type VARCHAR(48)     NOT NULL,
    severity   ENUM ('INFO', 'WARNING', 'CRITICAL') NOT NULL DEFAULT 'INFO',
    source     ENUM ('MINECRAFT', 'WEB', 'TELEGRAM', 'DISCORD', 'API', 'SYSTEM') NOT NULL DEFAULT 'SYSTEM',

    ip         VARBINARY(16)   NULL,
    country    CHAR(2)         NULL,
    city       VARCHAR(64)     NULL,
    user_agent VARCHAR(255)    NULL,

    message    VARCHAR(512)    NULL,
    metadata   JSON            NULL,

    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_security_logs_account_time (account_id, created_at),
    KEY idx_security_logs_type_time (event_type, created_at),
    KEY idx_security_logs_severity_time (severity, created_at),
    KEY idx_security_logs_ip_time (ip, created_at),
    CONSTRAINT fk_security_logs_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_security_logs_actor
        FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Аудит безопасности';

-- -------------------------------------------------------------------------------------
--  servers — реестр серверов сети
-- -------------------------------------------------------------------------------------
CREATE TABLE servers
(
    id                INT UNSIGNED     NOT NULL AUTO_INCREMENT,
    name              VARCHAR(48)      NOT NULL COMMENT 'Имя как в velocity.toml',
    type              ENUM ('PROXY', 'LIMBO', 'LOBBY', 'GAME') NOT NULL,
    address           VARCHAR(255)     NOT NULL,
    port              SMALLINT UNSIGNED NOT NULL,
    motd              VARCHAR(255)     NULL,

    online            TINYINT(1)       NOT NULL DEFAULT 0,
    player_count      INT UNSIGNED     NOT NULL DEFAULT 0,
    max_players       INT UNSIGNED     NOT NULL DEFAULT 0,
    priority          INT              NOT NULL DEFAULT 0 COMMENT 'Порядок выбора при балансировке',

    last_heartbeat_at DATETIME(3)      NULL,
    registered_at     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_servers_name (name),
    KEY idx_servers_type_online (type, online, priority)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Серверы сети KoF';

-- -------------------------------------------------------------------------------------
--  settings — настройки времени выполнения, изменяемые без правки YAML
-- -------------------------------------------------------------------------------------
CREATE TABLE settings
(
    id            INT UNSIGNED NOT NULL AUTO_INCREMENT,
    setting_key   VARCHAR(96)  NOT NULL COMMENT 'key — зарезервированное слово, отсюда префикс',
    setting_value TEXT         NULL,
    value_type    ENUM ('STRING', 'INT', 'LONG', 'DOUBLE', 'BOOLEAN', 'JSON') NOT NULL DEFAULT 'STRING',
    description   VARCHAR(255) NULL,
    editable      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0 = только для чтения из панели',

    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by    BIGINT UNSIGNED NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_settings_key (setting_key),
    CONSTRAINT fk_settings_updated_by
        FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Динамические настройки';
