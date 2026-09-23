const DATABASE_URL = process.env.DATABASE_URL || "";
const TOKEN = process.env.FIREBASE_TOKEN || "";
const RETENTION_DAYS = Number(process.env.RETENTION_DAYS || 30);
const DRY_RUN = process.env.DRY_RUN === "1";

if (!DATABASE_URL || !TOKEN) {
  console.error("Faltam DATABASE_URL e/ou FIREBASE_TOKEN");
  process.exit(1);
}

const cutoff = Date.now() - RETENTION_DAYS * 86400000;
const url = (p) => `${DATABASE_URL.replace(/\/+$/, "")}/${p}.json?access_token=${encodeURIComponent(TOKEN)}`;

async function get(path) {
  const r = await fetch(url(path));
  if (!r.ok) throw new Error(`GET ${path} -> ${r.status}`);
  return r.json();
}

async function patch(path, body) {
  const r = await fetch(url(path), {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`PATCH ${path} -> ${r.status}`);
}

const membros = await get("historico");
if (!membros || typeof membros !== "object") {
  console.log("historico vazio, nada a fazer");
  process.exit(0);
}

let removidos = 0;
for (const m of Object.keys(membros)) {
  const dados = await get(`historico/${encodeURIComponent(m)}`);
  if (!dados || typeof dados !== "object") continue;

  const antigos = {};
  for (const chave of Object.keys(dados)) {
    const ts = Number(chave);
    if (!Number.isFinite(ts) || ts >= cutoff) continue;
    antigos[chave] = null;
  }

  const n = Object.keys(antigos).length;
  if (n === 0) {
    console.log(`${m}: nada antigo`);
    continue;
  }
  if (n > 500) {
    console.warn(`${m}: ${n} entradas antigas (acima do limite de 500) - pular para nao arriscar`);
    continue;
  }
  console.log(`${m}: removendo ${n} entrada(s)`);
  if (!DRY_RUN) await patch(`historico/${encodeURIComponent(m)}`, antigos);
  removidos += n;
}

console.log(`Concluído. Total ${DRY_RUN ? "(dry-run) " : ""}removido: ${removidos}`);