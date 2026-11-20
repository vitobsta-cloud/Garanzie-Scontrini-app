// Testi "come li legge l'OCR del telefono" (righe spezzate, colonne separate, errori tipici)
module.exports = [
{ id:"Leroy Merlin carta", t:`LEROY MERLIN
Leroy Merlin Italia S.r.l.
Via Lombardia 12 - 25030 Roncadelle BS
P.IVA 11669080158
DOCUMENTO COMMERCIALE
di vendita o prestazione
DESCRIZIONE IVA Prezzo(€)
TRAPANO AVVITATORE 18V 22% 89,90
PUNTE HSS SET 10PZ 22% 12,50
TASSELLI NYLON 6MM 22% 4,20
x 2 2,10
TOTALE COMPLESSIVO 106,60
DI CUI IVA 19,22
Pagamento elettronico 106,60
Importo pagato 106,60
14/03/2025 17:22
DOCUMENTO N. 0412-0087`,
  exp:{negozio:"Leroy Merlin", data:"2025-03-14", prezzo:106.60, numero:"0412-0087", pagamento:"Carta", nome:"Trapano Avvitatore 18v"} },
{ id:"MediaWorld TV", t:`MEDIAWORLD
MediaMarket S.p.a. con socio unico
Via Bresciana 4 Castenedolo
DOCUMENTO COMMERCIALE di vendita
TV SAMSUNG 55" QE55Q60D
22%
649,00
CAVO HDMI 2.1 2M
22%
19,99
SUBTOTALE
668,99
TOTALE COMPLESSIVO
668,99
DI CUI IVA
120,64
PAGAMENTO ELETTRONICO
668,99
05-11-2024 10:41
DOC. N. 1203-0456`,
  exp:{negozio:"MediaWorld", data:"2024-11-05", prezzo:668.99, numero:"1203-0456", pagamento:"Carta", nome:"Tv Samsung 55\" Qe55q60d"} },
{ id:"Unieuro bancomat", t:`unieuro
UNIEURO S.P.A.
Piazza A. Volta 25 - 47121 Forlì
DOCUMENTO COMMERCIALE
LAVATRICE 9KG BEKO B5W 22% 399,00
SERVIZIO CONSEGNA 22% 29,90
TOTALE COMPLESSIVO €428,90
DI CUI IVA 77,34
PAGOBANCOMAT 428,90
29/08/2026 11:05
DOCUMENTO N. 0033-0092`,
  exp:{negozio:"Unieuro", data:"2026-08-29", prezzo:428.90, numero:"0033-0092", pagamento:"Bancomat", nome:"Lavatrice 9kg Beko B5w"} },
{ id:"Esselunga spesa", t:`esselunga
ESSELUNGA S.P.A.
VIA VITTORIO EMANUELE 1 BRESCIA
DOCUMENTO COMMERCIALE
DESCRIZIONE IVA PREZZO
LATTE PS 1L 4% 1,29
PANE COMUNE 4% 2,10
FRULLATORE IMMERSIONE 22% 24,90
BISCOTTI 10% 2,49
2 X 1,25
DETERSIVO 22% 5,99
TOTALE COMPLESSIVO 36,77
CONTANTI 50,00
RESTO 13,23
12-01-2026 18:30
DOCUMENTO N. 2101-3344`,
  exp:{negozio:"Esselunga", data:"2026-01-12", prezzo:36.77, numero:"2101-3344", pagamento:"Contanti", nome:"Frullatore Immersione"} },
{ id:"Bricoman colonne spostate", t:`BRICOMAN
Bricoman Italia Srl
Via Industriale 5 Brescia
DOCUMENTO COMMERCIALE
DESCRIZIONE IVA PREZZO(€)
PIEDINI LETTO 4PZ 22%
ANGOLARI ACCIAIO 22%
VITI LEGNO 200PZ 22%
19,90
9,50
5,00
TOTALE COMPLESSIVO 34,40
CARTA DI CREDITO 34,40
06/09/2026 10:12
DOCUMENTO N. 0215-0777`,
  exp:{negozio:"Bricoman", data:"2026-09-06", prezzo:34.40, numero:"0215-0777", pagamento:"Carta", nome:"Piedini Letto 4pz"} },
{ id:"Decathlon", t:`DECATHLON
Decathlon Italia S.r.l.
Brescia Elnos
SCONTRINO
BICI BAMBINO 16" 22% 149,99
CASCO BAMBINO 22% 19,99
SCONTO -10,00
TOTALE EURO 159,98
VISA ************1234 159,98
03.04.2025 15:01
DOC. N. 4567-0012`,
  exp:{negozio:"Decathlon", data:"2025-04-03", prezzo:159.98, numero:"4567-0012", pagamento:"Carta", nome:"Bici Bambino 16\""} },
{ id:"Lidl con O al posto di 0", t:`LIDL
Lidl Italia S.r.l. a socio unico
DESCRIZIONE IVA PREZZO(€)
COMPRESSORE PARKSIDE 22% 69,99
TUBO ARIA 1OM 22% 12,99
TOTALE COMPLESSIVO 82,98
DI CUI IVA 14,96
Pagamento contante 100,00
Resto 17,02
Importo pagato 82,98
10-06-2026 09:15
DOCUMENTO N. 1105-0021`,
  exp:{negozio:"Lidl", data:"2026-06-10", prezzo:82.98, numero:"1105-0021", pagamento:"Contanti", nome:"Compressore Parkside"} },
{ id:"Ikea", t:`IKEA
IKEA Italia Retail S.r.l.
Roncadelle
DOCUMENTO COMMERCIALE
BILLY LIBRERIA BIANCO 22% 59,00
RANARP LAMPADA 22% 29,99
TOTALE 88,99
CARTA DI DEBITO 88,99
21/02/2026 16:44
N. DOC. 0099-1234`,
  exp:{negozio:"Ikea", data:"2026-02-21", prezzo:88.99, numero:"0099-1234", pagamento:"Carta", nome:"Billy Libreria Bianco"} },
{ id:"Euronics con articolo su 2 righe", t:`EURONICS
Euronics Point
DOCUMENTO COMMERCIALE
ASPIRAPOLVERE SENZA FILI
DYSON V8 22% 299,00
TOTALE COMPLESSIVO 299,00
DI CUI IVA 53,92
PAGAMENTO ELETTRONICO 299,00
18/11/2025 12:00
DOCUMENTO N. 0007-0100`,
  exp:{negozio:"Euronics", data:"2025-11-18", prezzo:299.00, numero:"0007-0100", pagamento:"Carta", nome:"Aspirapolvere Senza Fili Dyson V8"} },
{ id:"Negozio sconosciuto", t:`FERRAMENTA ROSSI SNC
di Rossi Mario & C.
Via Garibaldi 3 - 25060 Collebeato BS
Tel. 030 1234567
DOCUMENTO COMMERCIALE
MOTOSEGA ELETTRICA 22% 129,00
CATENA RICAMBIO 22% 18,00
TOTALE COMPLESSIVO 147,00
PAGATO CON CARTA 147,00
07/07/2026 09:30
DOCUMENTO N. 0003-0045`,
  exp:{negozio:"FERRAMENTA ROSSI SNC", data:"2026-07-07", prezzo:147.00, numero:"0003-0045", pagamento:"Carta", nome:"Motosega Elettrica"} },
{ id:"Anno sbagliato OCR", t:`OBI
OBI Italia S.r.l.
DOCUMENTO COMMERCIALE
TOSAERBA ELETTRICO 22% 179,00
TOTALE 179,00
BANCOMAT 179,00
15/05/2076 11:11
DOCUMENTO N. 3001-0001`,
  exp:{negozio:"Obi", data:"2026-05-15", prezzo:179.00, numero:"3001-0001", pagamento:"Bancomat", nome:"Tosaerba Elettrico"} },
{ id:"Prezzi con spazi OCR", t:`Brico io
DOCUMENTO COMMERCIALE
SCALA ALLUMINIO 5 GR 22% 79, 90
GUANTI LAVORO 22% 3, 50
TOTALE COMPLESSIVO 83, 40
CONTANTI 83, 40
02/02/2026 10:00
DOCUMENTO N. 0010-0020`,
  exp:{negozio:"Brico io", data:"2026-02-02", prezzo:83.40, numero:"0010-0020", pagamento:"Contanti", nome:"Scala Alluminio 5 Gr"} },
,
{ id:"Amazon fattura PDF", t:`Amazon EU S.à r.l., Succursale Italiana
Fattura
Numero fattura IT5001234567
Data fattura 12.10.2025
Data dell'ordine 10.10.2025
Descrizione Qtà Prezzo unitario IVA Prezzo totale
Smartwatch XYZ Pro nero 1 89,99 € 22% 89,99 €
Totale 89,99 €
Pagato con Visa`,
  exp:{negozio:"Amazon", data:"2025-10-12", prezzo:89.99, pagamento:"Carta", nome:"Smartwatch Xyz Pro Nero"} },
{ id:"Tigota molti articoli", t:`TIGOTA'
Gottardo S.p.A.
DOCUMENTO COMMERCIALE
SHAMPOO 250ML 22% 2,99
BAGNOSCHIUMA 22% 1,99
ASCIUGACAPELLI 2000W 22% 24,90
SPAZZOLINO 22% 1,50
DENTIFRICIO 22% 1,89
TOTALE COMPLESSIVO 33,27
PAGAMENTO ELETTRONICO 33,27
04/04/2026 18:00
DOCUMENTO N. 0050-0500`,
  exp:{negozio:"Tigotà", data:"2026-04-04", prezzo:33.27, numero:"0050-0500", pagamento:"Carta", nome:"Asciugacapelli 2000w"} },
{ id:"Un solo articolo = totale", t:`EXPERT
Expert Città
DOCUMENTO COMMERCIALE
FRIGORIFERO COMBINATO 22% 549,00
TOTALE EURO 549,00
BANCOMAT 549,00
01/12/2025 10:30
DOCUMENTO N. 0001-0002`,
  exp:{negozio:"Expert", data:"2025-12-01", prezzo:549.00, numero:"0001-0002", pagamento:"Bancomat", nome:"Frigorifero Combinato"} },
];
