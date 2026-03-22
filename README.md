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

## API Endpoints

All endpoints in `ProxyController` require:

- `Authorization: Bearer ${INTERNAL_API_KEY}`
- `X-Client-Id` is optional (used for rate limiting; defaults to `anon` if missing)

Current routes:

| Method | Path | Content-Type | Required fields |
| --- | --- | --- | --- |
| `POST` | `/v1/responses` | `application/json` | JSON body with `stream=false` |
| `POST` | `/v1/responses/stream` | `text/event-stream` | JSON body with `stream=true` |
| `POST` | `/v1/audio/transcriptions` | `multipart/form-data` | form-data `file`, `model` |
| `POST` | `/v1/audio/translations` | `multipart/form-data` | form-data `file`, `model` |
| `POST` | `/openrouter/test` | none required | none |
| `POST` | `/openrouter/dictionary` | none required | query params `model`, `word` |

Audio endpoint notes:

- max file size: `25 MB`
- allowed file formats: `mp3`, `mp4`, `mpeg`, `mpga`, `m4a`, `wav`, `webm`
- transcription models: `whisper-1`, `gpt-4o-mini-transcribe`, `gpt-4o-transcribe`
- translation models: `whisper-1` only
- optional form fields: `response_format`, `prompt`
- `response_format`:
  - `whisper-1`: `json`, `text`, `srt`, `verbose_json`, `vtt`
  - `gpt-4o-mini-transcribe` / `gpt-4o-transcribe`: `json`, `text`

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
OPENROUTER_API_KEY=sk-or-...
INTERNAL_API_KEY=your-internal-shared-key

# optional
OPENAI_ORGANIZATION=org_...
OPENAI_PROJECT=proj_...
OPENROUTER_TEST_MODEL=openai/gpt-4o-mini

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

### 5) Optional: verify gateway endpoints with internal API key

`/v1/responses`:

```bash
curl -sS -X POST http://localhost:8080/v1/responses \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${INTERNAL_API_KEY}" \
  -d '{"model":"gpt-5","input":"hello"}'
```

`/v1/responses/stream`:

```bash
curl -N -sS -X POST http://localhost:8080/v1/responses/stream \
  -H "Content-Type: text/event-stream" \
  -H "Accept: text/event-stream" \
  -H "Authorization: Bearer ${INTERNAL_API_KEY}" \
  -d '{"model":"gpt-5","input":"hello","stream":true}'
```

`/openrouter/test`:

```bash
curl -sS -X POST http://localhost:8080/openrouter/test \
  -H "Authorization: Bearer ${INTERNAL_API_KEY}"
```

`/openrouter/dictionary`:

```bash
curl -sS -X POST "http://localhost:8080/openrouter/dictionary?model=openai/gpt-4o-mini&word=apple" \
  -H "Authorization: Bearer ${INTERNAL_API_KEY}"
```

`/v1/audio/transcriptions`:

```bash
curl -sS -X POST http://localhost:8080/v1/audio/transcriptions \
  -H "Authorization: Bearer ${INTERNAL_API_KEY}" \
  -F "file=@/path/to/audio.mp3" \
  -F "model=whisper-1" \
  -F "response_format=json" \
  -F "prompt=include punctuation"
```

`/v1/audio/translations`:

```bash
curl -sS -X POST http://localhost:8080/v1/audio/translations \
  -H "Authorization: Bearer ${INTERNAL_API_KEY}" \
  -F "file=@/path/to/audio.wav" \
  -F "model=whisper-1" \
  -F "response_format=text" \
  -F "prompt=translate to English"
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
