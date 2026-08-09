# =============================================================================
#  Универсальный образ для jar-модулей KoFAuth: WebAPI, Telegram, Discord.
#  Какой именно модуль собрать — задаёт аргумент MODULE.
# =============================================================================

# ---- Сборка ----------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Сначала только описания сборки: слой с зависимостями переиспользуется,
# пока не менялись pom-файлы. Изменение кода не заставляет качать Maven-репозиторий заново.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY KoFAuth-API/pom.xml KoFAuth-API/
COPY KoFAuth-Core/pom.xml KoFAuth-Core/
COPY KoFAuth-Velocity/pom.xml KoFAuth-Velocity/
COPY KoFAuth-Paper/pom.xml KoFAuth-Paper/
COPY KoFAuth-WebAPI/pom.xml KoFAuth-WebAPI/

RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline -DskipTests || true

COPY . .

ARG MODULE=KoFAuth-WebAPI
# Тесты в образе не гоняем: интеграционные требуют Docker внутри Docker.
# Их место — конвейер CI, а не сборка артефакта.
RUN ./mvnw -B -q -pl ${MODULE} -am package -DskipTests \
    && cp ${MODULE}/target/*.jar /build/app.jar

# ---- Выполнение ------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Непривилегированный пользователь: процесс, скомпрометированный через уязвимость
# в зависимости, не должен получить root внутри контейнера.
RUN addgroup -S kofauth && adduser -S -G kofauth kofauth

WORKDIR /app
COPY --from=build /build/app.jar app.jar
RUN mkdir -p /app/config && chown -R kofauth:kofauth /app

USER kofauth

# MaxRAMPercentage вместо -Xmx: контейнеру задают лимит памяти, и JVM должна
# считать долю от него, а не от памяти хоста.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
-Djava.security.egd=file:/dev/urandom -Duser.timezone=UTC"

EXPOSE 8080

# exec-форма и sh -c: нужна подстановка JAVA_OPTS, но PID 1 должен остаться
# у java, иначе сигнал остановки не дойдёт до приложения и Docker убьёт его
# принудительно через 10 секунд, оборвав сброс буфера аудита.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar /app/config"]
