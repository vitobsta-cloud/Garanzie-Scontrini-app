package it.trsoftware.garanzie;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Scheda A4 disegnata direttamente in PDF (un solo foglio): dati e articoli a sinistra,
 * foto dello scontrino a destra. Il PDF viene poi passato alla stampa di Android,
 * che lo tratta come un normale documento (più affidabile della stampa da pagina web).
 */
final class SchedaPdf {
    private static final int W = 595, H = 842, M = 30;          // A4 in punti, margine
    private static final int COL = 300;                          // larghezza colonna dati

    static File crea(File dir, String json) throws Exception {
        JSONObject d = new JSONObject(json);
        PdfDocument doc = new PdfDocument();
        PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, 1).create());
        Canvas c = page.getCanvas();
        c.drawColor(Color.WHITE);

        TextPaint tit = tp(17, true, 0xFF111111), sub = tp(11, false, 0xFF444444), brand = tp(8.5f, true, 0xFF0B7BC0),
                brand2 = tp(8.5f, false, 0xFF555555), k = tp(9.5f, false, 0xFF666666), v = tp(9.5f, false, 0xFF111111),
                h2 = tp(11, true, 0xFF111111), foot = tp(7.5f, false, 0xFF777777), pill = tp(9.5f, true, 0xFF111111);
        Paint line = new Paint(); line.setColor(0xFFDDDDDD); line.setStrokeWidth(0.6f);
        Paint thick = new Paint(); thick.setColor(0xFF1A1A1A); thick.setStrokeWidth(1.6f);

        // intestazione
        float y = M;
        y += draw(c, d.optString("nome", "Scheda acquisto"), tit, M, y, W - 2 * M - 120);
        y += draw(c, d.optString("negozio", ""), sub, M, y, W - 2 * M - 120);
        Paint.FontMetrics fm = brand.getFontMetrics();
        brand.setTextAlign(Paint.Align.RIGHT); brand2.setTextAlign(Paint.Align.RIGHT);
        c.drawText("TR/software", W - M, y - 14, brand);
        c.drawText("Garanzie & Scontrini", W - M, y - 3, brand2);
        y += 4;
        c.drawLine(M, y, W - M, y, thick);
        y += 10;
        float top = y;

        // colonna dati
        String stato = d.optString("stato", "");
        if (!stato.isEmpty()) {
            float tw = pill.measureText(stato);
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG); bp.setStyle(Paint.Style.STROKE); bp.setColor(0xFF999999); bp.setStrokeWidth(0.8f);
            c.drawRoundRect(new RectF(M, y, M + tw + 16, y + 17), 8, 8, bp);
            c.drawText(stato, M + 8, y + 12.5f, pill);
            y += 24;
        }
        JSONArray righe = d.optJSONArray("righe");
        if (righe != null) for (int i = 0; i < righe.length(); i++) {
            JSONArray r = righe.getJSONArray(i);
            float h1 = draw(c, r.optString(0), k, M + 2, y + 3, 95);
            float h2v = draw(c, r.optString(1), v, M + 100, y + 3, COL - 104);
            y += Math.max(h1, h2v) + 6;
            c.drawLine(M, y, M + COL, y, line);
        }
        JSONArray art = d.optJSONArray("articoli");
        if (art != null && art.length() > 0) {
            y += 10;
            y += draw(c, "Articoli", h2, M, y, COL) + 2;
            TextPaint vr = tp(9.5f, false, 0xFF111111); vr.setTextAlign(Paint.Align.RIGHT);
            for (int i = 0; i < art.length(); i++) {
                JSONArray a = art.getJSONArray(i);
                float hh = draw(c, a.optString(0), v, M + 2, y + 3, COL - 70);
                c.drawText(a.optString(1), M + COL - 2, y + 3 - v.getFontMetrics().ascent, vr);
                y += hh + 6;
                c.drawLine(M, y, M + COL, y, line);
                if (y > H - 80) break;
            }
        }
        String note = d.optString("note", "");
        if (!note.isEmpty() && y < H - 90) {
            y += 10;
            y += draw(c, "Note", h2, M, y, COL) + 2;
            y += draw(c, note, v, M, y, COL);
        }
        y += 12;
        draw(c, d.optString("piede", ""), foot, M, Math.min(y, H - M - 10), COL);

        // colonna foto
        JSONArray img = d.optJSONArray("immagini");
        if (img != null && img.length() > 0) {
            float x0 = M + COL + 16, bw = W - M - x0, bh = H - M - top;
            float each = (bh - 6 * (img.length() - 1)) / img.length();
            float yy = top;
            Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Paint border = new Paint(); border.setStyle(Paint.Style.STROKE); border.setColor(0xFFDDDDDD); border.setStrokeWidth(0.6f);
            for (int i = 0; i < img.length(); i++) {
                Bitmap b = decode(img.getString(i), 1600);
                if (b == null) continue;
                float s = Math.min(bw / b.getWidth(), each / b.getHeight());
                float iw = b.getWidth() * s, ih = b.getHeight() * s, ix = x0 + (bw - iw) / 2;
                RectF dst = new RectF(ix, yy, ix + iw, yy + ih);
                c.drawBitmap(b, null, dst, ip);
                c.drawRect(dst, border);
                b.recycle();
                yy += ih + 6;
            }
        }

        doc.finishPage(page);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "scheda.pdf");
        try (FileOutputStream out = new FileOutputStream(f)) {
            doc.writeTo(out);
        } finally {
            doc.close();
        }
        return f;
    }

    private static TextPaint tp(float size, boolean bold, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }

    /** Scrive testo a capo dentro la larghezza data; restituisce l'altezza usata. */
    private static float draw(Canvas c, String t, TextPaint p, float x, float y, float w) {
        if (t == null || t.isEmpty()) return 0;
        StaticLayout l = StaticLayout.Builder.obtain(t, 0, t.length(), p, (int) Math.max(10, w))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.05f).build();
        c.save();
        c.translate(x, y);
        l.draw(c);
        c.restore();
        return l.getHeight();
    }

    private static Bitmap decode(String b64, int max) {
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > max) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    /** Passa un PDF già pronto alla stampa di Android. */
    static final class Adapter extends PrintDocumentAdapter {
        private final File file;
        private final String nome;

        Adapter(File file, String nome) { this.file = file; this.nome = nome; }

        @Override
        public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cs, LayoutResultCallback cb, Bundle extras) {
            if (cs.isCanceled()) { cb.onLayoutCancelled(); return; }
            cb.onLayoutFinished(new PrintDocumentInfo.Builder(nome + ".pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(), true);
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor dest, CancellationSignal cs, WriteResultCallback cb) {
            try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) {
                    if (cs.isCanceled()) { cb.onWriteCancelled(); return; }
                    out.write(b, 0, n);
                }
                cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception e) {
                cb.onWriteFailed(e.getMessage());
            }
        }
    }
}
