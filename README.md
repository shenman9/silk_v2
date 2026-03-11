# Silk

A cross-platform chat application built with Kotlin Multiplatform. It provides a web app, an Android APK, and optional desktop clients, with a Kotlin backend, Weaviate for vector search, and OpenAI-compatible AI APIs.

---

## Project overview

- **Backend**: Kotlin (Ktor), serves API and static web assets. Reads config from project-root `.env`.
- **Frontend**: Kotlin/JS WebApp (production build is copied to `backend/static` and served there).
- **Android**: Kotlin Multiplatform Android app; APK can be built and served for download.
- **Vector store**: Weaviate (run via Docker by `silk.sh`). Used for context and file search.
- **AI**: Any OpenAI-compatible API; configure `API_BASE_URL`, `OPENAI_API_KEY`, and `AI_MODEL` in `.env`.

All day-to-day operations (build, run, stop, logs, Weaviate) are driven by the **`silk.sh`** script in the project root.

---

## Project structure

| Path | Description |
|------|-------------|
| `silk.sh` | Main script: deploy, start/stop, build, logs, Weaviate management. |
| `.env` | Local config (create from `.env.example`). Not committed. |
| `.env.example` | Template and documentation for required/optional env vars. |
| `backend/` | Kotlin backend (Ktor), static files, chat history. |
| `frontend/webApp/` | Kotlin/JS web frontend. |
| `frontend/androidApp/` | Android app; APK output can be copied to `backend/static`. |
| `frontend/desktopApp/` | Desktop client (optional). |
| `frontend/shared/` | Shared KMP code for frontends. |
| `search/` | Weaviate schema and indexing (e.g. `schema.py`). |

---

## Configuration

Configuration is done via a **`.env`** file in the project root. The script `silk.sh` loads it automatically (and strips CRLF line endings).

1. Copy the example: `cp .env.example .env`
2. Edit `.env` and set at least:
   - **Backend / public URL**: `BACKEND_HOST`, `BACKEND_HTTP_PORT` (default `8006`)
   - **AI**: `OPENAI_API_KEY`, `API_BASE_URL`, `AI_MODEL`
   - **Weaviate**: `WEAVIATE_URL` (e.g. `http://<host>:8008`)
3. Optional: external search (e.g. `SERPAPI_KEY`), feature flags. See `.env.example` for comments.

Do not commit `.env`; it is listed in `.gitignore`.

---

## Installation

Follow these steps to get Silk running using `silk.sh`. The script expects **Java 17**, **Python 3**, and (for Weaviate) **Docker**. Gradle is used via the project’s wrapper.

### 1. Clone and enter the project

```bash
cd /path/to/silk   # or your repo root
```

### 2. Create and edit `.env`

```bash
cp .env.example .env
# Edit .env: set BACKEND_HOST, BACKEND_HTTP_PORT, OPENAI_API_KEY, API_BASE_URL, AI_MODEL, WEAVIATE_URL
```

Use your real API base URL and key; set `BACKEND_HOST` to the IP or hostname other devices will use to reach this machine (e.g. for the web UI and APK download).

### 3. One-shot deploy (recommended first time)

This builds the WebApp and APK, frees the ports used by Silk, starts Weaviate (Docker), then starts the backend and the web frontend:

```bash
./silk.sh deploy
```

When it finishes, the script prints the URLs (backend, web UI, APK download). Use `./silk.sh status` to confirm all services.

### 4. Start only (if already built)

If you have already run `deploy` or built manually:

```bash
./silk.sh start
```

### 5. Check status and logs

```bash
./silk.sh status   # Backend, frontend, Weaviate, APK path
./silk.sh logs     # Tail backend/frontend logs
```

### Default ports

| Service      | Port  | Note                    |
|-------------|-------|-------------------------|
| Backend     | 8006  | API + static + APK URL  |
| Web frontend| 8005  | HTTP server for WebApp  |
| Weaviate HTTP | 8008 | Vector DB               |
| Weaviate gRPC | 50051 | Vector DB             |

If a port is in use, `deploy` will try to free it; for `start`, the script may prompt. You can stop everything with `./silk.sh stop`.

---

## Operations (silk.sh)

All commands are run from the project root. `silk.sh` loads `.env` automatically.

| Command | Description |
|---------|-------------|
| `./silk.sh deploy` | Clean ports, build WebApp + APK, start Weaviate, backend, and frontend. |
| `./silk.sh start` | Start Weaviate, backend, and frontend (builds WebApp if missing). |
| `./silk.sh stop` | Stop backend, frontend, and Weaviate. |
| `./silk.sh restart` | Stop then start. |
| `./silk.sh status` | Show status of backend, frontend, Weaviate, and latest APK. |
| `./silk.sh logs` | Tail backend and frontend logs. |
| `./silk.sh build` | Build WebApp only; output is copied to `backend/static`. |
| `./silk.sh build-apk` | Build Android APK; copies to `backend/static` and sets `silk.apk` link. |
| `./silk.sh build-all` | Build WebApp and APK. |
| `./silk.sh weaviate start\|stop\|status\|schema` | Manage Weaviate (Docker) and schema. |

APK download URL (when backend is up): `http://<BACKEND_HOST>:8006/api/files/download-apk`.

---

## Development

- **Backend only**: `./gradlew :backend:run` (still need `.env` and optionally Weaviate).
- **Web dev**: `./gradlew :frontend:webApp:browserDevelopmentRun` (dev server; for production use `./silk.sh build` then serve via backend or `./silk.sh start`).
- **Env**: `silk.sh` sources `.env` with CRLF stripped. Prefer LF line endings in `.env` to avoid issues.

If the APK build fails with “Unable to delete directory” under `frontend/androidApp/build/snapshot/`, remove that directory and run `./silk.sh build-apk` again.

---

## License

MIT
