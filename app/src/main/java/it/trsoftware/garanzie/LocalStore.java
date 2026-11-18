package it.trsoftware.garanzie;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Destinazione: una cartella scelta dall'utente sul telefono (o di un'app cloud che la mette a
 * disposizione nel selettore di Android). Dentro la cartella scelta l'app usa la sottocartella
 * "Garanzie e Scontrini", che crea se manca; se la cartella scelta è già quella dell'archivio
 * (il nome contiene "garanzi") la usa direttamente.
 */
final class LocalStore implements Store {

    static final String ARCHIVE_NAME = "Garanzie e Scontrini";
    private static final int MAX_DEPTH = 4;

    private final Context ctx;
    private final Uri treeUri;

    LocalStore(Context ctx, Uri treeUri) {
        this.ctx = ctx.getApplicationContext();
        this.treeUri = treeUri;
    }

    /** Percorso leggibile della cartella scelta, es. "Memoria interna/Documents/Scontrini". */
    static String displayPath(Context ctx, Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            String path = id;
            int c = id.indexOf(':');
            if (c >= 0) {
                String vol = id.substring(0, c), rest = id.substring(c + 1);
                path = ("primary".equalsIgnoreCase(vol) ? "Memoria interna" : vol) + (rest.isEmpty() ? "" : "/" + rest);
            }
            if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) {
                DocumentFile d = DocumentFile.fromTreeUri(ctx, tree);
                String n = d != null && d.getName() != null ? d.getName() : path;
                path = appName(tree.getAuthority()) + "/" + n;
            }
            DocumentFile d = DocumentFile.fromTreeUri(ctx, tree);
            String name = d != null && d.getName() != null ? d.getName() : "";
            return name.toLowerCase().contains("garanzi") ? path : path + "/" + ARCHIVE_NAME;
        } catch (Exception e) {
            return tree.toString();
        }
    }

    private static String appName(String authority) {
        if (authority == null) return "Cloud";
        String a = authority.toLowerCase();
        if (a.contains("onedrive") || a.contains("skydrive")) return "OneDrive";
        if (a.contains("dropbox")) return "Dropbox";
        if (a.contains("google")) return "Google";
        if (a.contains("nextcloud")) return "Nextcloud";
        return authority;
    }

    private DocumentFile root() {
        DocumentFile tree = DocumentFile.fromTreeUri(ctx, treeUri);
        if (tree == null || !tree.exists() || !tree.canWrite())
            throw new IllegalStateException("La cartella scelta non è più accessibile: sceglila di nuovo in Destinazione file.");
        String n = tree.getName() == null ? "" : tree.getName();
        if (n.toLowerCase().contains("garanzi")) return tree;
        DocumentFile a = tree.findFile(ARCHIVE_NAME);
        if (a != null && a.isDirectory()) return a;
        a = tree.createDirectory(ARCHIVE_NAME);
        if (a == null) throw new IllegalStateException("Impossibile creare la cartella " + ARCHIVE_NAME);
        return a;
    }

    private DocumentFile dir(String rel, boolean create) {
        DocumentFile d = root();
        for (String seg : Store.segments(rel)) {
            DocumentFile n = d.findFile(seg);
            if (n == null || !n.isDirectory()) {
                if (!create) return null;
                n = d.createDirectory(seg);
                if (n == null) throw new IllegalStateException("Impossibile creare la cartella " + seg);
            }
            d = n;
        }
        return d;
    }

    @Override
    public String test() throws Exception {
        DocumentFile r = root();
        int n = 0;
        for (DocumentFile f : r.listFiles()) if (f.isDirectory() && f.getName() != null && !Store.skip(f.getName())) n++;
        String probe = "garanzie_test_" + System.currentTimeMillis() + ".txt";
        DocumentFile p = r.createFile("text/plain", probe);
        if (p == null) throw new IllegalStateException("La cartella non è scrivibile.");
        try (OutputStream os = ctx.getContentResolver().openOutputStream(p.getUri())) {
            if (os == null) throw new IllegalStateException("La cartella non è scrivibile.");
            os.write("ok".getBytes(StandardCharsets.UTF_8));
        }
        byte[] back = readUri(p.getUri());
        p.delete();
        if (!"ok".equals(new String(back, StandardCharsets.UTF_8))) throw new IllegalStateException("Lettura di prova non riuscita.");
        return "Cartella raggiungibile e scrivibile: " + n + " cartelle negozio in " + displayPath(ctx, treeUri) + ".";
    }

    @Override
    public String list() throws Exception {
        JSONArray out = new JSONArray();
        walk(root(), "", 0, out);
        return out.toString();
    }

    private void walk(DocumentFile d, String rel, int depth, JSONArray out) throws Exception {
        JSONArray files = new JSONArray();
        String info = null;
        java.util.List<DocumentFile> subs = new java.util.ArrayList<>();
        for (DocumentFile f : d.listFiles()) {
            String name = f.getName();
            if (name == null || Store.skip(name)) continue;
            if (f.isDirectory()) {
                if (depth < MAX_DEPTH) subs.add(f);
            } else if (INFO_FILE.equalsIgnoreCase(name)) {
                try {
                    info = new String(readUri(f.getUri()), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
            } else if (Store.isDoc(name)) {
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("size", f.length());
                o.put("mtime", f.lastModified());
                files.put(o);
            }
        }
        if (!rel.isEmpty() && (files.length() > 0 || info != null)) {
            JSONObject e = new JSONObject();
            e.put("path", rel);
            e.put("files", files);
            if (info != null) e.put("info", info);
            out.put(e);
        }
        for (DocumentFile s : subs) walk(s, rel.isEmpty() ? s.getName() : rel + "/" + s.getName(), depth + 1, out);
    }

    private DocumentFile file(String relPath) {
        String[] seg = Store.segments(relPath);
        if (seg.length == 0) throw new IllegalArgumentException("Percorso non valido");
        StringBuilder dirPath = new StringBuilder();
        for (int i = 0; i < seg.length - 1; i++) dirPath.append(i == 0 ? "" : "/").append(seg[i]);
        DocumentFile d = dir(dirPath.toString(), false);
        DocumentFile f = d == null ? null : d.findFile(seg[seg.length - 1]);
        if (f == null || !f.isFile()) throw new IllegalStateException("File non trovato: " + relPath);
        return f;
    }

    @Override
    public byte[] read(String relPath) throws Exception {
        return readUri(file(relPath).getUri());
    }

    @Override
    public String writeFile(String relDir, String name, byte[] data) throws Exception {
        DocumentFile d = dir(relDir, true);
        String used = Store.uniqueName(name, n -> d.findFile(n) != null);
        DocumentFile f = d.createFile(Store.mimeOf(used), used);
        if (f == null) throw new IllegalStateException("Impossibile creare " + used);
        try (OutputStream os = ctx.getContentResolver().openOutputStream(f.getUri())) {
            if (os == null) throw new IllegalStateException("Impossibile scrivere " + used);
            os.write(data);
        }
        return f.getName() != null ? f.getName() : used;
    }

    @Override
    public void writeText(String relDir, String name, String text) throws Exception {
        DocumentFile d = dir(relDir, true);
        DocumentFile f = d.findFile(name);
        if (f == null) f = d.createFile(Store.mimeOf(name), name);
        if (f == null) throw new IllegalStateException("Impossibile creare " + name);
        try (OutputStream os = ctx.getContentResolver().openOutputStream(f.getUri(), "wt")) {
            if (os == null) throw new IllegalStateException("Impossibile scrivere " + name);
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public String deleteFolder(String relDir) throws Exception {
        String[] seg = Store.segments(relDir);
        if (seg.length == 0) throw new IllegalArgumentException("Cartella non valida");
        DocumentFile d = dir(relDir, false);
        if (d == null) return "già eliminata";
        boolean hasSub = false;
        for (DocumentFile f : d.listFiles()) {
            if (f.isDirectory()) hasSub = true;
            else f.delete();
        }
        if (!hasSub) d.delete();
        if (seg.length > 1) {
            DocumentFile parent = dir(String.join("/", java.util.Arrays.copyOf(seg, seg.length - 1)), false);
            if (parent != null && parent.listFiles().length == 0) parent.delete();
        }
        return "ok";
    }

    private byte[] readUri(Uri u) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
            if (in == null) throw new IllegalStateException("File non leggibile");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }
}
