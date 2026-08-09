package net.kofnetwork.auth.webapi.security;

import net.kofnetwork.auth.api.model.IpAddress;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Кому можно верить на слово про адрес клиента.
 *
 * <p><b>Что здесь исправлено.</b> Заголовок с реальным адресом принимался от кого
 * угодно: код проверял только флаг {@code forwarding.trust-proxy-header}, а перечень
 * {@code forwarding.trusted-proxies} не использовался вовсе. Достаточно было
 * добраться до сервиса в обход обратного прокси — а в контейнерной сети или при
 * ошибке в правилах брандмауэра это обычное дело — и подставить себе любой адрес.
 * Рушилось сразу три механизма: ограничение скорости считалось по выдуманному
 * адресу, привязка сессии к IP переставала что-либо значить, бан по адресу обходился
 * одним заголовком.
 *
 * <p>Теперь заголовку верят, только если <em>соединение</em> пришло с адреса из
 * перечня. Проверяется адрес сокета — единственное, что клиент подделать не может.
 *
 * <p>Пустой перечень означает «не верить никому». Это не оплошность, а решение:
 * «доверять всем» — ровно то состояние, из которого здесь и выбирались.
 */
public final class TrustedProxies {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrustedProxies.class);

    /** Одна запись перечня: адрес и длина префикса. */
    private record Rule(byte[] network, int prefixBits, String source) {

        boolean matches(byte[] candidate) {
            if (candidate.length != network.length) {
                // IPv4 и IPv6 сравнивать побайтово нельзя. Смешанные записи —
                // не ошибка конфигурации, а обычное дело; просто не совпадают.
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Rule> rules;

    private TrustedProxies(List<Rule> rules) {
        this.rules = rules;
    }

    /**
     * Разбирает перечень из конфигурации.
     *
     * <p>Принимаются как отдельные адреса ({@code 10.0.0.5}), так и подсети
     * ({@code 10.0.0.0/8}, {@code ::1/128}). Нечитаемая запись пропускается с
     * записью в лог: одна опечатка не должна обнулять весь перечень — иначе
     * исправление конфигурации превращалось бы в отказ доверять и настоящему прокси.
     */
    public static @NotNull TrustedProxies parse(@NotNull List<String> entries) {
        List<Rule> parsed = new ArrayList<>(entries.size());
        for (String entry : entries) {
            String trimmed = entry == null ? "" : entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Rule rule = parseOne(trimmed);
            if (rule == null) {
                LOGGER.error("Запись trusted-proxies '{}' не разобрана и пропущена", trimmed);
                continue;
            }
            parsed.add(rule);
        }
        return new TrustedProxies(List.copyOf(parsed));
    }

    private static Rule parseOne(String entry) {
        int slash = entry.lastIndexOf('/');
        String host = slash < 0 ? entry : entry.substring(0, slash);
        IpAddress address = IpAddress.ofNullable(host);
        if (address.isUnknown()) {
            return null;
        }
        byte[] network = address.toBytes();
        int maxBits = network.length * 8;

        if (slash < 0) {
            // Одиночный адрес — это подсеть с полной длиной префикса.
            return new Rule(network, maxBits, entry);
        }
        int prefix;
        try {
            prefix = Integer.parseInt(entry.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (prefix < 0 || prefix > maxBits) {
            return null;
        }
        return new Rule(network, prefix, entry);
    }

    /** Есть ли в перечне хоть что-то. */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * Пришло ли соединение от доверенного посредника.
     *
     * @param peer адрес сокета — то, что подделать нельзя
     */
    public boolean trusts(@NotNull IpAddress peer) {
        if (rules.isEmpty() || peer.isUnknown()) {
            return false;
        }
        byte[] candidate = peer.toBytes();
        for (Rule rule : rules) {
            if (rule.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return rules.stream().map(Rule::source).toList().toString();
    }
}
