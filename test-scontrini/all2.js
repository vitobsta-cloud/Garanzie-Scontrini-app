const fs = require("fs");
const s = fs.readFileSync("/home/claude/GaranzieScontriniAndroid/app/src/main/assets/www/index.html","utf8");
const a = s.indexOf("const NEGOZI_NOTI"), b = s.indexOf("/* Claude (facoltativo) */");
global.todayISO = () => "2026-11-20";
eval(s.slice(a, b).replace(/^const /gm, "var ").replace(/^function (\w+)/gm, "global.$1 = function $1"));
const ni = s.indexOf("const nomeBello"); eval(s.slice(ni, s.indexOf("\n", ni)).replace(/^const /, "var "));
const d = JSON.parse(fs.readFileSync(process.env.F||"/mnt/user-data/uploads/85bbd521-prova_lettura_2026-11-20_1.json","utf8"));
for(const r of d.risultati){ const p = parseReceipt(r.testo || ""); const nome = p.nomeSuggerito ? nomeBello(p.nomeSuggerito) : nomeDaArticoli(p.articoli);
  console.log(r.cartella.padEnd(50).slice(0,50), "|", (p.negozio||"").slice(0,14).padEnd(14), "|", (p.dataAcquisto||"").padEnd(10), "|", String(p.prezzo).padEnd(7), "|", nome); }
