const fs=require("fs");const {execSync}=require("child_process");
const F=process.env.F;const d=JSON.parse(fs.readFileSync(F,"utf8")).risultati;
const loc=execSync("node all2.js",{env:{...process.env,F}}).toString().trim().split("\n");
let n=0;d.forEach((r,i)=>{const L=loc[i].split("|").map(x=>x.trim());const l=r.letti;
 const ph=[l.negozio||"",l.data||"",String(l.prezzo),l.nome||""];const lc=[L[1],L[2],L[3],L[4]];
 if(ph[3]!==lc[3]||ph[0].slice(0,14)!==lc[0]){n++;console.log(r.cartella.slice(0,40),"\n  tel:",ph.join(" | "),"\n  loc:",lc.join(" | "));}});console.log("diversi",n);
