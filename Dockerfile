# Stage 1: Dependency Caching - Only download and process dependencies
FROM gradle:jdk21 AS cache_dependencies

# 1. 拷贝构建所需的配置文件
# 这些文件的变化会触发该阶段的重新执行
COPY build.gradle.kts settings.gradle.kts gradle.properties /home/gradle/app/
COPY gradle /home/gradle/app/gradle

WORKDIR /home/gradle/app

# 2. 只执行依赖相关的任务
# 使用 --init-script 来禁用缓存的 Gradle wrapper，确保使用 Docker 镜像自带的 Gradle
# 运行 dependencies 命令下载所有 runtime 和 compile 依赖
# 这一步会填充 Docker 层缓存，但不会产生脆弱的 build 目录
RUN gradle --no-daemon clean build --dry-run || true 


# Stage 2: Application Build - Compile using the cached dependencies
FROM gradle:jdk21 AS build_app

# 1. 继承 Stage 1 的依赖缓存（这是 Docker 层缓存的机制）
# 2. 拷贝所有项目文件，包括源代码
COPY --from=cache_dependencies /home/gradle/app /home/gradle/src/
COPY . /home/gradle/src/

WORKDIR /home/gradle/src

# 3. 编译并生成 Fat JAR
# 因为依赖已经在 Stage 1 缓存好了，这一步应该会很快
RUN gradle buildFatJar --no-daemon -i


# Stage 3: Create the Runtime Image
FROM azul/zulu-openjdk:21 AS runtime

EXPOSE 8080

RUN mkdir /app
# 使用 Stage 2 构建的 JAR 文件
COPY --from=build_app /home/gradle/src/build/libs/*.jar /app/ktor-docker-sample.jar

ENTRYPOINT ["java","-jar","/app/ktor-docker-sample.jar"]
