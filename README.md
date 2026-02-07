# ChatGPT Gateway

A Spring Boot reactive gateway service built with Kotlin for managing ChatGPT API interactions.

## Tech Stack

- **Language**: Kotlin 2.3.0
- **Framework**: Spring Boot 4.1.0-SNAPSHOT
- **Runtime**: Java 21
- **Build Tool**: Gradle
- **Key Dependencies**:
  - Spring WebFlux (Reactive Web)
  - Spring Security
  - Spring Boot Actuator
  - Kotlin Coroutines
  - Project Reactor

## Getting Started

### Prerequisites

- Java 21
- Gradle (or use the included Gradle wrapper)

### Building the Project

```bash
./gradlew build
```

### Running the Application

```bash
./gradlew bootRun
```

## Deploy with Docker Image (No Host JRE)

Use this path on a VPS where you do not want to install Java/JRE on the host.

### Prerequisite

- Docker installed on the VPS

### 1) Pull image from Docker Hub

```bash
docker pull docker.io/<org>/chatgpt-gateway:<tag>
```

### 2) Create `.env` with required variables

```dotenv
# required
OPENAI_API_KEY=sk-...
INTERNAL_API_KEY=your-internal-shared-key

# optional
OPENAI_ORGANIZATION=org_...
OPENAI_PROJECT=proj_...

# recommended for VPS/prod
SPRING_PROFILES_ACTIVE=prod
```

### 3) Run container

```bash
docker run -d \
  --name chatgpt-gateway \
  -p 8080:8080 \
  --env-file .env \
  docker.io/<org>/chatgpt-gateway:<tag>
```

### 4) Verify container health

```bash
curl -fsS http://localhost:8080/actuator/health
```

### 5) Optional: verify `/v1/responses` with internal API key

```bash
curl -sS -X POST http://localhost:8080/v1/responses \
  -H "Content-Type: application/json" \
  -H "X-Internal-Api-Key: ${INTERNAL_API_KEY}" \
  -d '{"model":"gpt-5","input":"hello"}'
```

### Build and Publish Image

Build a local image for `linux/amd64`:

```bash
docker build --platform linux/amd64 -t chatgpt-gateway:local .
```

Push a tagged image to Docker Hub:

```bash
docker buildx build --platform linux/amd64 \
  -t docker.io/<org>/chatgpt-gateway:<tag> \
  --push .
```

Optional: also push `latest`:

```bash
docker buildx build --platform linux/amd64 \
  -t docker.io/<org>/chatgpt-gateway:<tag> \
  -t docker.io/<org>/chatgpt-gateway:latest \
  --push .
```

## Project Structure

- Package: `com.ericdream.gateway`
- Built with reactive programming patterns using Kotlin Coroutines and Project Reactor

## Reference Documentation

For further reference, please consider the following sections:

* [Official Gradle documentation](https://docs.gradle.org)
* [Spring Boot Gradle Plugin Reference Guide](https://docs.spring.io/spring-boot/gradle-plugin)
* [Spring Reactive Web](https://docs.spring.io/spring-boot/reference/web/reactive.html)
* [Spring Security](https://docs.spring.io/spring-boot/reference/web/spring-security.html)
* [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/index.html)

## License

[Add your license here]
