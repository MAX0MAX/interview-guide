# AGENTS.md

## Cursor Cloud specific instructions

### Architecture overview

This is a Spring Boot 4.0 (Java 21) + React 18 (TypeScript, Vite) application — an AI-powered interview coaching platform. The backend (`app/`) connects to PostgreSQL+pgvector, Redis, and MinIO (S3-compatible storage). The frontend (`frontend/`) is a Vite dev server that proxies `/api` requests to the backend at `localhost:8080`.

### Infrastructure services (Docker)

Infrastructure runs via `docker compose` from the repo root. Start only the dependency services for local development:

```bash
sudo docker compose up -d postgres redis minio createbuckets
```

After starting, you must also create the MinIO bucket accessible from the host network (the `createbuckets` init container uses the Docker-internal hostname `minio:9000`, which the host-side backend cannot reach):

```bash
sudo docker run --rm --network host --entrypoint /bin/sh minio/mc -c \
  "/usr/bin/mc alias set myminio http://localhost:9000 minioadmin minioadmin; \
   /usr/bin/mc mb myminio/interview-guide --ignore-existing; \
   /usr/bin/mc anonymous set public myminio/interview-guide;"
```

### Running the backend

```bash
./gradlew bootRun
```

The backend reads `.env` from the repo root (via a Gradle task in `app/build.gradle`). Copy `.env.example` to `.env` and fill in `AI_BAILIAN_API_KEY` for AI features to work. Without a valid key the app still starts and serves CRUD APIs, but AI-dependent features (resume analysis, mock interview, RAG) will fail.

Backend starts on `http://localhost:8080`.

### Running the frontend

```bash
cd frontend && pnpm install && pnpm dev
```

Frontend starts on `http://localhost:5173`. Vite proxies `/api` to `localhost:8080`.

### Running tests

- **Backend**: `./gradlew :app:test` — runs JUnit 5 tests. 5 `QwenTtsService` tests fail without a valid `AI_BAILIAN_API_KEY`; all other tests pass using the H2 in-memory database.
- **Frontend**: `pnpm build` (in `frontend/`) runs `tsc && vite build`. There are 3 pre-existing TS unused-variable errors; these don't affect `pnpm dev`.

### Gotchas

- Docker daemon requires `fuse-overlayfs` storage driver and `iptables-legacy` in the Cloud Agent VM. See the Docker setup section in the system instructions.
- The `dockerd` must be started manually: `sudo dockerd &>/tmp/dockerd.log &`.
- `pnpm install` in `frontend/` may show warnings about ignored build scripts (`@swc/core`, `esbuild`, `protobufjs`). These are non-blocking; the dev server works fine.
- The `packageManager` field in `frontend/package.json` pins pnpm 10.26.2. The VM may have a newer version pre-installed; this is compatible.
- The Gradle wrapper downloads Gradle 8.14 on first run (~2.5 GB JDK toolchain + dependencies). Subsequent runs are fast.
