package fr.ridecheck;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * Lit l'écran de l'application chauffeur Bolt (lecture seule, ne clique jamais),
 * détecte une offre, calcule la rentabilité, affiche un bandeau et annonce le verdict.
 */
public class BoltReaderService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private View banner;
    private String lastKey = "";
    private long lastTime = 0;

    @Override
    protected void onServiceConnected() {
        tts = new TextToSpeech(this, status -> { if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.FRANCE); });
    }

    static boolean estBolt(CharSequence pkg) {
        if (pkg == null) return false;
        String p = pkg.toString().toLowerCase(Locale.ROOT);
        return p.contains("mtakso") || p.contains("bolt") || p.contains("ubercab") || p.contains("uber") || p.contains("heetch");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence evPkg = event.getPackageName();
        noterPaquet(evPkg);
        // On lit toutes les fenêtres visibles : l'offre peut être dans une fenêtre qui n'est pas "active"
        StringBuilder sb = new StringBuilder();
        String pkgVtc = null;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                if (estBolt(r.getPackageName())) { pkgVtc = String.valueOf(r.getPackageName()); collect(r, sb, 0); }
            }
        } catch (Exception ignored) { }
        if (sb.length() == 0 && estBolt(evPkg)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) { pkgVtc = String.valueOf(evPkg); collect(root, sb, 0); }
            if (sb.length() == 0 && event.getSource() != null) collect(event.getSource(), sb, 0);
            if (sb.length() == 0) for (CharSequence t : event.getText()) sb.append(t).append('\n');
        }
        if (sb.length() == 0) return;
        String txt = sb.toString();
        String low = txt.toLowerCase(Locale.ROOT);
        diag(pkgVtc, txt);
        if (!txt.contains("€") || !low.contains("km")) return;
        Calc.Offre o = Calc.parse(txt);
        if (o.prix == null || o.crsKm == 0) return;
        String key = o.prix + "|" + o.appKm + "|" + o.crsKm;
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastTime < 60000) return; // même offre déjà annoncée
        lastKey = key; lastTime = now;
        Calc.Verdict v = Calc.analyser(this, o);
        if (!v.ok) return;
        Historique.ajouter(this, o, v);
        afficher(v);
        if (tts != null && !getSharedPreferences("ridecheck", MODE_PRIVATE).getBoolean("voix_off", false)) tts.speak(v.oral, TextToSpeech.QUEUE_FLUSH, null, "rc");
    }

    private long lastDiag = 0;
    private void diag(String pkg, String txt) {
        long now = System.currentTimeMillis();
        if (now - lastDiag < 1500) return;
        lastDiag = now;
        String t = txt.length() > 1500 ? txt.substring(0, 1500) : txt;
        getSharedPreferences("ridecheck", MODE_PRIVATE).edit()
            .putString("diag_txt", new java.text.SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(new java.util.Date()) + " · " + pkg + "\n" + t).apply();
    }

    private void noterPaquet(CharSequence p) {
        if (p == null) return;
        android.content.SharedPreferences sp = getSharedPreferences("ridecheck", MODE_PRIVATE);
        String vus = sp.getString("diag_pkgs", "");
        String s = p.toString();
        if (("," + vus + ",").contains("," + s + ",")) return;
        vus = vus.isEmpty() ? s : vus + "," + s;
        if (vus.length() > 600) vus = vus.substring(vus.length() - 600);
        sp.edit().putString("diag_pkgs", vus).apply();
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 60) return;
        if (n.getText() != null) sb.append(n.getText()).append('\n');
        else if (n.getContentDescription() != null) sb.append(n.getContentDescription()).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, depth + 1);
    }

    private void afficher(Calc.Verdict v) {
        handler.post(() -> {
            retirer();
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            box.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(pad);
            bg.setColor(v.niveau == 2 ? Color.rgb(31, 191, 106) : v.niveau == 1 ? Color.rgb(240, 160, 21) : Color.rgb(236, 59, 48));
            box.setBackground(bg);
            TextView t1 = new TextView(this);
            t1.setText(v.titre); t1.setTextSize(20); t1.setTextColor(Color.WHITE); t1.setTypeface(null, android.graphics.Typeface.BOLD);
            TextView t2 = new TextView(this);
            t2.setText(v.detail); t2.setTextSize(14); t2.setTextColor(Color.WHITE);
            box.addView(t1); box.addView(t2);
            box.setOnClickListener(x -> retirer());
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            lp.x = 0; lp.y = (int) (40 * getResources().getDisplayMetrics().density);
            lp.horizontalMargin = 0.03f;
            try { wm.addView(box, lp); banner = box; } catch (Exception ignored) { }
            handler.postDelayed(this::retirer, 9000);
        });
    }

    private void retirer() {
        if (banner != null) {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(banner); } catch (Exception ignored) { }
            banner = null;
        }
    }

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        retirer();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
