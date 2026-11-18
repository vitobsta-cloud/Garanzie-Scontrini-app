package it.trsoftware.garanzie;

/**
 * Destinazione dei file (NAS, cartella sul telefono, Google Drive).
 * I percorsi sono relativi alla cartella dell'archivio e usano "/" come separatore,
 * es. "Leroy Merlin/Trapano 2026_03_01".
 */
interface Store {

    /** Prova collegamento, scrittura e lettura. Restituisce un messaggio per l'utente. */
    String test() throws Exception;

    /** Controllo veloce del collegamento (senza leggere l'elenco). */
    String ping() throws Exception;

    /** JSON: [{path, files:[{name,size,mtime}], info}] per ogni cartella che contiene documenti. */
    String list() throws Exception;

    byte[] read(String relPath) throws Exception;

    /** Scrive un file nuovo (se il nome esiste aggiunge _2, _3…). Restituisce il nome usato. */
    String writeFile(String relDir, String name, byte[] data) throws Exception;

    /** Crea o sovrascrive un file di testo (garanzia.json). */
    void writeText(String relDir, String name, String text) throws Exception;

    /** Elimina la cartella dell'acquisto. */
    String deleteFolder(String relDir) throws Exception;

    // ---------- utilità comuni ----------

    String INFO_FILE = "garanzia.json";

    static boolean isDoc(String name) {
        return name.toLowerCase().matches(".*\\.(pdf|jpe?g|png|webp|bmp|heic|heif|tiff?|gif)$");
    }

    /** Cartelle e file da ignorare (sistema QNAP, nascosti, cestino). */
    static boolean skip(String name) {
        return name.startsWith(".") || name.startsWith("@") || name.startsWith("#") || name.startsWith("_Cestino")
                || name.equalsIgnoreCase("Thumbs.db") || name.equalsIgnoreCase("desktop.ini");
    }

    static String[] segments(String rel) {
        String s = rel == null ? "" : rel.replace('\\', '/');
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : s.split("/")) {
            if (p.isEmpty()) continue;
            if (p.equals("..") || p.equals(".")) throw new IllegalArgumentException("Percorso non valido");
            out.add(p);
        }
        return out.toArray(new String[0]);
    }

    static String uniqueName(String name, java.util.function.Predicate<String> exists) {
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        String used = name;
        for (int k = 2; exists.test(used); k++) used = base + "_" + k + ext;
        return used;
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".txt") || n.endsWith(".tmp")) return "text/plain";
        return Bridge.mimeFor(name);
    }
}
