# Garanzie & Scontrini per Android

Versione Android del programma Windows **GaranzieScontrini** (TR/software).
L'app lavora direttamente sulla cartella del NAS QNAP:

```
\\192.168.1.13\Public\garanzie scontrini acquisti\<Negozio>\<nome AAAA_MM_GG>\
```

È la stessa struttura del programma Windows, quindi quello che salvi dal telefono lo vede anche il PC, e viceversa.

## Cosa fa

- **Acquisisci:** fotografi lo scontrino (anche in più foto) o scegli una foto o un PDF. L'app legge negozio, data, prezzo, numero, pagamento e articoli, e salva tutto nella cartella giusta sul NAS.
- **Cerca:** trova gli acquisti sul NAS per oggetto, negozio, articolo, note o anno, e apre PDF e foto. Funziona anche senza collegamento, con i dati dell'ultimo aggiornamento.
- **Scadenze:** mostra le garanzie in scadenza, attive e scadute. Ogni mattina verso le 9 il telefono manda una notifica quando una garanzia entra nel periodo di avviso (30 giorni, modificabile).
- **Senza NAS:** se il NAS non è raggiungibile, l'acquisto resta sul telefono e viene caricato appena il NAS torna disponibile.

La lettura degli scontrini usa l'OCR di Google, che gira sul telefono: è gratuito e funziona senza internet.
In Impostazioni si può aggiungere una chiave API di Claude (facoltativa): compare il tasto "Rileggi con Claude" per gli scontrini difficili.

### Il file `garanzia.json`

Il programma Windows tiene i dettagli (prezzo, mesi di garanzia, note) in un catalogo sul PC, non sul NAS.
Per le cartelle già esistenti l'app ricava quindi **negozio, nome e data dal nome della cartella** e usa 24 mesi di garanzia.
Quando salvi o modifichi un acquisto dall'app, nella sua cartella viene scritto un piccolo file `garanzia.json` con tutti i dati.
In futuro il programma Windows potrà leggere e scrivere lo stesso file, così i dati coincidono ovunque.

## 1. Caricare il progetto su GitHub

1. Su github.com crea un nuovo repository, per esempio `GaranzieScontrini-Android`. Conviene sceglierlo **Private**.
2. Nella pagina del repository vuoto scegli **uploading an existing file**, poi trascina **tutto il contenuto** di questa cartella, compresa la cartella `.github`. Infine premi **Commit changes**.
   - Se Windows non mostra la cartella `.github`, attiva in Esplora file **Visualizza → Elementi nascosti**.
3. Apri la scheda **Actions**: parte da sola la compilazione **"Compila APK"**, che dura circa 5–8 minuti.
   - Se ti chiede di abilitare le Actions, conferma.
   - Se la compilazione finisce con la ❌ rossa, apri il passaggio fallito, copia l'errore e mandamelo.

## 2. Installare l'app sul telefono

1. Dal telefono apri il repository su GitHub → **Releases** → **Ultima versione** → scarica `GaranzieScontrini.apk`.
   - Con il repository privato devi aver fatto l'accesso a GitHub dal browser del telefono. In alternativa scarica l'APK dal PC (Actions → ultima build → *GaranzieScontrini-apk*) e copialo sul telefono.
2. Aprilo e consenti al browser di **installare app sconosciute** quando Android lo chiede.
3. Ogni volta che modifichi il progetto su GitHub viene creato un APK nuovo. Si installa sopra il precedente senza perdere le impostazioni.

## 3. Primo avvio

1. L'app si apre su **Impostazioni**. I dati del NAS sono già precompilati (`192.168.1.13`, `Public`, `garanzie scontrini acquisti`, `VitoSilviaNas`): basta scrivere la **password**.
2. Premi **Testa connessione**. Deve comparire "NAS raggiungibile e scrivibile".
3. Consenti le **notifiche** quando Android lo chiede, così ricevi gli avvisi di scadenza.

### Fuori casa

Il collegamento usa la condivisione di rete Windows (SMB). Per sicurezza non va aperta su internet.
Fuori casa accendi sul telefono la **VPN del QNAP** (app QVPN, oppure WireGuard o OpenVPN configurati da QVPN Service sul NAS). Con la VPN attiva l'app funziona come a casa, con lo stesso indirizzo `192.168.1.13`.

## Struttura del progetto

```
app/src/main/assets/www/index.html   interfaccia (HTML/JS)
app/src/main/java/.../MainActivity   finestra dell'app, fotocamera, scelta file
app/src/main/java/.../Bridge         collegamento tra interfaccia e funzioni Android
app/src/main/java/.../Nas            accesso al QNAP via SMB (libreria smbj)
app/src/main/java/.../Ocr            lettura scontrini con Google ML Kit
app/src/main/java/.../ReminderReceiver  notifiche di scadenza
app/garanzie.keystore                chiave di firma fissa (per aggiornare l'app senza disinstallarla)
.github/workflows/build-apk.yml      compilazione automatica dell'APK su GitHub
```

Note sulla sicurezza:
- La password del NAS è salvata solo nella memoria privata dell'app, sul telefono.
- La chiave di firma è inclusa nel progetto: tieni il repository **privato**.
