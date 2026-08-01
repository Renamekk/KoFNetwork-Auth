package net.kofnetwork.auth.api.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest {

    private static Role withPermissions(String... nodes) {
        return new Role(1, "test", "Тест", 0, null, false, Set.of(nodes), Instant.now());
    }

    @Test
    void точное_совпадение_узла() {
        Role role = withPermissions("kofauth.admin.reload");

        assertThat(role.hasPermission("kofauth.admin.reload")).isTrue();
        assertThat(role.hasPermission("kofauth.admin.lock")).isFalse();
    }

    @Test
    void маска_покрывает_вложенные_узлы() {
        Role role = withPermissions("kofauth.admin.*");

        assertThat(role.hasPermission("kofauth.admin.reload")).isTrue();
        assertThat(role.hasPermission("kofauth.admin.lock")).isTrue();
    }

    @Test
    void маска_верхнего_уровня_покрывает_глубоко_вложенные_узлы() {
        Role role = withPermissions("kofauth.*");

        assertThat(role.hasPermission("kofauth.admin.reload")).isTrue();
        assertThat(role.hasPermission("kofauth.login")).isTrue();
    }

    @Test
    void маска_не_покрывает_соседнюю_ветку() {
        Role role = withPermissions("kofauth.admin.*");

        assertThat(role.hasPermission("kofauth.login")).isFalse();
        assertThat(role.hasPermission("otherplugin.admin.reload")).isFalse();
    }

    @Test
    void звёздочка_даёт_всё() {
        Role role = withPermissions("*");

        assertThat(role.hasPermission("kofauth.admin.migrate")).isTrue();
        assertThat(role.hasPermission("совершенно.произвольный.узел")).isTrue();
    }

    @Test
    void пустой_набор_прав_ничего_не_даёт() {
        Role role = withPermissions();

        assertThat(role.hasPermission("kofauth.login")).isFalse();
    }

    @Test
    void маска_не_покрывает_собственный_родительский_узел() {
        // kofauth.admin.* — это права ВНУТРИ kofauth.admin, а не сам kofauth.admin.
        Role role = withPermissions("kofauth.admin.*");

        assertThat(role.hasPermission("kofauth.admin")).isFalse();
    }

    @Test
    void набор_прав_неизменяем() {
        Set<String> mutable = new java.util.HashSet<>(Set.of("kofauth.login"));
        Role role = new Role(1, "test", "Тест", 0, null, false, mutable, Instant.now());

        mutable.add("kofauth.admin");

        assertThat(role.hasPermission("kofauth.admin")).isFalse();
        assertThat(role.permissions()).hasSize(1);
    }
}
