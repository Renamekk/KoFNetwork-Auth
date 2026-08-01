-- =====================================================================================
--  KoFAuth — стартовые данные
--  Идемпотентность обеспечивается INSERT IGNORE по уникальным ключам: повторный
--  прогон (например, при восстановлении baseline на существующей базе) безопасен.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
--  Роли
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO roles (name, display_name, priority, color, is_default)
VALUES ('player', 'Игрок', 0, '#AAAAAA', 1),
       ('vip', 'VIP', 10, '#55FF55', 0),
       ('premium', 'Premium', 20, '#FFAA00', 0),
       ('helper', 'Хелпер', 40, '#55FFFF', 0),
       ('moderator', 'Модератор', 50, '#5555FF', 0),
       ('admin', 'Администратор', 90, '#FF5555', 0),
       ('owner', 'Владелец', 100, '#AA00AA', 0);

-- -------------------------------------------------------------------------------------
--  Права
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO permissions (node, description)
VALUES ('kofauth.login', 'Использование /login'),
       ('kofauth.register', 'Использование /register'),
       ('kofauth.changepassword', 'Смена собственного пароля'),
       ('kofauth.link.email', 'Привязка e-mail'),
       ('kofauth.link.telegram', 'Привязка Telegram'),
       ('kofauth.link.discord', 'Привязка Discord'),
       ('kofauth.totp.manage', 'Управление собственным TOTP'),
       ('kofauth.session.manage', 'Управление собственными сессиями'),

       ('kofauth.admin', 'Доступ к /auth'),
       ('kofauth.admin.reload', '/auth reload'),
       ('kofauth.admin.info', '/auth info'),
       ('kofauth.admin.player', '/auth player'),
       ('kofauth.admin.lock', '/auth lock и /auth unlock'),
       ('kofauth.admin.resetpassword', '/auth resetpassword'),
       ('kofauth.admin.forceverify', '/auth forceverify'),
       ('kofauth.admin.sessions', '/auth sessions'),
       ('kofauth.admin.devices', '/auth devices'),
       ('kofauth.admin.logs', '/auth logs'),
       ('kofauth.admin.migrate', '/auth migrate'),
       ('kofauth.admin.export', '/auth export'),
       ('kofauth.admin.import', '/auth import'),

       ('kofauth.bypass.captcha', 'Пропуск CAPTCHA'),
       ('kofauth.bypass.ratelimit', 'Пропуск rate-limit'),
       ('kofauth.bypass.antivpn', 'Пропуск проверки VPN/прокси');

-- -------------------------------------------------------------------------------------
--  Права ролей
-- -------------------------------------------------------------------------------------

-- Базовый набор для всех игроков.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         CROSS JOIN permissions p
WHERE r.name = 'player'
  AND p.node IN ('kofauth.login', 'kofauth.register', 'kofauth.changepassword',
                 'kofauth.link.email', 'kofauth.link.telegram', 'kofauth.link.discord',
                 'kofauth.totp.manage', 'kofauth.session.manage');

-- Модерация: чтение данных игроков, но без разрушительных операций.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         CROSS JOIN permissions p
WHERE r.name = 'moderator'
  AND p.node IN ('kofauth.admin', 'kofauth.admin.info', 'kofauth.admin.player',
                 'kofauth.admin.sessions', 'kofauth.admin.devices', 'kofauth.admin.logs',
                 'kofauth.admin.lock');

-- Администратор: всё, кроме миграции и импорта/экспорта дампов.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         CROSS JOIN permissions p
WHERE r.name = 'admin'
  AND p.node NOT IN ('kofauth.admin.migrate', 'kofauth.admin.import', 'kofauth.admin.export');

-- Владелец: полный доступ.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         CROSS JOIN permissions p
WHERE r.name = 'owner';

-- -------------------------------------------------------------------------------------
--  Настройки времени выполнения
--  Значения дублируют дефолты YAML. YAML — базовая конфигурация процесса,
--  settings — то, что администратор может менять на лету из панели без рестарта.
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO settings (setting_key, setting_value, value_type, description, editable)
VALUES ('auth.registration.enabled', 'true', 'BOOLEAN', 'Разрешена ли регистрация новых аккаунтов', 1),
       ('auth.login.timeout-seconds', '60', 'INT', 'Сколько секунд даётся на вход до кика', 1),
       ('auth.login.max-attempts', '5', 'INT', 'Неудачных попыток до временной блокировки', 1),
       ('auth.login.lockout-minutes', '15', 'INT', 'Длительность временной блокировки', 1),
       ('auth.session.ttl-minutes', '1440', 'INT', 'Скользящий срок жизни игровой сессии', 1),
       ('auth.session.absolute-ttl-minutes', '10080', 'INT', 'Жёсткий потолок жизни сессии', 1),
       ('auth.session.bind-to-ip', 'true', 'BOOLEAN', 'Привязывать сессию к IP', 1),

       ('captcha.enabled', 'true', 'BOOLEAN', 'Требовать CAPTCHA при первом входе', 1),
       ('captcha.type', 'GUI_GRID', 'STRING', 'BLOCK_SELECT, TEXT_INPUT, BUTTON_CLICK, GUI_GRID, MAP_IMAGE', 1),
       ('captcha.ttl-seconds', '120', 'INT', 'Срок жизни челленджа', 1),

       ('security.antibot.enabled', 'true', 'BOOLEAN', 'Защита от массовых подключений', 1),
       ('security.antibot.max-connections-per-ip', '3', 'INT', 'Одновременных подключений с одного IP', 1),
       ('security.antivpn.enabled', 'false', 'BOOLEAN', 'Блокировать VPN и прокси', 1),
       ('security.password.min-length', '8', 'INT', 'Минимальная длина пароля', 1),

       ('maintenance.enabled', 'false', 'BOOLEAN', 'Технические работы: пускать только с kofauth.admin', 1),
       ('maintenance.message', 'Сервер на техническом обслуживании', 'STRING', 'Сообщение при техработах', 1),

       ('schema.version', '2', 'INT', 'Версия схемы данных KoFAuth', 0);
