package fr.ridecheck;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Historique simple des offres (stocké sur le téléphone, jamais envoyé). */
public class Historique {
    static final String KEY = "historique";

    static String jour() { return new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date()); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("ridecheck", Context.MODE_PRIVATE); }

    // Une ligne par offre : jour;heure;niveau;prix;net;parH;minutes;acceptee
    public static void ajouter(Context c, Calc.Offre o, Calc.Verdict v) {
                String heure = new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date());
        String ligne = jour() + ";" + heure + ";" + v.niveau + ";" + o.prix + ";" + v.net + ";" + v.parH + ";" + v.minReel + ";0";
        List<String> l = lignes(c);
        l.add(0, ligne);
        while (l.size() > 200) l.remove(l.size() - 1);
        prefs(c).edit().putString(KEY, String.join("\n", l)).apply();
    }

    public static List<String> lignes(Context c) {
        String s = prefs(c).getString(KEY, "");
        List<String> l = new ArrayList<>();
        if (s.isEmpty()) return l;
        for (String x : s.split("\n")) if (!x.trim().isEmpty()) l.add(x);
        return l;
    }

    public static boolean accepterDerniere(Context c) {
        List<String> l = lignes(c);
        if (l.isEmpty()) return false;
        String[] f = l.get(0).split(";");
        if (f.length < 8 || f[7].equals("1")) return false;
        f[7] = "1";
        l.set(0, String.join(";", f));
        prefs(c).edit().putString(KEY, String.join("\n", l)).apply();
        return true;
    }

    /** {courses, net, minutes, offres} du jour */
    public static double[] statsJour(Context c) {
        String j = jour();
        double courses = 0, net = 0, min = 0, offres = 0;
        for (String x : lignes(c)) {
            String[] f = x.split(";");
            if (f.length < 8 || !f[0].equals(j)) continue;
            offres++;
            if (f[7].equals("1")) { courses++; net += Double.parseDouble(f[4]); min += Double.parseDouble(f[6]); }
        }
        return new double[]{courses, net, min, offres};
    }

    public static void effacerJour(Context c) {
        String j = jour();
        List<String> garde = new ArrayList<>();
        for (String x : lignes(c)) if (!x.startsWith(j + ";")) garde.add(x);
        prefs(c).edit().putString(KEY, String.join("\n", garde)).apply();
    }

}
