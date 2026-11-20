const fs = require("fs");
const s = fs.readFileSync("/home/claude/GaranzieScontriniAndroid/app/src/main/assets/www/index.html","utf8");
const a = s.indexOf("const NEGOZI_NOTI"), b = s.indexOf("/* Claude (facoltativo) */");
global.todayISO = () => "2026-11-20";
eval(s.slice(a, b).replace(/^const /gm, "var ").replace(/^function (\w+)/gm, "global.$1 = function $1"));
const casi = JSON.parse(fs.readFileSync(__dirname + "/casi_reali.json", "utf8"));
for(const c of casi.filter(c => c.id.includes(process.argv[2]))){ const r = parseReceipt(c.t); console.log(c.id, r.prezzo, JSON.stringify(r.articoli)); }
if(process.argv[3]){ console.log(JSON.stringify(parseReceipt(process.argv[3]).articoli)); }
