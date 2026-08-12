package net.kofnetwork.auth.api.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запись аудита.
 *
 * <p>Главное здесь — исполнитель. Колонка {@code security_logs.actor_id} ссылается
 * на {@code users.id}, поэтому в неё нельзя класть ничего, кроме настоящего
 * идентификатора либо {@code NULL}. Вызывающие обозначают отсутствие аккаунта
 * нулём — консоль прокси администратор, но строки в {@code users} у неё нет, — и
 * ноль обязан превращаться в {@code NULL} здесь, а не отвергаться базой.
 *
 * <p>Что было: ноль уезжал в колонку как есть, вставку отвергал внешний ключ
 * {@code fk_security_logs_actor}, а поскольку записи уходят пачкой — вместе с
 * отвергнутой терялись и соседние, к действию администратора отношения не имевшие.
 */
class SecurityLogEntryTest {

    @Test
    void исполнитель_без_аккаунта_записывается_как_отсутствующий() {
        SecurityLogEntry entry = SecurityLogEntry.byAdmin(42L, 0L,
                SecurityEventType.ACCOUNT_LOCKED, EventSource.SYSTEM, "из консоли");

        assertThat(entry.actorId())
                .as("ноль — не идентификатор пользователя, а «исполнитель неизвестен»")
                .isNull();
        assertThat(entry.accountId()).isEqualTo(42L);
    }

    @Test
    void отрицательный_исполнитель_тоже_не_попадает_в_колонку() {
        SecurityLogEntry entry = SecurityLogEntry.byAdmin(42L, -1L,
                SecurityEventType.ACCOUNT_UNLOCKED, EventSource.SYSTEM, null);

        assertThat(entry.actorId()).isNull();
    }

    @Test
    void настоящий_исполнитель_сохраняется() {
        SecurityLogEntry entry = SecurityLogEntry.byAdmin(42L, 7L,
                SecurityEventType.SESSION_REVOKED, EventSource.MINECRAFT, "администратор в игре");

        assertThat(entry.actorId()).isEqualTo(7L);
        assertThat(entry.source()).isEqualTo(EventSource.MINECRAFT);
    }

    @Test
    void обычная_запись_исполнителя_не_имеет() {
        SecurityLogEntry entry = SecurityLogEntry.of(42L, SecurityEventType.LOGIN_SUCCESS,
                EventSource.WEB, IpAddress.of("203.0.113.7"), "вход");

        assertThat(entry.actorId()).isNull();
        assertThat(entry.severity()).isEqualTo(SecurityEventType.LOGIN_SUCCESS.defaultSeverity());
    }

    @Test
    void метаданные_копируются_и_не_меняются_снаружи() {
        Map<String, Object> source = new java.util.HashMap<>();
        source.put("browser", "Firefox");

        SecurityLogEntry entry = SecurityLogEntry
                .of(1L, SecurityEventType.LOGIN_SUCCESS, EventSource.WEB, null, null)
                .withMetadata(source);
        source.put("browser", "подменено");

        assertThat(entry.metadata()).containsEntry("browser", "Firefox");
    }
}
