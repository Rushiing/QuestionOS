FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=builder /app/target/*.jar /app/app.jar

EXPOSE 8080
# 与根目录 Dockerfile.railway-backend 保持一致：Railway Root Directory 若被误设为 java-backend，
# 本文件会经由 railway.json 接管构建——缺 postgres profile 会让持久化静默失效（2026-06 实际发生过）。
# 本地无库运行请显式覆盖：docker run -e SPRING_PROFILES_ACTIVE=local ...
ENV SPRING_PROFILES_ACTIVE=postgres
ENTRYPOINT ["java", "-Xms256m", "-Xmx1024m", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", "-jar", "/app/app.jar"]
