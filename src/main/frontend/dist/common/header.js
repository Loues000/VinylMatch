export async function injectHeader() {
    const container = document.getElementById("header");
    if (!container)
        return;
    const THEME_KEY = "vinylmatch_theme";
    const resolveEffectiveTheme = () => {
        const explicit = document.documentElement.dataset.theme;
        if (explicit === "light" || explicit === "dark")
            return explicit;
        return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    };
    const applyTheme = (theme) => {
        if (theme === "light" || theme === "dark") {
            document.documentElement.dataset.theme = theme;
        }
        else {
            delete document.documentElement.dataset.theme;
        }
    };
    const initThemeToggle = () => {
        const btn = container.querySelector("#theme-toggle");
        if (!(btn instanceof HTMLButtonElement))
            return;
        const render = () => {
            const current = resolveEffectiveTheme();
            btn.textContent = current === "dark" ? "Light" : "Dark";
            btn.setAttribute("aria-pressed", String(current === "dark"));
            btn.title = current === "dark" ? "Switch to light mode" : "Switch to dark mode";
        };
        btn.addEventListener("click", () => {
            const current = resolveEffectiveTheme();
            const next = current === "dark" ? "light" : "dark";
            try {
                localStorage.setItem(THEME_KEY, next);
            }
            catch (_a) {
                /* ignore */
            }
            applyTheme(next);
            render();
        });
        render();
    };
    const emitAuthState = (loggedIn) => {
        try {
            window.dispatchEvent(new CustomEvent("vm:auth-state", { detail: { loggedIn } }));
        }
        catch (_a) {
            /* ignore */
        }
    };
    try {
        const res = await fetch("/common/header.html", { cache: "no-cache" });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        container.innerHTML = await res.text();
        initThemeToggle();
        // Aktiver Link markieren
        const path = location.pathname.toLowerCase();
        container.querySelectorAll("a[href]").forEach((a) => {
            const hrefPath = a.pathname.toLowerCase();
            const normalizedHref = hrefPath === "/" ? "/home.html" : hrefPath;
            const normalizedPath = path === "/" ? "/home.html" : path;
            if (normalizedHref === normalizedPath) {
                a.classList.add("active");
            }
        });
        // Spotify-Login-Button erzeugen
        const createSpotifyButton = (loggedIn) => {
            const li = document.createElement("li");
            const btn = document.createElement("a");
            btn.id = "spotify-login-btn";
            btn.href = "#";
            btn.className = loggedIn ? "spotify-btn logged-in" : "spotify-btn";
            btn.innerHTML = `
                <img src="/assets/spotify-logo.svg" alt="Spotify">
                ${loggedIn ? "Log out" : "Log in with Spotify"}
            `;
            li.appendChild(btn);
            return li;
        };
        const updateSpotifyButton = (loggedIn) => {
            const navRight = container.querySelector(".navigation.navigation-right");
            if (!navRight)
                return;
            // Remove old
            const oldBtnLi = navRight.querySelector("#spotify-login-btn")?.parentElement;
            if (oldBtnLi)
                oldBtnLi.remove();
            // Add new
            navRight.appendChild(createSpotifyButton(loggedIn));
            emitAuthState(!!loggedIn);
            // Event hinzufügen
            const spotifyBtn = container.querySelector("#spotify-login-btn");
            if (spotifyBtn) {
                spotifyBtn.addEventListener("click", async (event) => {
                    event.preventDefault();
                    if (loggedIn) {
                        // Logout
                        try {
                            const r = await fetch("/api/auth/logout", { method: "POST", credentials: "include" });
                            if (!r.ok && r.status !== 204)
                                throw new Error("HTTP " + r.status);
                        }
                        catch (_a) {
                        }
                        updateSpotifyButton(false);
                    }
                    else {
                        // Login
                        try {
                            const r = await fetch("/api/auth/login", { method: "POST", credentials: "include" });
                            if (!r.ok)
                                throw new Error("HTTP " + r.status);
                            const data = await r.json();
                            const url = data?.authorizeUrl;
                            if (url) {
                                window.open(url, "_blank");
                                // Poll up to 2 minutes for login completion
                                const start = Date.now();
                                const poll = setInterval(async () => {
                                    const statusRes = await fetch("/api/auth/status", { cache: "no-cache", credentials: "include" });
                                    if (statusRes.ok) {
                                        const statusData = await statusRes.json();
                                        if (statusData?.loggedIn) {
                                            updateSpotifyButton(true);
                                            clearInterval(poll);
                                        }
                                    }
                                    if (Date.now() - start > 120000)
                                        clearInterval(poll);
                                }, 1500);
                            }
                        }
                        catch (e) {
                            alert("Login could not be started: " + (e instanceof Error ? e.message : String(e)));
                        }
                    }
                });
            }
        };
        // Initial mit nicht-eingeloggt starten
        updateSpotifyButton(false);
        try {
            const statusRes = await fetch("/api/auth/status", { cache: "no-cache", credentials: "include" });
            if (statusRes.ok) {
                const status = await statusRes.json();
                if (typeof status?.loggedIn === "boolean") {
                    updateSpotifyButton(status.loggedIn);
                }
            }
        }
        catch (e) {
            console.warn("Failed to fetch auth status:", e);
        }
    }
    catch (e) {
        console.error("Failed to load header:", e);
    }
}
