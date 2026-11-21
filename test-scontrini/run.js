const fs = require("fs");
const s = fs.readFileSync("/home/claude/GaranzieScontriniAndroid/app/src/main/assets/www/index.html","utf8");
const a = s.indexOf("const NEGOZI_NOTI"), b = s.indexOf("/* Lettura online gratuita");
global.todayISO = () => "2026-11-20";
eval(s.slice(a, b).replace(/^const /gm, "var ").replace(/^function (\w+)/gm, "global.$1 = function $1"));
const casi = require("./casi.js").filter(Boolean); let ok = 0, tot = 0;
for(const c of casi){
  const r = parseReceipt(c.t); r.nome = nomeDaArticoli(r.articoli);
  const somma = Math.round(r.articoli.reduce((x,a)=>x+(a.prezzo||0),0)*100)/100;
  const got = {negozio:r.negozio, data:r.dataAcquisto, prezzo:r.prezzo, numero:r.numeroScontrino, pagamento:r.tipoPagamento, nome:r.nome};
  const errs = [];
  for(const k of Object.keys(c.exp)){ tot++; const e = c.exp[k], g = got[k]; const same = typeof e === "number" ? Math.abs((g||0)-e) < 0.01 : String(g||"").toLowerCase() === String(e).toLowerCase(); if(same) ok++; else errs.push(`${k}: atteso "${e}" ottenuto "${g}"`); }
  console.log((errs.length ? "❌ " : "✅ ") + c.id + `  [articoli ${r.articoli.length}, somma ${somma}]`);
  errs.forEach(e => console.log("     " + e));
  if(process.argv[2]) console.log("     ", JSON.stringify(r.articoli));
}
console.log(`\nCampi corretti: ${ok}/${tot}`);
