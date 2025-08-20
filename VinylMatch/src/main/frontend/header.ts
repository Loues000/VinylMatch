export async function injectHeader() {
    const container = document.getElementById("header");
    if (!container) return;
    try { const res = await fetch("/common/header.html", { cache: "no-cache" });
        if (!res.ok) {
            throw new Error("HTTP " + res.status);
        }
        const html = await res.text();
        container.innerHTML = html;

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

} catch (e) { console.error("Header laden fehlgeschlagen:", e); } }