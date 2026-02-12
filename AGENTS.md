# AGENTS.md — Zemni (AI Orchestration & Guidelines)

You are an expert Full-Stack Engineer working on **Zemni**, a Next.js 14 (App Router) app for processing lecture PDFs into exam-oriented summaries and flashcards.

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
- **Proof of Work**: Never mark a task done without proving it works (npm run build, tests, logs, or UI verification).
- **The Elegance Check**: For complex changes, pause and ask: "Is there a more elegant way?" 
- **Bug Fixing**: Fix bugs autonomously. Point at logs/errors, then resolve without hand-holding.

---

## 🛠 Tech Stack & Commands

| Tool | Usage |
| :--- | :--- |
| **Framework** | Next.js 14 (App Router), TypeScript |
| **Backend** | Convex (Database/Mutations/Queries) |
| **Auth** | Clerk (`components/auth/ClerkWrapper.tsx`) |
| **Styling** | Tailwind CSS + `app/globals.css` |
| **AI** | OpenRouter (Config in `config/`) |
| **Errors** | Sentry (`lib/error-tracking.ts`) |

**Executable Commands:**
- `npm run dev` (Runs on `http://localhost:3420`)
- `npm run build` / `npm run start`
- `npx tsc --noEmit` (Type check)
- `npx vitest run [path]` (Test single file)

---

## 📂 Project Structure & Exports

- `app/api/`: Server-only endpoints. **Never call OpenRouter/Notion from the client.**
- `app/settings/`: Tab-based settings (Preferred over Modals).
- `components/features/`: Feature logic. **Must export via `index.ts`.**
- `components/ui/`: Atomic UI components. **Must export via `index.ts`.**
- `hooks/`: Main app state (e.g., `useUIState`, `useHistoryManagement`).
- `lib/handlers/`: Complex orchestration logic (e.g., `summary-actions.ts`).
- `tasks/`: **Your Workspace** (`todo.md`, `lessons.md`).

---

## 🚫 Boundaries & Anti-Patterns

### ✅ Always Do
- Use **Small, focused changes** with minimal diffs.
- Maintain a **1-100 scale** for all benchmarks.
- Use **Server-side logic** for API calls (Notion, OpenRouter).
- Ensure `subscription_tier` is present in all model JSONs.
- If a UI version badge is shown (e.g., `.hero-logo-wrap::after`), increment its numeric version when that design is changed.

### 🚫 Never Do (Anti-Patterns)
- **AI-Slop Design**: Avoid excessive gradients and unnecessary container nesting. Keep it clean and flat.
- **Unrequested Refactors**: No large-scale directory moves or global reformatting.
- **Client-Side Secrets**: Never log or expose `.env` variables or PII.
- **Duplicate Logic**: Check `lib/utils` or `types/` before creating new helpers.
- **Framework Bloat**: Do not add new styling libraries or frameworks.

---

## ⚠️ Known Issues & Workarounds (MUST READ)

### Clerk Hydration/SSR Issues
- **Symptoms**: "SignedIn can only be used within ClerkProvider" errors during Hot Reload.
- **The Fix**: Do not try to solve this with longer delays.
- **Pattern**: Use `ClerkWrapper.tsx` (has a 2s delay) and `ClerkErrorBoundary`.
- **Status**: These errors are expected in Dev during Hot Reload; do not attempt "fixes" that change the Provider logic.

### Model Configurations
- `config/openrouter-models.json` is your source of truth for pricing/availability.
- If a model is unavailable, `ModelsTab.tsx` should trigger an upgrade state based on `subscription_tier`.

---

## 📝 Task Management Protocol

1. **Initialize**: Write the plan to `tasks/todo.md`.
2. **Execute**: Mark items `[x]` as you complete them.
3. **Summarize**: When finished, provide a high-level summary of changes.
4. **Lesson Capture**: If a bug was found or a mistake was made, document the **Fix** and the **Prevention Rule** in `tasks/lessons.md`.
