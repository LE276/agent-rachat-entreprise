package fr.ridecheck;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private LinearLayout root;
    private TextView etat, stats, histo, resultatTest, diag;
    private final EditText[] champs = new EditText[Calc.KEYS.length];

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(18, 20, 22));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(36), dp(18), dp(36));
        sv.addView(root);
        setContentView(sv);

        titre("RideCheck", 30);
        texte("Verdict automatique sur les offres Bolt et Uber : rentable, à évaluer ou perte.", 15, Color.LTGRAY);

        // Activation
        section("1. Activer la lecture des offres");
        etat = texte("", 16, Color.WHITE);
        bouton("Ouvrir les réglages d'accessibilité", v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Applications installées > RideCheck > Activer", Toast.LENGTH_LONG).show();
        });
        texte("Dans la liste, touche « RideCheck » puis active-le. Si Android dit « paramètre restreint » : "
                + "Réglages > Applications > RideCheck > menu ⋮ en haut à droite > « Autoriser les paramètres restreints », puis recommence.", 13, Color.GRAY);

        // Stats
        section("2. Aujourd'hui");
        stats = texte("", 16, Color.WHITE);
        bouton("🚗 J'ai accepté la dernière offre", v -> {
            if (Historique.accepterDerniere(this)) Toast.makeText(this, "Course ajoutée", Toast.LENGTH_SHORT).show();
            else Toast.makeText(this, "Rien à ajouter", Toast.LENGTH_SHORT).show();
            rafraichir();
        });
        bouton("Remettre la journée à zéro", v -> { Historique.effacerJour(this); rafraichir(); });
        histo = texte("", 13, Color.LTGRAY);

        // Réglages
        section("3. Réglages véhicule");
        for (int i = 0; i < Calc.KEYS.length; i++) {
            texte(Calc.LABELS[i], 13, Color.LTGRAY);
            EditText e = new EditText(this);
            e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            e.setTextColor(Color.WHITE);
            e.setText(fmt(Calc.get(this, Calc.KEYS[i])));
            champs[i] = e;
            root.addView(e);
        }
        CheckBox voix = new CheckBox(this);
        voix.setText("Annoncer le verdict à voix haute");
        voix.setTextColor(Color.WHITE);
        voix.setChecked(!getSharedPreferences("ridecheck", MODE_PRIVATE).getBoolean("voix_off", false));
        voix.setOnCheckedChangeListener((c, on) -> getSharedPreferences("ridecheck", MODE_PRIVATE).edit().putBoolean("voix_off", !on).apply());
        root.addView(voix);
        bouton("Enregistrer", v -> {
            for (int i = 0; i < Calc.KEYS.length; i++) {
                try { Calc.set(this, Calc.KEYS[i], Calc.num(champs[i].getText().toString())); } catch (Exception ignored) { }
            }
            Toast.makeText(this, "Enregistré · coût réel " + Calc.f2(Calc.coutKm(this)) + " €/km", Toast.LENGTH_LONG).show();
        });

        // Test
        section("4. Tester une offre");
        EditText test = new EditText(this);
        test.setTextColor(Color.WHITE);
        test.setHint("10,49 € (net, TTC)  15 min • 7.4 km  17 min • 8.7 km");
        test.setHintTextColor(Color.GRAY);
        root.addView(test);
        resultatTest = texte("", 15, Color.WHITE);
        bouton("Analyser", v -> {
            Calc.Verdict r = Calc.analyser(this, Calc.parse(test.getText().toString()));
            resultatTest.setText(r.ok ? r.titre + "\n" + r.detail : "Offre non lue : prix ou distance introuvable.");
        });
        section("5. Diagnostic");
        diag = texte("", 12, Color.LTGRAY);
        bouton("Actualiser le diagnostic", v -> rafraichir());
        texte("RideCheck lit seulement l'écran des applis chauffeur, ne clique jamais à ta place et n'envoie aucune donnée.", 12, Color.GRAY);
    }

    @Override
    protected void onResume() { super.onResume(); rafraichir(); }

    void rafraichir() {
        boolean actif = serviceActif();
        etat.setText(actif ? "✅ Lecture des offres ACTIVÉE" : "❌ Lecture des offres désactivée");
        etat.setTextColor(actif ? Color.rgb(31, 191, 106) : Color.rgb(236, 59, 48));
        double[] s = Historique.statsJour(this);
        double parH = s[2] > 0 ? s[1] / (s[2] / 60.0) : 0;
        stats.setText((int) s[0] + " courses · net " + Calc.f2(s[1]) + " € · " + Calc.f2(parH) + " €/h\n" + (int) s[3] + " offres analysées");
        StringBuilder sb = new StringBuilder();
        List<String> l = Historique.lignes(this);
        String[] ic = {"❌", "⚠️", "✅"};
        for (int i = 0; i < Math.min(15, l.size()); i++) {
            String[] f = l.get(i).split(";");
            if (f.length < 8) continue;
            sb.append(f[7].equals("1") ? "🚗 " : "   ").append(f[1]).append("  ").append(ic[Integer.parseInt(f[2])])
              .append("  ").append(Calc.f2(Double.parseDouble(f[3]))).append(" € → ")
              .append(Calc.f2(Double.parseDouble(f[4]))).append(" € net · ")
              .append(Calc.f2(Double.parseDouble(f[5]))).append(" €/h\n");
        }
        android.content.SharedPreferences sp = getSharedPreferences("ridecheck", MODE_PRIVATE);
        if (diag != null) diag.setText("Applis vues : " + sp.getString("diag_pkgs", "aucune") + "\n\nDernier texte lu (Bolt/Uber) :\n" + sp.getString("diag_txt", "rien pour l'instant"));
        histo.setText(sb.length() == 0 ? "Aucune offre analysée pour l'instant." : sb.toString());
    }

    boolean serviceActif() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.toLowerCase(Locale.ROOT).contains(getPackageName().toLowerCase(Locale.ROOT));
    }

    static String fmt(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    TextView titre(String t, int size) {
        TextView v = texte(t, size, Color.WHITE);
        v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    void section(String t) {
        TextView v = titre(t, 19);
        v.setPadding(0, dp(22), 0, dp(6));
    }

    TextView texte(String t, int size, int color) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(size); v.setTextColor(color);
        v.setPadding(0, dp(4), 0, dp(4));
        root.addView(v);
        return v;
    }

    void bouton(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(16);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12)); bg.setColor(Color.rgb(45, 50, 56));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        root.addView(b);
    }
}
