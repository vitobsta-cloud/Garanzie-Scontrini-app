package it.trsoftware.garanzie;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** OCR sul telefono con Google ML Kit; ricostruisce le righe dello scontrino in base alla posizione. */
final class Ocr {

    private static TextRecognizer recognizer;

    private static synchronized TextRecognizer rec() {
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        return recognizer;
    }

    static String read(Context ctx, byte[] data, String mime) throws Exception {
        List<Bitmap> pages = new ArrayList<>();
        if ("application/pdf".equals(mime)) {
            File tmp = new File(ctx.getCacheDir(), "ocr_" + System.nanoTime() + ".pdf");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(data);
            }
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer r = new PdfRenderer(pfd)) {
                int n = Math.min(r.getPageCount(), 4);
                for (int i = 0; i < n; i++) {
                    try (PdfRenderer.Page p = r.openPage(i)) {
                        float scale = Math.min(3f, 2400f / Math.max(p.getWidth(), p.getHeight()));
                        int w = Math.max(1, Math.round(p.getWidth() * scale));
                        int h = Math.max(1, Math.round(p.getHeight() * scale));
                        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        new Canvas(bmp).drawColor(Color.WHITE);
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        pages.add(bmp);
                    }
                }
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } else {
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bmp == null) throw new IllegalArgumentException("Immagine non leggibile");
            pages.add(bmp);
        }

        StringBuilder sb = new StringBuilder();
        for (Bitmap bmp : pages) {
            Text t = Tasks.await(rec().process(InputImage.fromBitmap(bmp, 0)));
            sb.append(toLines(t)).append('\n');
            bmp.recycle();
        }
        return sb.toString().trim();
    }

    private static final class L {
        final String text;
        final int left, top, bottom;

        L(String text, Rect r) {
            this.text = text;
            this.left = r.left;
            this.top = r.top;
            this.bottom = r.bottom;
        }

        int cy() { return (top + bottom) / 2; }

        int h() { return Math.max(1, bottom - top); }
    }

    /** Unisce i pezzi sulla stessa riga orizzontale (descrizione a sinistra, prezzo a destra). */
    private static String toLines(Text t) {
        List<L> ls = new ArrayList<>();
        for (Text.TextBlock b : t.getTextBlocks()) {
            for (Text.Line line : b.getLines()) {
                Rect r = line.getBoundingBox();
                if (r != null) ls.add(new L(line.getText(), r));
            }
        }
        if (ls.isEmpty()) return t.getText();
        Collections.sort(ls, (a, b) -> Integer.compare(a.cy(), b.cy()));

        List<List<L>> rows = new ArrayList<>();
        for (L l : ls) {
            List<L> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (last != null) {
                L ref = last.get(0);
                int tol = Math.min(ref.h(), l.h()) / 2;
                if (Math.abs(l.cy() - ref.cy()) <= tol) {
                    last.add(l);
                    continue;
                }
            }
            List<L> row = new ArrayList<>();
            row.add(l);
            rows.add(row);
        }
        StringBuilder sb = new StringBuilder();
        for (List<L> row : rows) {
            Collections.sort(row, (a, b) -> Integer.compare(a.left, b.left));
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append("  ");
                sb.append(row.get(i).text);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private Ocr() {
    }
}
