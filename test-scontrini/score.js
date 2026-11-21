const fs = require("fs");
const s = fs.readFileSync(process.env.APP || "/home/claude/GaranzieScontriniAndroid/app/src/main/assets/www/index.html","utf8");
const a = s.indexOf("const NEGOZI_NOTI"), b = s.indexOf("/* Lettura online gratuita");
global.todayISO = () => "2026-11-20";
eval(s.slice(a, b).replace(/^const /gm, "var ").replace(/^function (\w+)/gm, "global.$1 = function $1"));
const ni = s.indexOf("const nomeBello"); eval(s.slice(ni, s.indexOf("\n", ni)).replace(/^const /, "var "));
const files = (process.env.F?[process.env.F]:null) || ["/mnt/user-data/uploads/85bbd521-prova_lettura_2026-11-20_1.json", "/mnt/user-data/uploads/2fa00742-prova_lettura_2026-11-20.json"];
const seen = new Set(), rs = [];
for(const f of files) for(const r of JSON.parse(fs.readFileSync(f,"utf8")).risultati) if(!seen.has(r.cartella)){ seen.add(r.cartella); rs.push(r); }
const norm = t => String(t||"").normalize("NFD").replace(/[̀-ͯ]/g,"").toLowerCase().replace(/[^a-z0-9]/g,"");
const stop = new Set("della delle degli nuovo grande piccolo silvia manuel mamma laura cucina giardino campo vito condominio ernesto adele terracina".split(" "));
let N=[0,0], D=[0,0], M=[0,0]; const bad = [];
for(const r of rs){
  const p = parseReceipt(r.testo || ""); const nome = p.nomeSuggerito ? nomeBello(p.nomeSuggerito) : nomeDaArticoli(p.articoli);
  const parts = r.cartella.split("/"), last = parts[parts.length-1];
  if(parts.length > 1 && !/^(gasatore|xiaomi|onlinestore|riepilogo)/i.test(parts[0])){ N[1]++; const ok = !!p.negozio && (norm(parts[0]).includes(norm(p.negozio).slice(0,6)) || norm(p.negozio).includes(norm(parts[0]).slice(0,6))); if(ok) N[0]++; else bad.push(`NEG  ${r.cartella} → "${p.negozio}"`); }
  const dm = last.match(/(\d{4})_(\d{2})_(\d{2})/);
  if(dm && +dm[2] <= 12 && +dm[3] <= 31 && dm[1] < "2027"){ D[1]++; const e = `${dm[1]}-${dm[2]}-${dm[3]}`; if(p.dataAcquisto === e) D[0]++; else bad.push(`DATA ${r.cartella} → "${p.dataAcquisto}" atteso ${e}`); }
  const words = last.toLowerCase().replace(/[_\d]+/g," ").split(/\s+/).filter(w => w.length >= 5 && !stop.has(w));
  if(words.length){ M[1]++; const n = norm(nome); const ok = words.some(w => n.includes(norm(w).slice(0,5))); if(ok) M[0]++; else bad.push(`NOME ${r.cartella} → "${nome}"`); }
}
if(process.argv[2]) bad.forEach(x => console.log(x));
console.log(`Scontrini ${rs.length} | negozio ${N[0]}/${N[1]} | data ${D[0]}/${D[1]} | nome (parola della cartella) ${M[0]}/${M[1]}`);
