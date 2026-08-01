package net.kofnetwork.auth.webapi;

import net.kofnetwork.auth.core.KoFAuthCore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * Точка входа REST API.
 *
 * <p>Поднимает собственный экземпляр {@link KoFAuthCore}: веб-API — отдельный
 * процесс, обычно на другой машине, и делит с игровой сетью только MySQL и Redis.
 */
@SpringBootApplication
public class KoFAuthWebApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(KoFAuthWebApiApplication.class, args);
    }

    /**
     * Экземпляр Core.
     *
     * <p>{@code destroyMethod} обязателен: без него при остановке приложения
     * останутся открытыми пул соединений и подписка на Redis.
     */
    @Bean(destroyMethod = "close")
    public KoFAuthCore koFAuthCore(@Value("${kofauth.config-directory:./config}") String directory) {
        return KoFAuthCore.start(Path.of(directory));
    }
}
