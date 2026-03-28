# Lessons Learned

## 2026-03-28
- When a frontend boot regression appears to affect all JS-driven interactions, verify it once in a fresh browser profile before changing app code; if the source tree works there, suspect stale cached static assets and harden cache headers or asset versioning instead of chasing a nonexistent runtime bug.
- For checked-in frontend `dist/` assets, verify every imported module is actually tracked with `git ls-files`; local builds can mask missing Git assets because untracked files still get copied into `target/frontend`, while clean CI/deploy checkouts will 404 those modules at runtime.

## 2026-03-27
- For CI-hosted OWASP Dependency-Check runs, always wire the NVD API key from environment config and set a non-fatal update fallback; shared builder IPs get rate-limited often enough that unauthenticated NVD refreshes are not a reliable deploy gate.
- For Java HTTP services deployed behind Railway or similar platforms, bind the listener explicitly to `0.0.0.0` instead of relying on the runtime default address family; a build can pass while the platform still returns `502 connection refused` if the process is not reachable on the expected interface.
- Before adding a new ignore rule, verify the path is not tracked with `git ls-files` or `git status`; broad patterns like `.github/` can silently hide real project files if they are already committed.

## 2026-03-06
- Before declaring a frontend diff commit-ready, run `git diff --check` and remove stray backup artifacts like `.bak` files so review cleanup is handled before commit prep.
- If a secret/config value is documented for `.env` or config-file use, route the consuming code through centralized `Config` loading rather than raw `System.getenv`, or local configuration silently breaks.
- For encrypted Redis-backed sessions, never use a per-process random/time-based fallback key; the fallback must be stable across restarts or persisted sessions will force unnecessary re-logins.
- For per-item async match UI, never use nearly identical labels like `Search` and `Searching...`; badge copy must distinguish waiting, ready-to-open results, and manual-search-needed states, and action buttons should expose the same state in their tooltips/labels.
- When a third-party integration can fail for policy reasons (not just auth/network), preserve the upstream exception details through the backend boundary and map them to a specific API error code; otherwise the frontend can only show a misleading generic failure or a bad "log in" hint.

## 2026-02-11
- When writing setup docs, never include real credentials/tokens in command examples.
- Prefer secure placeholders and explicit rotation instructions if a leak is found.
- Replace blocking browser alerts with inline status messages on core flows to avoid interrupting user actions.
- Add an automated docs secret scan script so secret hygiene is verifiable and repeatable.
- For slide-in drawers, always pair visibility changes with keyboard focus management and inert/aria-hidden state.
- When JaCoCo gates block verification, run explicit test/build commands with documented flags and record why.

## 2026-02-12
- For paginated third-party lists, never drop entries during normalization just because optional link metadata is missing; preserve the row and derive a fallback link from stable IDs when possible, or pagination totals become misleading and pages appear "empty".
- For homepage route variants like `/1|/2|/3`, normalize trailing slashes server-side before file resolution to avoid hidden 404 edge cases.
- If legacy encoding characters make patch context unstable, rewrite the file in one pass and then re-run focused diff checks to verify no behavior was dropped.
- During UI redesigns, run a targeted guideline pass (forms, image sizing, motion/accessibility hooks) before finalizing to avoid avoidable compliance regressions.
- If variant switching is meant to be URL-driven, do not add in-page switch controls even if they are convenient for previewing.
- For external match APIs, treat `429`/`5xx` as transient: retry with backoff and avoid caching fallback search URLs from transient failures, or low-quality matches will persist across pages.
- For public read access without user login, resolve tokens in layered order (user token first, app token fallback) so private resources remain protected while anonymous public flows keep working.
- In PowerShell, quote Maven system properties containing dots (for example `"-Djacoco.skip=true"`) to avoid argument parsing issues.
- When promoting one UI variant to default, keep legacy variants on deep static URLs and remove shortcut route switching in both frontend boot code and server path mapping.
- For special CTA links inside shared nav bars (like Spotify auth), exclude them from broad `.navigation a` selectors and style them via dedicated selectors to prevent theme/variant color bleed.
- If an icon is expected to be reusable across components/themes, store it as a dedicated file in `design/` and reference it by path instead of embedding long inline SVG strings.
- When editing JS with PowerShell `-replace`, avoid literal `` `r`n `` insertion in single-quoted replacement strings; use `apply_patch` for multiline blocks or verify output lines immediately.
- When UI state uses a filtered `tracks` array, do not overwrite `totalTracks` with `tracks.length`; keep the API-provided total for headers and recents.
- For optional action icons, render icons only when both light/dark assets exist and keep a visible text fallback for incomplete vendor setups.
- For token-based third-party features (like Discogs wishlist), persist the user token locally (opt-in), auto-restore the server session on page load, and provide in-page status messages so users can recover without guessing.
- For popup-based OAuth flows, always close the loop with both `postMessage` callback signaling and a status-poll fallback so auth completion is detected even if popup messaging is blocked.
- For OAuth callback HTML pages, escape all dynamic query/error text before rendering to avoid reflected script injection in popup status views.
- Keep JaCoCo thresholds explicit and realistic for the active baseline; otherwise all tests can pass while CI/build still fails on `jacoco:check`.
- For paged side panels, keep pagination state and controls in the same module as data fetching; otherwise UI arrows disappear when layout-only refactors happen.
- For idempotent add actions backed by third-party APIs (like Discogs wantlist), treat duplicate responses as success and reserve frontend-visible errors for true failures to avoid noisy 409 UX.
- Keep static fallback markup asset paths aligned with actual files (especially icons loaded before JS hydration), or users will see persistent 404 console errors even when dynamic rendering later corrects the UI.
- For paginated third-party panels, treat a sudden `total=0` + empty page on `page>1` as suspicious if prior total was non-zero; auto-fallback one page instead of resetting UI to an unusable empty `1/1` state.
- For OAuth request-token starts against third-party APIs, treat `429`/`5xx` and IO errors as transient with short retry/backoff; otherwise users see avoidable intermittent 500s on repeated connect attempts.
- During bulk selector cleanup, avoid global text replacement of `:root ` without block scoping; it can leave orphaned `{` blocks and break CSS syntax.
- For slow third-party enrichment flows (like Discogs matching), never `await` the full queue in primary UI actions (`load`, `load more`); render content first and run lookups in the background to keep interactions responsive.

## 2026-02-13
- When a page already loads a base stylesheet plus a variant stylesheet, keep the variant file focused on real overrides only; repeated base declarations should be removed first to reduce CSS safely.
- Treat `transition: all` as a guideline violation in UI CSS; explicitly list transitioned properties to avoid accidental motion/layout regressions.
- For mobile form fields that users type into (`input`, `textarea`), enforce `16px` font-size on narrow breakpoints to prevent iOS zoom and input friction.
- When closing a todo item that was implemented earlier, add explicit verification notes with file references so the checklist reflects shipped behavior instead of stale status.
- For frequent badge sync on large track lists, prefer a normalized `artist+album` local cache and treat network status checks as cache-miss fallback only.
- For cache-backed ownership badges, never let wishlist hydration overwrite an existing `owned` state, and keep cache data across transient logged-out checks so auto-login flows can still reuse local ownership state.
- If list/grid toggles rerender track cards, reapply local badge state immediately after render; otherwise ownership indicators appear to flicker/disappear until the next scheduled refresh.

## 2026-02-19
- When replacing text badges with icon-only indicators, always keep explicit `role="img"` and meaningful `aria-label`/`title` strings so the state remains accessible and debuggable.
- In PowerShell, avoid over-escaped quote patterns for `rg`; prefer `setAttribute(...)`/simple token searches when checking JS attributes to prevent false "file not found" lookup errors.
