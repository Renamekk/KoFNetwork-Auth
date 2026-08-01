package net.kofnetwork.auth.core.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.AccountStatus;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.repository.AccountRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Выгрузка и загрузка аккаунтов для переноса между установками.
 *
 * <p><b>Формат — JSON Lines: одна запись на строку.</b> Не единый массив: выгрузка
 * сети на сто тысяч аккаунтов не должна собираться в памяти целиком ни при записи,
 * ни при чтении, а построчный формат читается и пишется потоком. Побочная польза —
 * оборванный на середине файл остаётся частично пригодным, тогда как незакрытый
 * JSON-массив не разбирается вовсе.
 *
 * <p><b>Хэши паролей входят в выгрузку.</b> Без них перенос бессмысленен — на новой
 * установке всем пришлось бы восстанавливать пароль. Это делает файл эквивалентом
 * дампа таблицы {@code users}: он создаётся с правами только для владельца, а сам
 * факт выгрузки пишется в аудит как критическое событие. Открытых паролей в нём нет
 * и быть не может — в базе их тоже нет.
 *
 * <p>Привязки, сессии и устройства не переносятся намеренно. Сессия чужой установки
 * недействительна по определению, а привязка Telegram или Discord должна быть
 * подтверждена владельцем заново — перенос её «как есть» означал бы, что доступ к
 * аккаунту наследуется вместе с файлом.
 */
public final class AccountTransfer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountTransfer.class);

    /** Версия формата: при несовпадении загрузка отказывает, а не угадывает. */
    static final int FORMAT_VERSION = 1;

    /** Размер страницы при выгрузке. */
    private static final int PAGE = 500;

    private final AccountRepository accounts;
    private final Executor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    public AccountTransfer(@NotNull AccountRepository accounts, @NotNull Executor executor) {
        this.accounts = accounts;
        this.executor = executor;
    }

    /**
     * Выгружает все аккаунты.
     *
     * <p>Первой строкой идёт заголовок с версией формата и моментом выгрузки —
     * по нему загрузка отличает наш файл от произвольного JSON Lines.
     *
     * @param target путь файла; существующий перезаписывается
     */
    public @NotNull CompletableFuture<TransferResult> exportTo(@NotNull Path target) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                long exported = 0;
                try (BufferedWriter writer = Files.newBufferedWriter(target,
                        StandardCharsets.UTF_8)) {

                    restrictToOwner(target);

                    ObjectNode header = mapper.createObjectNode();
                    header.put("kofauth", FORMAT_VERSION);
                    header.put("exportedAt", Instant.now().toString());
                    writer.write(mapper.writeValueAsString(header));
                    writer.newLine();

                    long cursor = 0;
                    while (true) {
                        List<Account> page = accounts.findPageAfter(cursor, PAGE).join();
                        if (page.isEmpty()) {
                            break;
                        }
                        for (Account account : page) {
                            writer.write(mapper.writeValueAsString(toJson(account)));
                            writer.newLine();
                            exported++;
                        }
                        cursor = page.get(page.size() - 1).id();
                    }
                }
                LOGGER.info("Выгружено аккаунтов: {} в {}", exported, target);
                return TransferResult.ok(exported, 0);
            } catch (IOException e) {
                LOGGER.error("Не удалось выгрузить аккаунты в {}", target, e);
                return TransferResult.failed(e.getMessage());
            }
        }, executor);
    }

    /**
     * Загружает аккаунты из файла.
     *
     * <p><b>Существующие не трогаются.</b> Совпадение ника — это либо повторный
     * запуск загрузки, либо столкновение двух сетей; в обоих случаях перезапись
     * заменила бы живой пароль чужим, и владелец потерял бы доступ. Такие записи
     * считаются пропущенными, и их число видно в отчёте.
     *
     * <p>Строка, которую не удалось разобрать, не прерывает загрузку: файл мог быть
     * оборван при копировании, и остановиться на первой битой строке значит потерять
     * все следующие за ней целые.
     */
    public @NotNull CompletableFuture<TransferResult> importFrom(@NotNull Path source) {
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.isReadable(source)) {
                return TransferResult.failed("Файл не найден или недоступен для чтения");
            }

            long imported = 0;
            long skipped = 0;

            try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                String headerLine = reader.readLine();
                String headerError = validateHeader(headerLine);
                if (headerError != null) {
                    return TransferResult.failed(headerError);
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        Account account = fromJson(mapper.readTree(line));
                        if (accounts.existsByUsername(account.username()).join()) {
                            skipped++;
                            continue;
                        }
                        accounts.insert(account).join();
                        imported++;
                    } catch (RuntimeException e) {
                        LOGGER.warn("Строка загрузки пропущена: {}", e.getMessage());
                        skipped++;
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Не удалось загрузить аккаунты из {}", source, e);
                return TransferResult.failed(e.getMessage());
            }

            LOGGER.info("Загружено аккаунтов: {}, пропущено: {}", imported, skipped);
            return TransferResult.ok(imported, skipped);
        }, executor);
    }

    /** @return текст ошибки либо {@code null}, если заголовок наш */
    private @Nullable String validateHeader(@Nullable String headerLine) {
        if (headerLine == null || headerLine.isBlank()) {
            return "Файл пуст";
        }
        try {
            JsonNode header = mapper.readTree(headerLine);
            JsonNode version = header.get("kofauth");
            if (version == null) {
                return "Это не файл выгрузки KoFAuth";
            }
            if (version.asInt() != FORMAT_VERSION) {
                return "Версия формата " + version.asInt()
                        + " не поддерживается, ожидается " + FORMAT_VERSION;
            }
            return null;
        } catch (IOException e) {
            return "Заголовок файла не разбирается";
        }
    }

    // ------------------------------------------------------------------ отображение

    private ObjectNode toJson(Account account) {
        ObjectNode node = mapper.createObjectNode();
        node.put("uuid", account.uuid().toString());
        node.put("username", account.username());
        node.put("passwordHash", account.passwordHash());
        node.put("passwordAlgorithm", account.passwordAlgorithm());
        node.put("status", account.status().name());
        node.put("premium", account.premium());
        node.put("registrationIp", account.registrationIp().asString());
        node.put("registrationDate", account.registrationDate().toString());
        node.put("captchaPassed", account.captchaPassed());
        if (account.passwordUpdatedAt() != null) {
            node.put("passwordUpdatedAt", account.passwordUpdatedAt().toString());
        }
        if (account.lastLoginAt() != null) {
            node.put("lastLoginAt", account.lastLoginAt().toString());
        }
        return node;
    }

    /**
     * Собирает аккаунт из строки файла.
     *
     * <p>Идентификатор не переносится: он принадлежит той установке, где выгрузка
     * сделана, и на новой его выдаст автоинкремент. Второй фактор тоже не переносится —
     * его секреты живут в отдельных таблицах, которых в выгрузке нет, и включённый
     * флаг без секрета запер бы владельца снаружи.
     */
    private Account fromJson(JsonNode node) {
        String username = required(node, "username");
        Account.Builder builder = Account.newAccount(
                UUID.fromString(required(node, "uuid")),
                username,
                required(node, "passwordHash"),
                IpAddress.ofNullable(text(node, "registrationIp")));

        builder.passwordAlgorithm(text(node, "passwordAlgorithm", "BCRYPT"));
        builder.status(AccountStatus.valueOf(text(node, "status", AccountStatus.ACTIVE.name())));
        builder.premium(node.path("premium").asBoolean(false));
        builder.captchaPassed(node.path("captchaPassed").asBoolean(false));

        instant(node, "registrationDate", builder::registrationDate);
        instant(node, "passwordUpdatedAt", builder::passwordUpdatedAt);
        instant(node, "lastLoginAt", builder::lastLoginAt);

        return builder.build();
    }

    private static void instant(JsonNode node, String field,
                                java.util.function.Consumer<Instant> setter) {
        String value = text(node, field);
        if (value != null && !value.isBlank()) {
            setter.accept(Instant.parse(value));
        }
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Отсутствует поле " + field);
        }
        return value;
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null ? fallback : value;
    }

    /**
     * Снимает доступ у всех, кроме владельца.
     *
     * <p>На Windows и на файловых системах без POSIX-прав операция не поддерживается.
     * Это не повод отменять выгрузку, но повод сказать об этом в логе: администратор
     * должен знать, что файл с хэшами лежит с правами по умолчанию.
     */
    private static void restrictToOwner(Path target) {
        try {
            Files.setPosixFilePermissions(target,
                    java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            LOGGER.warn("Не удалось ограничить права на {}: файл с хэшами паролей "
                    + "создан с правами по умолчанию", target);
        }
    }

    /**
     * Итог переноса.
     *
     * @param processed сколько записей выгружено или загружено
     * @param skipped   сколько пропущено при загрузке
     */
    public record TransferResult(boolean success,
                                 long processed,
                                 long skipped,
                                 @Nullable String error) {

        static TransferResult ok(long processed, long skipped) {
            return new TransferResult(true, processed, skipped, null);
        }

        static TransferResult failed(@Nullable String error) {
            return new TransferResult(false, 0, 0,
                    error == null ? "Неизвестная ошибка" : error);
        }
    }
}
