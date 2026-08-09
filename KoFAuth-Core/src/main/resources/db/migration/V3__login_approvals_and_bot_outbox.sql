-- =============================================================================
--  KoFAuth V3 — подтверждение входа и очередь сообщений ботам
-- =============================================================================
--
--  Две таблицы решают две разные задачи, которые прежде решались одним общим
--  токеном LOGIN_APPROVAL и подпиской ботов на Redis Pub/Sub.
--
--  login_approvals заменяет предъявительский токен. Токен ни к чему не был
--  привязан: его выпускала команда бота вообще без попытки входа, предъявить
--  его мог кто угодно, а погашение создавало отдельную сессию мессенджера
--  вместо завершения того входа, ради которого он выпускался. Здесь связь
--  задана колонками: аккаунт, игрок, попытка, платформа и получатель.
--
--  bot_outbox заменяет Pub/Sub. Канал не помнит сообщений, поэтому перезапуск
--  бота терял все запросы подтверждения, пришедшие за эти секунды; кроме того,
--  доступ к каналу давался мастер-паролем Redis, то есть правом переписывать
--  любые ключи системы. Очередь в MySQL переживает перезапуск, а ботам для
--  чтения хватает ключа /api/bot.
-- =============================================================================

CREATE TABLE login_approvals
(
    id           BIGINT UNSIGNED               NOT NULL AUTO_INCREMENT,

    public_id    CHAR(32)                      NOT NULL COMMENT 'Едет в callback_data кнопки; сам по себе ничего не открывает',
    account_id   BIGINT UNSIGNED               NOT NULL,
    username     VARCHAR(16)                   NOT NULL COMMENT 'Копия ника для текста сообщения без запроса к users',
    player_uuid  BINARY(16)                    NULL COMMENT 'NULL для входов не из игры',

    attempt_id   CHAR(36)                      NOT NULL COMMENT 'Какая именно попытка входа ждёт решения',

    request_source   ENUM ('MINECRAFT', 'WEB', 'TELEGRAM', 'DISCORD', 'API', 'SYSTEM') NOT NULL,
    request_platform ENUM ('MINECRAFT', 'WEB', 'TELEGRAM', 'DISCORD', 'API', 'UNKNOWN') NOT NULL,
    user_agent       VARCHAR(255)              NULL,
    device_fingerprint CHAR(64)                NULL,

    platform     ENUM ('TELEGRAM', 'DISCORD')  NOT NULL,
    recipient_id BIGINT                        NOT NULL COMMENT 'Идентификатор владельца в мессенджере: только он вправе нажать',
    chat_id      BIGINT                        NOT NULL DEFAULT 0,

    request_ip   VARBINARY(16)                 NULL,
    server       VARCHAR(64)                   NULL,
    country      VARCHAR(64)                   NULL,
    city         VARCHAR(128)                  NULL,

    status       ENUM ('PENDING', 'APPROVED', 'DENIED', 'EXPIRED')
                                               NOT NULL DEFAULT 'PENDING',

    issued_at    DATETIME(3)                   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at   DATETIME(3)                   NOT NULL,
    decided_at   DATETIME(3)                   NULL,
    decided_by   BIGINT                        NULL COMMENT 'Кто нажал; для аудита',

    browser_proof_hash CHAR(64)                NULL COMMENT 'SHA-256 proof, known only to the browser tab',
    session_id   BIGINT UNSIGNED               NULL COMMENT 'WEB/GAME сессия исходной попытки',
    exchange_consumed_at DATETIME(3)           NULL COMMENT 'Однократная выдача WEB-сессии вкладке',

    PRIMARY KEY (id),
    UNIQUE KEY uk_login_approvals_public_id (public_id),
    UNIQUE KEY uk_login_approvals_attempt_id (attempt_id),
    -- Атомарное решение ищет строку по public_id со статусом PENDING; поиск
    -- ожидающих запросов аккаунта нужен, чтобы гасить их при новой попытке.
    KEY idx_login_approvals_account_status (account_id, status, expires_at),
    KEY idx_login_approvals_expires (status, expires_at),
    CONSTRAINT fk_login_approvals_account
        FOREIGN KEY (account_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Одноразовые подтверждения входа кнопкой в мессенджере';


CREATE TABLE bot_outbox
(
    id           BIGINT UNSIGNED              NOT NULL AUTO_INCREMENT,

    platform     ENUM ('TELEGRAM', 'DISCORD') NOT NULL,
    recipient_id BIGINT                       NOT NULL,
    chat_id      BIGINT                       NOT NULL DEFAULT 0,

    kind         ENUM ('LOGIN_APPROVAL', 'LOGIN_APPROVAL_RESOLVED',
                       'LOGIN_NOTICE', 'SECURITY_NOTICE')
                                              NOT NULL,
    payload      JSON                         NOT NULL COMMENT 'Скалярные поля сообщения; секретов здесь нет по построению',

    created_at   DATETIME(3)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at   DATETIME(3)                  NOT NULL COMMENT 'Просроченное сообщение показывать бессмысленно',

    PRIMARY KEY (id),
    -- Бот читает «всё, что больше курсора», по одной платформе за раз.
    KEY idx_bot_outbox_platform_id (platform, id),
    KEY idx_bot_outbox_expires (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Долговечная очередь сообщений ботам взамен Pub/Sub';


-- Курсор доставки живёт на сервере: контейнер бота не имеет состояния и после
-- перезапуска не помнил бы, до какого места дочитал.
CREATE TABLE bot_outbox_cursor
(
    platform   ENUM ('TELEGRAM', 'DISCORD') NOT NULL,
    position   BIGINT UNSIGNED              NOT NULL DEFAULT 0,
    updated_at DATETIME(3)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (platform)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'До какого сообщения бот каждой платформы дочитал';

INSERT INTO bot_outbox_cursor (platform, position)
VALUES ('TELEGRAM', 0),
       ('DISCORD', 0);
