# Lessons Learned

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
