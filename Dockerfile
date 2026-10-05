# syntax=docker/dockerfile:1

# ---- build: compile the fat jar ----
FROM maven:3.10-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && cp target/LiveStreamBot-*.jar /bot.jar

# ---- jre: minimal Java runtime containing only the modules the bot uses ----
FROM maven:3.10-eclipse-temurin-21-alpine AS jre
COPY --from=build /bot.jar /bot.jar
# jdk.crypto.ec: TLS ECDHE ciphers; jdk.unsupported: sun.misc.Unsafe (OkHttp, Kotlin, sqlite-jdbc);
# java.xml: YouTube feed parsing (currently also pulled in by java.desktop, listed so it never silently drops out)
RUN MODULES="$(jdeps --ignore-missing-deps --multi-release 21 --print-module-deps /bot.jar),jdk.crypto.ec,jdk.unsupported,java.xml" \
    && echo "jlink modules: $MODULES" \
    && jlink --add-modules "$MODULES" \
        --strip-debug --no-man-pages --no-header-files \
        --compress=zip-9 --generate-cds-archive \
        --output /opt/jre \
    # this archive is only used with heaps over 32 GB
    && rm /opt/jre/lib/server/classes_nocoops.jsa
# Unpack sqlite's musl native library now: at runtime /tmp is a noexec tmpfs, so it can't be extracted there.
RUN mkdir /opt/sqlite && cd /tmp \
    && jar xf /bot.jar "org/sqlite/native/Linux-Musl/$(uname -m)/libsqlitejdbc.so" \
    && mv "org/sqlite/native/Linux-Musl/$(uname -m)/libsqlitejdbc.so" /opt/sqlite/

# ---- runtime ----
FROM alpine:3.24
RUN addgroup -S bot && adduser -S -G bot -H bot \
    && mkdir /data && chown bot:bot /data
COPY --from=jre /opt/jre /opt/jre
COPY --from=jre /opt/sqlite /opt/sqlite
COPY --from=build /bot.jar /app/bot.jar

# AutoCreateSharedArchive: the first run dumps the app's loaded classes (JDA, OkHttp, ...) to /data;
# later runs memory-map them, cutting startup time and metaspace. Rebuilt automatically after updates.
ENV DB_PATH=/data/livestreambot.db \
    JAVA_OPTS="-XX:+UseSerialGC -Xms16m -Xmx96m -Xss512k -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:MaxDirectMemorySize=32m -XX:+ExitOnOutOfMemoryError -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=/data/app-cds.jsa -Xlog:cds*=error"

USER bot
VOLUME /data
# exec replaces the shell so java is PID 1 and receives SIGTERM from `docker stop`
ENTRYPOINT ["sh", "-c", "exec /opt/jre/bin/java -Dorg.sqlite.lib.path=/opt/sqlite -Dorg.sqlite.lib.name=libsqlitejdbc.so $JAVA_OPTS -jar /app/bot.jar"]
