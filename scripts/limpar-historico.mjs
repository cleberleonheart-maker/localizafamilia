import fs from "node:fs";

const ARQ = process.argv[2];
const RETENTION_DAYS = Number(process.env.RETENTION_DAYS || 30);
const DRY_RUN = process.env.DRY_RUN === "1";

if (!ARQ) {
  console.error("Uso: node limpar-historico.mjs <export.json>");
  process.exit(1);
}

const cutoff = Date.now() - RETENTION_DAYS * 86400000;
const raiz = JSON.parse(fs.readFileSync(ARQ, "utf8"));

let total = 0;
for (const m of Object.keys(raiz)) {
  const dados = raiz[m];
  if (!dados || typeof dados !== "object") continue;
  const antigos = Object.keys(dados).filter((c) => {
    const ts = Number(c);
    return Number.isFinite(ts) && ts < cutoff;
  });
  if (antigos.length === 0) {
    console.log(`${m}: nada antigo`);
    continue;
  }
  if (antigos.length > 500) {
    console.warn(`${m}: ${antigos.length} antigas (limite 500) - pular`);
    continue;
  }
  for (const a of antigos) {
    console.log(`/historico/${m}/${a}`);
    total++;
  }
}
console.error(`Antigas a remover: ${total}${DRY_RUN ? " (dry-run, sem remover)" : ""}`);
if (total === 0) process.exit(0);