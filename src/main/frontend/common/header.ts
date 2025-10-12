export async function injectHeader() {
    const container = document.getElementById("header");
    if (!container) return;
    try {
        const res = await fetch("/common/header.html", { cache: "no-cache" });
        if (!res.ok) {
            throw new Error("HTTP " + res.status);
        }
        container.innerHTML = await res.text();

        // aktiven Link markieren
        const path = location.pathname.toLowerCase();
        const links = container.querySelectorAll("a[href]");
        links.forEach((node) => {
            const a = node as HTMLAnchorElement;
            const hrefPath = a.pathname.toLowerCase();
            if (
                hrefPath === path ||
                (path === "/" && (hrefPath === "/playlist.html" || hrefPath === "/playlist.html"))
            ) {
                a.classList.add("active");
            }
        });

        // Spotify-Login-Button erstellen
        const spotifyLoginButton = (loggedIn: boolean) => `
            <a href="#" id="spotify-login-btn"
               style="display: inline-flex; align-items: center; background-color: ${loggedIn ? '#A5A5A5' : '#1DB954'};
                      color: white; padding: 10px 20px; border-radius: 50px; text-decoration: none;
                      font-family: Arial, sans-serif; font-size: 16px; pointer-events: auto;"
               onmouseover="this.style.backgroundColor='${loggedIn ? '#FF0000' : '#17A74A'}';"
               onmouseout="this.style.backgroundColor='${loggedIn ? '#A5A5A5' : '#1DB954'}';">
                <img src="https://storage.googleapis.com/pr-newsroom-wp/1/2018/11/Spotify_Logo_RGB_White.png"
                     alt="Spotify Logo" style="height: 20px; margin-right: 10px;">
                ${loggedIn ? 'Abmelden' : 'Log in with Spotify'}
            </a>
        `;

        const updateSpotifyButton = (container: HTMLElement, loggedIn: boolean) => {
            const existingBtn = container.querySelector('#spotify-login-btn');
            if (existingBtn) existingBtn.remove();

            const navRight = container.querySelector('.navigation.navigation-right');
            if (navRight) {
                navRight.insertAdjacentHTML('beforebegin', spotifyLoginButton(loggedIn));
            }

            const spotifyBtn = container.querySelector('#spotify-login-btn') as HTMLAnchorElement;
            if (spotifyBtn) {
                spotifyBtn.addEventListener('click', async (event) => {
                    event.preventDefault();
                    if (loggedIn) {
                        // Logout-Funktion
                        try {
                            const r = await fetch('/api/auth/logout', { method: 'POST' });
                            if (!r.ok && r.status !== 204) throw new Error('HTTP ' + r.status);
                        } catch {}
                        updateSpotifyButton(container, false);
                    } else {
                        // Login-Funktion
                        try {
                            const r = await fetch('/api/auth/login', { method: 'POST' });
                            if (!r.ok) throw new Error('HTTP ' + r.status);
                            const data = await r.json();
                            const url: string | undefined = data?.authorizeUrl;
                            if (url) {
                                window.open(url, '_blank');
                                // kleines Polling für den Status
                                const start = Date.now();
                                const poll = setInterval(async () => {
                                    const statusRes = await fetch('/api/auth/status', { cache: 'no-cache' });
                                    if (statusRes.ok) {
                                        const statusData = await statusRes.json();
                                        if (statusData?.loggedIn) {
                                            updateSpotifyButton(container, true);
                                            clearInterval(poll);
                                        }
                                    }
                                    // 2 Minuten Limit
                                    if (Date.now() - start > 120000) clearInterval(poll);
                                }, 1500);
                            }
                        } catch (e) {
                            alert('Login konnte nicht gestartet werden: ' + (e instanceof Error ? e.message : String(e)));
                        }
                    }
                });
            }
        };

        // initial Spotify-Button laden
        updateSpotifyButton(container, false);

    } catch (e) {
        console.error("Header laden fehlgeschlagen:", e);
    }
}