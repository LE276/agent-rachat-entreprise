package fr.ridecheck;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lecture d'une offre (texte) et calcul de rentabilité. Même logique que la version iPhone. */
public class Calc {

    public static final String[] KEYS = {"conso", "prixEnergie", "usureKm", "chargesMois", "kmMois",
            "commission", "seuilOK", "seuilMoyen", "netMini", "coefPointe"};
    public static final String[] LABELS = {"Conso (L ou kWh / 100 km)", "Prix carburant / élec (€)",
            "Usure (€ / km)", "Charges fixes (€ / mois)", "Km par mois",
            "Commission si prix client (0 ou 0.25)", "Seuil RENTABLE (€ / h)", "Seuil À ÉVALUER (€ / h)",
            "Net mini par course (€)", "Coef trafic heures de pointe"};
    public static final double[] DEFAULTS = {5.6, 1.99, 0.05, 900, 4000, 0, 20, 12, 5, 1.25};

    public static double get(Context c, String key) {
        SharedPreferences p = c.getSharedPreferences("ridecheck", Context.MODE_PRIVATE);
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) {
            return Double.longBitsToDouble(p.getLong(key, Double.doubleToLongBits(DEFAULTS[i])));
        }
        return 0;
    }

    public static void set(Context c, String key, double v) {
        c.getSharedPreferences("ridecheck", Context.MODE_PRIVATE).edit()
                .putLong(key, Double.doubleToLongBits(v)).apply();
    }

    public static double coutKm(Context c) {
        return get(c, "conso") / 100.0 * get(c, "prixEnergie") + get(c, "usureKm")
                + get(c, "chargesMois") / Math.max(get(c, "kmMois"), 1);
    }

    static double num(String s) { return Double.parseDouble(s.replace(',', '.')); }

    public static class Offre {
        public Double prix; public double appKm, crsKm; public int appMin, crsMin;
        public boolean approcheLue, dejaNet, especes;
    }

    public static Offre parse(String txt) {
        String t = txt.replace(' ', ' ').replace(' ', ' ');
        Offre d = new Offre();
        Matcher m = Pattern.compile("(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*€|€\\s*(\\d{1,3}(?:[.,]\\d{1,2})?)").matcher(t);
        while (m.find()) {
            double v = num(m.group(1) != null ? m.group(1) : m.group(2));
            if (v > 0 && v < 1000) { d.prix = v; break; }
        }
        List<Double> dists = new ArrayList<>();
        m = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\b", Pattern.CASE_INSENSITIVE).matcher(t);
        while (m.find()) {
            double v = num(m.group(1));
            if (m.group(2).equalsIgnoreCase("m")) v = v / 1000.0;
            if (v > 0 && v < 500) dists.add(v);
        }
        List<Integer> durs = new ArrayList<>();
        m = Pattern.compile("(\\d+)\\s*h\\s*(\\d{1,2})?\\s*(?:min|mn)?|(\\d+)\\s*(?:min|mn)\\b", Pattern.CASE_INSENSITIVE).matcher(t);
        while (m.find()) {
            if (m.group(1) != null) durs.add(Integer.parseInt(m.group(1)) * 60 + (m.group(2) != null ? Integer.parseInt(m.group(2)) : 0));
            else durs.add(Integer.parseInt(m.group(3)));
        }
        d.dejaNet = Pattern.compile("\\(\\s*net|net de frais|montant net|\\bnet\\b", Pattern.CASE_INSENSITIVE).matcher(t).find();
        d.especes = Pattern.compile("esp[eè]ces", Pattern.CASE_INSENSITIVE).matcher(t).find();
        if (dists.size() >= 2) { d.appKm = dists.get(0); d.crsKm = dists.get(1); d.approcheLue = true; }
        else if (dists.size() == 1) d.crsKm = dists.get(0);
        if (durs.size() >= 2) { d.appMin = durs.get(0); d.crsMin = durs.get(1); }
        else if (durs.size() == 1) {
            // Uber : "1 min (0.2 km)" puis "Course de 3.4 km" sans durée -> la durée est celle de l'approche
            if (dists.size() >= 2) d.appMin = durs.get(0); else d.crsMin = durs.get(0);
        }
        if (d.crsMin == 0 && d.crsKm > 0) d.crsMin = (int) Math.max(1, Math.round(d.crsKm * 2.4));
        if (d.appMin == 0 && d.appKm > 0) d.appMin = (int) Math.round(d.appKm * 2);
        return d;
    }

    static boolean estPointe(Calendar cal) {
        int j = cal.get(Calendar.DAY_OF_WEEK);
        if (j == Calendar.SATURDAY || j == Calendar.SUNDAY) return false;
        double h = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0;
        return (h >= 7 && h < 9.5) || (h >= 16.5 && h < 19.5);
    }

    public static class Verdict {
        public boolean ok; public int niveau; // 2 = rentable, 1 = à évaluer, 0 = perte
        public String titre, detail, oral;
        public double net, parH; public int minReel;
    }

    static String f2(double v) { return String.format(Locale.FRANCE, "%.2f", v); }

    public static Verdict analyser(Context c, Offre d) {
        Verdict v = new Verdict();
        if (d.prix == null || (d.crsKm == 0 && d.crsMin == 0)) { v.ok = false; return v; }
        v.ok = true;
        boolean pointe = estPointe(Calendar.getInstance());
        double coef = pointe ? get(c, "coefPointe") : 1;
        double kmTot = d.appKm + d.crsKm;
        int minReel = Math.max((int) Math.round((d.appMin + d.crsMin) * coef), 1);
        double comm = d.dejaNet ? 0 : get(c, "commission");
        double brut = d.prix * (1 - comm);
        double couts = kmTot * coutKm(c);
        double net = brut - couts;
        double parH = net / (minReel / 60.0);
        double parKm = kmTot > 0 ? net / kmTot : 0;
        double prixMini = (get(c, "seuilOK") * minReel / 60.0 + couts) / (1 - comm);
        if (net < get(c, "netMini") || parH < get(c, "seuilMoyen")) v.niveau = 0;
        else if (parH >= get(c, "seuilOK")) v.niveau = 2;
        else v.niveau = 1;
        v.net = net; v.parH = parH; v.minReel = minReel;
        String[] noms = {"❌ PERTE", "⚠️ À ÉVALUER", "✅ RENTABLE"};
        String[] oral = {"Perte", "À évaluer", "Rentable"};
        v.titre = noms[v.niveau] + " · " + f2(parH) + " €/h · net " + f2(net) + " €";
        StringBuilder sb = new StringBuilder();
        sb.append(f2(parKm)).append(" €/km · ").append(String.format(Locale.FRANCE, "%.1f", kmTot))
          .append(" km · ~").append(minReel).append(" min").append(pointe ? " (pointe)" : "");
        sb.append("\nApproche ").append(String.format(Locale.FRANCE, "%.1f", d.appKm)).append(" km/").append(d.appMin)
          .append(" min · Course ").append(String.format(Locale.FRANCE, "%.1f", d.crsKm)).append(" km/").append(d.crsMin).append(" min");
        if (v.niveau != 2) sb.append("\nRentable à partir de ").append(f2(prixMini)).append(" €");
        if (d.especes) sb.append("\n💶 Paiement en espèces");
        v.detail = sb.toString();
        v.oral = oral[v.niveau] + ". " + Math.round(parH) + " euros de l'heure.";
        return v;
    }
}
