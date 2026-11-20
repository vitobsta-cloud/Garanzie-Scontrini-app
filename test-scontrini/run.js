const fs = require("fs");
const s = fs.readFileSync(process.env.APP || "../app/src/main/assets/www/index.html","utf8");
const a = s.indexOf("const NEGOZI_NOTI"), b = s.indexOf("/* Claude (facoltativo) */");
global.todayISO = () => "2026-11-20";
eval(s.slice(a, b).replace(/^const /gm, "var ").replace(/^function (\w+)/gm, "global.$1 = function $1"));
const ni = s.indexOf("const nomeBello"); eval(s.slice(ni, s.indexOf("\n", ni)).replace(/^const /, "var "));
const casi = require("./casi.js").filter(Boolean).concat(JSON.parse(fs.readFileSync(__dirname + "/casi_reali.json", "utf8")));
let ok = 0, tot = 0;
for(const c of casi){
  const r = parseReceipt(c.t); r.nome = r.nomeSuggerito ? nomeBello(r.nomeSuggerito) : nomeDaArticoli(r.articoli);
  const got = {negozio:r.negozio, data:r.dataAcquisto, prezzo:r.prezzo, numero:r.numeroScontrino, pagamento:r.tipoPagamento, nome:r.nome};
  const errs = [];
  for(const k of Object.keys(c.exp)){ tot++; const e = c.exp[k], g = got[k];
    const same = typeof e === "number" ? Math.abs((g||0)-e) < 0.01 : (c.id.startsWith("REALE") && k === "nome") ? String(g||"").toLowerCase().includes(e) : String(g||"").toLowerCase() === String(e).toLowerCase();
    if(same) ok++; else errs.push(`${k}: atteso "${e}" ottenuto "${g}"`); }
  console.log((errs.length ? "❌ " : "✅ ") + c.id); errs.forEach(e => console.log("     " + e));
}
console.log(`\nCampi corretti: ${ok}/${tot}`);
