const CACHE = "localizafamilia-v5";
const ARQUIVOS = [
  "./",
  "./index.html",
  "./manifest.json",
  "./icon.svg",
  "./LocalizaFamilia-v1.0.apk"
];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ARQUIVOS)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((chaves) =>
      Promise.all(chaves.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  if (e.request.method !== "GET") return;
  e.respondWith(
    caches.match(e.request).then((emCache) => {
      const rede = fetch(e.request)
        .then((resposta) => {
          if (resposta && resposta.status === 200 && e.request.url.startsWith(self.location.origin)) {
            const copia = resposta.clone();
            caches.open(CACHE).then((c) => c.put(e.request, copia));
          }
          return resposta;
        })
        .catch(() => emCache);
      return emCache || rede;
    })
  );
});