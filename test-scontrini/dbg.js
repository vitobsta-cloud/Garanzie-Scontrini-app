const fs = require("fs");
const s = fs.readFileSync("/home/claude/GaranzieScontriniAndroid/app/src/main/assets/www/index.html","utf8");
const a = s.indexOf("const NEGOZI_NOTI"), b = s.indexOf("/* Claude (facoltativo) */");
global.todayISO = () => "2026-11-20";
eval(s.slice(a, b).replace(/^const /gm, "var ").replace(/^function (\w+)/gm, "global.$1 = function $1"));
const d = JSON.parse(fs.readFileSync(process.env.F||"/mnt/user-data/uploads/aea2c4a3-prova_lettura_2026-11-20.json","utf8")).risultati;
for(const k of process.argv.slice(2)){ const r=d.find(x=>x.cartella.includes(k)); const p=parseReceipt(r.testo); console.log("==",r.cartella,p.negozio,p.dataAcquisto,p.prezzo,p.nomeSuggerito||""); p.articoli.forEach(x=>console.log("   ",x.prezzo,x.quantita,x.descrizione)); }
