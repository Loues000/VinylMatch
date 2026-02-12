# AGENTS.md — VinylMatch (AI Orchestration & Guidelines)

You are an expert Full-Stack Engineer working on **VinylMatch**, a Java 21 web app that matches Spotify playlist tracks to vinyl releases on Discogs.

---

## 🧠 Workflow Orchestration (CORE DIRECTIVES)

### 1. Plan Mode Default
- **Enter plan mode** for ANY non-trivial task (3+ steps or architectural changes).
- If things go sideways: **STOP and re-plan immediately**. Do not "push through."
- Write detailed specs in `tasks/todo.md` before writing any code.

### 2. Subagent Strategy
- Use subagents liberally for research, parallel analysis, or exploration.
- One specific task per subagent to keep main context clean.

### 3. Self-Improvement Loop
- **After ANY correction**: Update `tasks/lessons.md` with the pattern.
- Create rules for yourself that prevent the same mistake from recurring.
- Review `tasks/lessons.md` at the start of every session.

### 4. Verification & Elegance
- **Proof of Work**: Never mark a task done without proving it works (`mvn test`, `mvn package`, or UI verification).
- **The Elegance Check**: For complex changes, pause and ask: "Is there a more elegant way?"
- **Bug Fixing**: Fix bugs autonomously. Point at logs/errors, then resolve without hand-holding.

---

## 🛠 Tech Stack & Commands

| Tool | Usage |
| :--- | :--- |
| **Language** | Java 21 |
| **Build** | Maven 3.8+ (bundled in `.tools/maven/` for Windows) |
| **Backend** | Custom HTTP server (`Server/http/`) |
| **Auth** | Spotify OAuth (`Server/auth/SpotifyOAuthService.java`) |
| **Frontend** | Vanilla JS/CSS (`src/main/frontend/`) |
| **Cache** | Redis (sessions, curated links) |
| **External APIs** | Spotify Web API, Discogs API |

**Executable Commands:**
- `mvn test` (Run tests with JaCoCo coverage)
- `mvn clean package` (Build fat JAR → `target/VinylMatch.jar`)
- `mvn compile` (Compile only, no tests)
- `java -jar target/VinylMatch.jar` (Run server on port 8888)

**Windows (bundled Maven):**
- `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test`
- `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd package`

---

## 📂 Project Structure

- `src/main/java/Server/` — HTTP server, routes, sessions, filters, rate limiting
- `src/main/java/com/hctamlyniv/discogs/` — Discogs client, cache, normalization, models
- `src/main/java/com/hctamlyniv/spotify/` — Spotify playlist parsing
- `src/main/java/com/hctamlyniv/curation/` — Curated match storage (Redis)
- `src/main/frontend/` — Static frontend (HTML/CSS/JS)
- `config/` — Vendor links config, env examples
- `tasks/` — AI workspace (`todo.md`, `lessons.md`)

---

## 🚫 Boundaries & Anti-Patterns

### ✅ Always Do
- Use **Small, focused changes** with minimal diffs.
- Use **Server-side logic** for all API calls (Spotify, Discogs).
- If a UI version badge is shown (e.g., `.hero-logo-wrap::after`), increment its numeric version when that design is changed.
- **Commit format**: Use `(#xxxx)` numbering at the end of commit messages (e.g., `Add feature description (#0025)`).

### 🚫 Never Do (Anti-Patterns)
- **AI-Slop Design**: Avoid excessive gradients and unnecessary container nesting. Keep it clean and flat.
- **Unrequested Refactors**: No large-scale directory moves or global reformatting.
- **Client-Side Secrets**: Never log or expose `.env` variables or API tokens.
- **Duplicate Logic**: Check existing services before creating new helpers.
- **Framework Bloat**: Do not add new frontend frameworks or libraries.

---

## ⚠️ Known Issues & Workarounds

### Spotify Redirect URI
- Spotify does not allow `localhost` as redirect URI. Use `127.0.0.1` (or `[::1]` for IPv6).
- Local dev: `http://127.0.0.1:8888/api/auth/callback`

### JaCoCo Coverage Gates
- Minimum instruction coverage is set to `0.53` in `pom.xml`.
- If coverage drops, update the threshold or add tests—don't disable the gate.

### Discogs Rate Limiting
- Discogs API has rate limits. The app uses caching and backoff.
- Treat `429`/`5xx` as transient; retry with backoff.

---

## 📝 Task Management Protocol

1. **Initialize**: Write the plan to `tasks/todo.md`.
2. **Execute**: Mark items `[x]` as you complete them.
3. **Summarize**: When finished, provide a high-level summary of changes.
4. **Lesson Capture**: If a bug was found or a mistake was made, document the **Fix** and the **Prevention Rule** in `tasks/lessons.md`.
