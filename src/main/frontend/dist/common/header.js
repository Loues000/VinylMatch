export async function injectHeader() {
    const container = document.getElementById("header");
    if (!container)
        return;
    try {
        const res = await fetch("/common/header.html", { cache: "no-cache" });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        container.innerHTML = await res.text();
        // Aktiver Link markieren
        const path = location.pathname.toLowerCase();
        container.querySelectorAll("a[href]").forEach((a) => {
            const hrefPath = a.pathname.toLowerCase();
            if (hrefPath === path || (path === "/" && hrefPath === "/playlist.html")) {
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
                <img src="https://storage.googleapis.com/pr-newsroom-wp/1/2018/11/Spotify_Logo_RGB_White.png" 
                     alt="Spotify Logo">
                ${loggedIn ? "Abmelden" : "Log in with Spotify"}
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
            // Event hinzufügen
            const spotifyBtn = container.querySelector("#spotify-login-btn");
            if (spotifyBtn) {
                spotifyBtn.addEventListener("click", async (event) => {
                    event.preventDefault();
                    if (loggedIn) {
                        // Logout
                        try {
                            const r = await fetch("/api/auth/logout", { method: "POST" });
                            if (!r.ok && r.status !== 204)
                                throw new Error("HTTP " + r.status);
                        }
                        catch { }
                        updateSpotifyButton(false);
                    }
                    else {
                        // Login
                        try {
                            const r = await fetch("/api/auth/login", { method: "POST" });
                            if (!r.ok)
                                throw new Error("HTTP " + r.status);
                            const data = await r.json();
                            const url = data?.authorizeUrl;
                            if (url) {
                                window.open(url, "_blank");
                                // Polling bis max. 2 min
                                const start = Date.now();
                                const poll = setInterval(async () => {
                                    const statusRes = await fetch("/api/auth/status", { cache: "no-cache" });
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
                            alert("Login konnte nicht gestartet werden: " + (e instanceof Error ? e.message : String(e)));
                        }
                    }
                });
            }
        };
        // Initial mit nicht-eingeloggt starten
        updateSpotifyButton(false);
    }
    catch (e) {
        console.error("Header laden fehlgeschlagen:", e);
    }
}