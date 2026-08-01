package net.kofnetwork.auth.api.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final IpAddress IP = IpAddress.of("203.0.113.7");

    private static Account.Builder base() {
        return Account.newAccount(UUID_A, "Steve", "$2a$12$hash", IP);
    }

    @Test
    void новый_аккаунт_активен_и_без_второго_фактора() {
        Account account = base().build();

        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.hasTwoFactor()).isFalse();
        assertThat(account.isFirstLogin()).isTrue();
        assertThat(account.isPersisted()).isFalse();
    }

    @Test
    void ник_в_нижнем_регистре_выводится_автоматически() {
        Account account = base().username("SteveTheBuilder").build();

        assertThat(account.lowerUsername()).isEqualTo("stevethebuilder");
    }

    @Test
    void вход_разрешён_только_активному_аккаунту() {
        Instant now = Instant.now();

        assertThat(base().status(AccountStatus.ACTIVE).build().canLogin(now)).isTrue();
        assertThat(base().status(AccountStatus.LOCKED).build().canLogin(now)).isFalse();
        assertThat(base().status(AccountStatus.BANNED).build().canLogin(now)).isFalse();
        assertThat(base().status(AccountStatus.PENDING_DELETION).build().canLogin(now)).isFalse();
    }

    @Test
    void временная_блокировка_запрещает_вход_до_истечения() {
        Instant now = Instant.now();
        Account account = base().lockedUntil(now.plusSeconds(600)).build();

        assertThat(account.isTemporarilyLocked(now)).isTrue();
        assertThat(account.canLogin(now)).isFalse();
        assertThat(account.canLogin(now.plusSeconds(601))).isTrue();
    }

    @Test
    void предпочтительный_второй_фактор_самый_быстрый_из_включённых() {
        Account account = base()
                .twoFactorMethods(Set.of(TwoFactorMethod.EMAIL, TwoFactorMethod.TOTP, TwoFactorMethod.TELEGRAM))
                .build();

        // Telegram (30) быстрее TOTP (20), который быстрее EMAIL (10).
        assertThat(account.preferredTwoFactor()).isEqualTo(TwoFactorMethod.TELEGRAM);
    }

    @Test
    void предпочтительный_фактор_отсутствует_когда_2FA_выключена() {
        assertThat(base().build().preferredTwoFactor()).isNull();
    }

    @Test
    void набор_вторых_факторов_неизменяем() {
        Account account = base().twoFactorMethods(Set.of(TwoFactorMethod.TOTP)).build();

        assertThat(account.twoFactorMethods()).isUnmodifiable();
    }

    @Test
    void toBuilder_сохраняет_все_поля() {
        Instant now = Instant.now();
        Account original = base()
                .id(42L)
                .premium(true)
                .lastLoginIp(IP)
                .lastLoginAt(now)
                .lastCountry("RU")
                .failedLoginAttempts(3)
                .captchaPassed(true)
                .twoFactorMethods(Set.of(TwoFactorMethod.TOTP))
                .build();

        Account copy = original.toBuilder().build();

        assertThat(copy.id()).isEqualTo(42L);
        assertThat(copy.premium()).isTrue();
        assertThat(copy.lastLoginIp()).isEqualTo(IP);
        assertThat(copy.lastLoginAt()).isEqualTo(now);
        assertThat(copy.lastCountry()).isEqualTo("RU");
        assertThat(copy.failedLoginAttempts()).isEqualTo(3);
        assertThat(copy.captchaPassed()).isTrue();
        assertThat(copy.twoFactorMethods()).containsExactly(TwoFactorMethod.TOTP);
    }

    @Test
    void добавление_и_удаление_второго_фактора_не_мутирует_исходный_аккаунт() {
        Account original = base().twoFactorMethods(Set.of(TwoFactorMethod.TOTP)).build();

        Account withTelegram = original.toBuilder()
                .addTwoFactorMethod(TwoFactorMethod.TELEGRAM)
                .build();
        Account withoutTotp = withTelegram.toBuilder()
                .removeTwoFactorMethod(TwoFactorMethod.TOTP)
                .build();

        assertThat(original.twoFactorMethods()).containsExactly(TwoFactorMethod.TOTP);
        assertThat(withTelegram.twoFactorMethods())
                .containsExactlyInAnyOrder(TwoFactorMethod.TOTP, TwoFactorMethod.TELEGRAM);
        assertThat(withoutTotp.twoFactorMethods()).containsExactly(TwoFactorMethod.TELEGRAM);
    }

    @Test
    void равенство_по_uuid_а_не_по_всем_полям() {
        Account a = base().id(1L).failedLoginAttempts(0).build();
        Account b = base().id(1L).failedLoginAttempts(5).lastCountry("DE").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void разные_uuid_дают_разные_аккаунты() {
        Account a = base().build();
        Account b = Account.newAccount(UUID.randomUUID(), "Steve", "$2a$12$hash", IP).build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toString_не_раскрывает_хэш_пароля() {
        Account account = base().build();

        assertThat(account.toString())
                .contains("Steve")
                .contains("<redacted>")
                .doesNotContain("$2a$12$hash");
    }
}
