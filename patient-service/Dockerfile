# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy ONLY the pom first, resolve dependencies, and cache that as its
# own layer. This layer rebuilds only when pom.xml changes, not when you
# edit source. This is the single most important trick for Java builds.
#
# The cache mount keeps ~/.m2 between builds (it is not part of the image), so
# even when the pom or source changes, only new artifacts are downloaded.
# go-offline alone does not fetch every plugin dependency that `package` needs.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B

# Now copy source and build. Editing a .java file invalidates the cache
# from HERE down only, so your dependencies stay cached.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -B

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Pull just the fat jar out of the build stage. JDK, Maven, and the
# .m2 cache are all left behind in the discarded build stage.
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
