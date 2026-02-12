# VinylMatch Improvement Release Notes

Date: 2026-02-11

## Phase 1 - Security and docs cleanup
- Removed plaintext credential examples from Docker startup docs and replaced with safe placeholders.
- Shifted docs to Java/Maven-first workflow and positioned Docker as optional.
- Added markdown docs secret scanner: `scripts/check-doc-secrets.ps1`.
- Added explicit guidance to revoke/rotate credentials if previously exposed.

## Phase 2 - Core UX polish (Home/Playlist)
- Replaced blocking alert popups on Home/Playlist paths with inline status messaging.
- Added clearer loading/success/error status feedback for playlist load and Discogs actions.
- Improved home input validation UX (`aria-invalid`, focus return, busy state on submit).
- Added status feedback for playlist view mode switching and Discogs drawer interactions.

## Phase 3 - Accessibility and responsive updates
- Added focus management/trapping for Home and Playlist side drawers.
- Added keyboard navigation for Home sidebar tabs (left/right/home/end).
- Improved focus-visible outlines for key interactive controls.
- Increased tap targets for pagination and drawer controls.
- Added status color tokens in `styles/base.css` for better theme consistency and contrast.

## Phase 4 - CI/CD Docker removal and deploy modernization
- Removed Docker image build job from `.github/workflows/ci.yml`.
- Replaced deploy workflow with artifact-first JAR pipeline in `.github/workflows/deploy.yml`.
- Added optional SSH-based staging/production deploy jobs (guarded by secrets).
- Replaced `scripts/deploy-staging.sh` with no-Docker JAR deployment helper.

## Phase 5 - Regression checks and verification
- Ran docs secret scan successfully.
- Ran Maven tests (with `-Djacoco.skip=true`) to validate code paths.
- Built release JAR successfully (`-DskipTests -Djacoco.skip=true`).
- Ran runtime smoke checks for `/api/health/simple`, `/api/auth/status`, `home.html`, and `playlist.html`.

## Follow-up
- Keep Docker compose files for legacy/optional use only.
- Optional: tighten or refactor JaCoCo configuration to avoid coverage gate friction during packaging.
