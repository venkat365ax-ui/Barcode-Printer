/* StockTag service worker: works offline, updates in the background. */
const VERSION = "lp-1.7.0";
const CORE = [
  "./",
  "./index.html",
  "./manifest.webmanifest",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/maskable-192.png",
  "./icons/maskable-512.png",
  "./icons/apple-touch-icon.png",
  "./icons/favicon-64.png"
];
// libraries the app loads on demand (scanner, text reader, Excel reader)
const CDN = /^https:\/\/cdn\.jsdelivr\.net\//;

self.addEventListener("install", e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(CORE)));
});

self.addEventListener("activate", e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== VERSION && k !== "lp-cdn").map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("message", e => { if (e.data === "skipWaiting") self.skipWaiting(); });

self.addEventListener("fetch", e => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);

  // App page: network first (always the newest version), cached copy when offline
  if (req.mode === "navigate" || (url.origin === location.origin && url.pathname.endsWith(".html"))){
    e.respondWith(
      fetch(req).then(res => {
        const copy = res.clone();
        caches.open(VERSION).then(c => c.put("./index.html", copy));
        return res;
      }).catch(() => caches.match("./index.html").then(r => r || caches.match("./")))
    );
    return;
  }

  // Own files (icons, manifest): cache first
  if (url.origin === location.origin){
    e.respondWith(caches.match(req).then(r => r || fetch(req).then(res => {
      const copy = res.clone(); caches.open(VERSION).then(c => c.put(req, copy)); return res;
    })));
    return;
  }

  // CDN libraries: cache after first use so they work offline later
  if (CDN.test(req.url)){
    e.respondWith(caches.open("lp-cdn").then(c => c.match(req).then(r => r || fetch(req).then(res => {
      if (res.ok || res.type === "opaque") c.put(req, res.clone());
      return res;
    }))));
  }
});
