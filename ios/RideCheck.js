// RideCheck v3 — Verdict de rentabilité des offres Bolt / Uber (Scriptable, iPhone)
// Nouveauté v3 : retour à vide vers la zone de forte demande la plus proche.
const VERSION = 3;
const URL_MAJ = "https://raw.githubusercontent.com/LE276/agent-rachat-entreprise/ridecheck-apk/ios/RideCheck.js";

const DEFAUTS = {
  conso: 5.6, prixEnergie: 1.99, usureKm: 0.05, chargesMois: 900, kmMois: 4000,
  commission: 0, seuilOK: 20, seuilMoyen: 12, netMini: 5, coefPointe: 1.25,
  rayonZone: 2.5, tauxVide: 0.7,
};
const LIBELLES = {
  conso: "Conso (L ou kWh /100 km)", prixEnergie: "Prix carburant/élec (€)",
  usureKm: "Usure (€/km)", chargesMois: "Charges fixes (€/mois)", kmMois: "Km par mois",
  commission: "Commission (0 ou 0.25)", seuilOK: "Seuil RENTABLE (€/h)",
  seuilMoyen: "Seuil À ÉVALUER (€/h)", netMini: "Net mini par course (€)",
  coefPointe: "Coef trafic heures de pointe",
  rayonZone: "Rayon d'une zone forte (km)", tauxVide: "Part du retour à vide (0 à 1)",
};
// Zones de forte demande par défaut (modifiables dans le menu)
const ZONES_DEFAUT = [
  { nom: "Paris Centre (Châtelet/Louvre)", lat: 48.8606, lon: 2.3416 },
  { nom: "Paris Opéra / Saint-Lazare", lat: 48.8738, lon: 2.3264 },
  { nom: "Paris Gare du Nord / Est", lat: 48.8790, lon: 2.3570 },
  { nom: "Paris Gare de Lyon / Bastille", lat: 48.8480, lon: 2.3720 },
  { nom: "Paris Montparnasse", lat: 48.8420, lon: 2.3210 },
  { nom: "Cergy Préfecture", lat: 49.0366, lon: 2.0761 },
  { nom: "Rouen Centre", lat: 49.4404, lon: 1.0939 },
  { nom: "Rouen Gare", lat: 49.4489, lon: 1.0937 },
];

function estPointe(d) {
  const j = d.getDay(); if (j === 0 || j === 6) return false;
  const h = d.getHours() + d.getMinutes() / 60;
  return (h >= 7 && h < 9.5) || (h >= 16.5 && h < 19.5);
}

// ---------- Stockage ----------
const IS_SCRIPTABLE = typeof Script !== "undefined";
const _mem = {};
function fm() { return FileManager.local(); }
function chemin(n) { return fm().joinPath(fm().documentsDirectory(), "ridecheck_" + n + ".json"); }
function lire(n, def) {
  if (!IS_SCRIPTABLE) return n in _mem ? _mem[n] : def;
  const p = chemin(n);
  if (!fm().fileExists(p)) return def;
  try { return JSON.parse(fm().readString(p)); } catch (e) { return def; }
}
function ecrire(n, v) { if (!IS_SCRIPTABLE) { _mem[n] = v; return; } fm().writeString(chemin(n), JSON.stringify(v)); }
function reglages() { return Object.assign({}, DEFAUTS, lire("reglages", {})); }
function zones() { return lire("zones", ZONES_DEFAUT); }
function num(s) { return parseFloat(String(s).replace(",", ".")); }

// ---------- Lecture de l'offre ----------
const RE_KM = /(\d+(?:[.,]\d+)?)\s*(km|m)\b/i;
function parse(txt) {
  const t = String(txt || "").replace(/[  ]/g, " ");
  const prixs = [...t.matchAll(/(\d{1,3}(?:[.,]\d{1,2})?)\s*€|€\s*(\d{1,3}(?:[.,]\d{1,2})?)/g)]
    .map(m => num(m[1] || m[2])).filter(v => v > 0 && v < 1000);
  const dists = [...t.matchAll(/(\d+(?:[.,]\d+)?)\s*(km|m)\b/gi)]
    .map(m => m[2].toLowerCase() === "m" ? num(m[1]) / 1000 : num(m[1]))
    .filter(v => v > 0 && v < 500);
  const durs = [];
  const re = /(\d+)\s*h\s*(\d{1,2})?\s*(?:min|mn)?|(\d+)\s*(?:min|mn)\b/gi;
  let x;
  while ((x = re.exec(t)) !== null) {
    if (x[1] !== undefined) durs.push(parseInt(x[1]) * 60 + (x[2] ? parseInt(x[2]) : 0));
    else durs.push(parseInt(x[3]));
  }
  const d = { prix: prixs.length ? prixs[0] : null, appKm: 0, crsKm: 0, appMin: 0, crsMin: 0, approcheLue: false,
    dejaNet: /\(\s*net|net de frais|montant net/i.test(t), especes: /esp[eè]ces/i.test(t),
    destination: extraireDestination(t) };
  if (dists.length >= 2) { d.appKm = dists[0]; d.crsKm = dists[1]; d.approcheLue = true; }
  else if (dists.length === 1) d.crsKm = dists[0];
  if (durs.length >= 2) { d.appMin = durs[0]; d.crsMin = durs[1]; }
  else if (durs.length === 1) { if (dists.length >= 2) d.appMin = durs[0]; else d.crsMin = durs[0]; }
  if (!d.crsMin && d.crsKm) d.crsMin = Math.max(1, Math.round(d.crsKm * 2.4));
  if (!d.appMin && d.appKm) d.appMin = Math.round(d.appKm * 2.4);
  return d;
}

// L'adresse d'arrivée = les lignes qui suivent la dernière distance (Bolt et Uber)
function extraireDestination(t) {
  const lignes = t.split(/\n/).map(l => l.trim()).filter(l => l);
  let i = -1;
  lignes.forEach((l, k) => { if (RE_KM.test(l)) i = k; });
  if (i < 0) return null;
  const bruit = /accepter|répondre|repondre|refuser|annuler|^\d+([.,]\d+)?$|min\b|€|google|waze/i;
  const pris = [];
  for (let k = i + 1; k < lignes.length && pris.length < 2; k++) {
    if (bruit.test(lignes[k])) { if (pris.length) break; else continue; }
    pris.push(lignes[k]);
  }
  const a = pris.reduce((acc, l) => acc ? (acc.endsWith("-") ? acc + l : acc + " " + l) : l, "").replace(/\s+/g, " ").trim();
  return a.length >= 4 ? a : null;
}

// ---------- Géographie ----------
function distKm(a, b) {
  const R = 6371, r = x => x * Math.PI / 180;
  const dLat = r(b.lat - a.lat), dLon = r(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(r(a.lat)) * Math.cos(r(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
async function geocoder(adresse) {
  if (!adresse || !IS_SCRIPTABLE) return null;
  try {
    const req = new Request("https://api-adresse.data.gouv.fr/search/?limit=1&q=" + encodeURIComponent(adresse));
    req.timeoutInterval = 4;
    const j = await req.loadJSON();
    const f = j.features && j.features[0];
    if (!f || f.properties.score < 0.35) return null;
    return { lat: f.geometry.coordinates[1], lon: f.geometry.coordinates[0], label: f.properties.label };
  } catch (e) { return null; }
}
// Retour à vide : de l'arrivée jusqu'au bord de la zone forte la plus proche
function calculRetour(dest, R) {
  if (!dest) return null;
  let best = null;
  for (const z of zones()) {
    const dk = distKm(dest, z);
    if (!best || dk < best.dk) best = { dk, nom: z.nom };
  }
  if (!best) return null;
  let horsZone = Math.max(0, best.dk - R.rayonZone);
  if (horsZone < 0.5) horsZone = 0;
  const km = horsZone * 1.3 * R.tauxVide;        // 1,3 = détours de la route
  const min = Math.round(km / 25 * 60);         // ~25 km/h en ville
  return { km, min, zone: best.nom, dansZone: horsZone === 0 };
}

// ---------- Calcul ----------
function analyser(d, R, retour, maintenant) {
  maintenant = maintenant || new Date();
  if (d.prix === null || (!d.crsKm && !d.crsMin)) return { ok: false };
  const coutKm = (R.conso / 100) * R.prixEnergie + R.usureKm + R.chargesMois / R.kmMois;
  const pointe = estPointe(maintenant);
  const coef = pointe ? R.coefPointe : 1;
  const retKm = retour ? retour.km : 0, retMin = retour ? retour.min : 0;
  const kmTot = d.appKm + d.crsKm + retKm;
  const minReel = Math.max(Math.round((d.appMin + d.crsMin + retMin) * coef), 1);
  const comm = d.dejaNet ? 0 : R.commission;
  const brut = d.prix * (1 - comm);
  const couts = kmTot * coutKm;
  const net = brut - couts;
  const parH = net / (minReel / 60);
  const parKm = kmTot > 0 ? net / kmTot : 0;
  const prixMini = (R.seuilOK * minReel / 60 + couts) / (1 - comm);
  let niveau;
  if (net < R.netMini || parH < R.seuilMoyen) niveau = "PERTE";
  else if (parH >= R.seuilOK) niveau = "RENTABLE";
  else niveau = "A_EVALUER";
  return { ok: true, niveau, net, brut, parH, parKm, minReel, kmTot, prixMini, pointe, d, retour };
}

const f2 = n => n.toFixed(2).replace(".", ",");
const f1 = n => n.toFixed(1).replace(".", ",");
const ICONE = { RENTABLE: "✅ RENTABLE", A_EVALUER: "⚠️ À ÉVALUER", PERTE: "❌ PERTE" };

function texteVerdict(a) {
  if (!a.ok) return { titre: "❓ Offre non lue", corps: "Prix ou distance introuvable. Refais la capture quand l'offre est bien affichée." };
  const d = a.d;
  const titre = `${ICONE[a.niveau]} · ${f2(a.parH)} €/h · net ${f2(a.net)} €`;
  let corps = `${f2(a.parKm)} €/km · ${f1(a.kmTot)} km · ~${a.minReel} min réelles${a.pointe ? " (pointe)" : ""}\n`;
  corps += `Approche ${f1(d.appKm)} km/${d.appMin} min · Course ${f1(d.crsKm)} km/${d.crsMin} min`;
  if (a.retour) {
    corps += a.retour.dansZone ? `\n📍 Arrivée en zone forte (${a.retour.zone})`
      : `\n↩️ Retour à vide ${f1(a.retour.km)} km/${a.retour.min} min vers ${a.retour.zone}`;
  } else if (d.destination) corps += `\n(arrivée non localisée : retour non compté)`;
  if (a.niveau !== "RENTABLE") corps += `\nRentable à partir de ${f2(a.prixMini)} €`;
  if (d.especes) corps += `\n💶 Paiement en espèces`;
  return { titre, corps };
}

// ---------- Journal & stats ----------
function cleJour() { const d = new Date(); return d.getFullYear() + "-" + (d.getMonth() + 1) + "-" + d.getDate(); }
function enregistrerOffre(a) {
  if (!a.ok) return;
  const h = lire("historique", []);
  h.unshift({ t: Date.now(), jour: cleJour(), niveau: a.niveau, prix: a.d.prix, net: a.net, parH: a.parH, min: a.minReel, acceptee: false });
  ecrire("historique", h.slice(0, 300));
}
function statsJour() {
  const h = lire("historique", []).filter(o => o.jour === cleJour());
  const acc = h.filter(o => o.acceptee);
  const net = acc.reduce((s, o) => s + o.net, 0);
  const min = acc.reduce((s, o) => s + o.min, 0);
  const brut = acc.reduce((s, o) => s + o.prix, 0);
  return { offres: h.length, courses: acc.length, net, brut, parH: min ? net / (min / 60) : 0, dernier: h[0] };
}

// ---------- Interface ----------
async function alerte(titre, msg) { const a = new Alert(); a.title = titre; a.message = msg; a.addAction("OK"); await a.present(); }

async function menuReglages() {
  const R = reglages();
  const a = new Alert();
  a.title = "Réglages véhicule";
  a.message = "Point pour les décimales (ex : 1.75)";
  const cles = Object.keys(DEFAUTS);
  for (const k of cles) a.addTextField(LIBELLES[k], String(R[k]));
  a.addAction("Enregistrer"); a.addCancelAction("Annuler");
  if (await a.presentAlert() === -1) return;
  const nv = {};
  cles.forEach((k, i) => { const v = num(a.textFieldValue(i)); if (!isNaN(v)) nv[k] = v; });
  ecrire("reglages", nv);
  const R2 = reglages();
  const coutKm = (R2.conso / 100) * R2.prixEnergie + R2.usureKm + R2.chargesMois / R2.kmMois;
  await alerte("Enregistré ✅", `Ton coût réel : ${f2(coutKm)} €/km`);
}

async function menuZones() {
  while (true) {
    const z = zones();
    const a = new Alert();
    a.title = "Zones de forte demande";
    a.message = z.length ? z.map((x, i) => `${i + 1}. ${x.nom}`).join("\n") : "Aucune zone.";
    a.addAction("📍 Ajouter ma position actuelle");
    a.addAction("🔎 Ajouter une adresse");
    a.addAction("🗑 Supprimer une zone");
    a.addAction("↺ Revenir aux zones par défaut");
    a.addCancelAction("Fermer");
    const i = await a.presentSheet();
    if (i === -1) return;
    if (i === 0) {
      try {
        Location.setAccuracyToHundredMeters();
        const p = await Location.current();
        const n = new Alert(); n.title = "Nom de la zone"; n.addTextField("ex : Tour Eiffel", ""); n.addAction("OK");
        await n.presentAlert();
        z.push({ nom: n.textFieldValue(0) || "Ma zone", lat: p.latitude, lon: p.longitude });
        ecrire("zones", z);
      } catch (e) { await alerte("Position indisponible", "Autorise la localisation pour Scriptable dans Réglages."); }
    } else if (i === 1) {
      const n = new Alert(); n.title = "Adresse de la zone"; n.addTextField("ex : Gare Saint-Lazare, Paris", ""); n.addAction("Chercher"); n.addCancelAction("Annuler");
      if (await n.presentAlert() === -1) continue;
      const g = await geocoder(n.textFieldValue(0));
      if (!g) { await alerte("Introuvable", "Essaie avec la ville (ex : Place d'Italie, Paris)."); continue; }
      z.push({ nom: n.textFieldValue(0), lat: g.lat, lon: g.lon });
      ecrire("zones", z);
      await alerte("Zone ajoutée ✅", g.label);
    } else if (i === 2) {
      if (!z.length) continue;
      const s = new Alert(); s.title = "Supprimer quelle zone ?";
      z.forEach(x => s.addAction(x.nom)); s.addCancelAction("Annuler");
      const k = await s.presentSheet();
      if (k >= 0) { z.splice(k, 1); ecrire("zones", z); }
    } else if (i === 3) {
      ecrire("zones", ZONES_DEFAUT);
    }
  }
}

async function accepterDerniere() {
  const h = lire("historique", []);
  if (!h.length) return alerte("Rien à valider", "Aucune offre analysée.");
  if (h[0].acceptee) return alerte("Déjà comptée", "La dernière offre est déjà dans tes gains.");
  h[0].acceptee = true; ecrire("historique", h);
  const s = statsJour();
  await alerte("Course ajoutée ✅", `Aujourd'hui : ${s.courses} courses · net ${f2(s.net)} € · ${f2(s.parH)} €/h`);
}

async function afficherStats() {
  const s = statsJour();
  const h = lire("historique", []).filter(o => o.jour === cleJour());
  const rep = { RENTABLE: 0, A_EVALUER: 0, PERTE: 0 };
  h.forEach(o => rep[o.niveau]++);
  await alerte("Aujourd'hui",
    `Courses : ${s.courses}\nBrut : ${f2(s.brut)} €\nNet réel : ${f2(s.net)} €\nMoyenne : ${f2(s.parH)} €/h\n\n` +
    `Offres analysées : ${s.offres}\n✅ ${rep.RENTABLE}  ⚠️ ${rep.A_EVALUER}  ❌ ${rep.PERTE}`);
}

async function afficherHistorique() {
  const h = lire("historique", []).slice(0, 40);
  if (!h.length) return alerte("Historique", "Vide pour l'instant.");
  const lignes = h.map(o => {
    const d = new Date(o.t);
    const hh = String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0");
    return `${o.acceptee ? "🚗" : "  "} ${d.getDate()}/${d.getMonth() + 1} ${hh}  ${ICONE[o.niveau].split(" ")[0]}  ${f2(o.prix)} € → ${f2(o.net)} € net · ${f2(o.parH)} €/h`;
  });
  await QuickLook.present(lignes.join("\n"), true);
}

async function testerOffre() {
  const a = new Alert();
  a.title = "Tester une offre";
  a.message = "Colle le texte d'une offre (ou laisse vide pour le presse-papiers)";
  a.addTextField("ex : 12,50 € 4 min 1,8 km 22 min 9,4 km Rue X, Paris", "");
  a.addAction("Analyser"); a.addCancelAction("Annuler");
  if (await a.presentAlert() === -1) return;
  const txt = a.textFieldValue(0) || Pasteboard.paste() || "";
  const v = await verdictComplet(txt, false);
  await alerte(v.titre, v.corps);
}

async function mettreAJour() {
  try {
    const req = new Request(URL_MAJ + "?t=" + Date.now());
    const code = await req.loadString();
    const m = code.match(/const VERSION = (\d+)/);
    if (!m) return alerte("Mise à jour", "Fichier de mise à jour introuvable.");
    if (parseInt(m[1]) <= VERSION) return alerte("Mise à jour", `Tu as déjà la dernière version (v${VERSION}).`);
    fm().writeString(module.filename, code);
    await alerte("Mise à jour ✅", `RideCheck passe en v${m[1]}. Relance le script.`);
  } catch (e) { await alerte("Mise à jour impossible", String(e)); }
}

async function menu() {
  while (true) {
    const s = statsJour();
    const a = new Alert();
    a.title = `RideCheck v${VERSION}`;
    a.message = `Aujourd'hui : ${s.courses} courses · ${f2(s.net)} € net · ${f2(s.parH)} €/h`;
    const actions = [
      ["🚗 J'ai accepté la dernière offre", accepterDerniere],
      ["📊 Stats du jour", afficherStats],
      ["🕘 Historique", afficherHistorique],
      ["📍 Zones de forte demande", menuZones],
      ["⚙️ Réglages véhicule", menuReglages],
      ["🧪 Tester une offre", testerOffre],
      ["🔄 Mettre à jour RideCheck", mettreAJour],
    ];
    actions.forEach(([l]) => a.addAction(l));
    a.addCancelAction("Fermer");
    const i = await a.presentSheet();
    if (i === -1) return;
    await actions[i][1]();
  }
}

function widget() {
  const s = statsJour();
  const w = new ListWidget();
  w.backgroundColor = new Color("#1a1410");
  w.setPadding(14, 14, 14, 14);
  const t = w.addText("RideCheck"); t.font = Font.boldSystemFont(13); t.textColor = new Color("#f5a623");
  w.addSpacer(6);
  const n = w.addText(f2(s.net) + " €"); n.font = Font.boldSystemFont(26); n.textColor = Color.white();
  const sub = w.addText(`net · ${s.courses} courses · ${f2(s.parH)} €/h`); sub.font = Font.systemFont(11); sub.textColor = Color.lightGray();
  w.addSpacer();
  if (s.dernier) {
    const c = { RENTABLE: "#34c759", A_EVALUER: "#ff9f0a", PERTE: "#ff3b30" }[s.dernier.niveau];
    const l = w.addText(`Dernière : ${ICONE[s.dernier.niveau]} ${f2(s.dernier.parH)} €/h`);
    l.font = Font.semiboldSystemFont(11); l.textColor = new Color(c);
  }
  w.refreshAfterDate = new Date(Date.now() + 5 * 60 * 1000);
  return w;
}

async function verdictComplet(txt, enregistrer) {
  const R = reglages();
  const d = parse(txt);
  let retour = null;
  if (d.destination) {
    const g = await geocoder(d.destination);
    if (g) retour = calculRetour(g, R);
  }
  const a = analyser(d, R, retour);
  if (enregistrer) enregistrerOffre(a);
  return texteVerdict(a);
}

async function main() {
  let txt = args.shortcutParameter;
  if (Array.isArray(txt)) txt = txt.join("\n");
  if (config.runsInWidget) {
    Script.setWidget(widget());
  } else if (txt) {
    const v = await verdictComplet(txt, true);
    Script.setShortcutOutput(v.titre + "\n" + v.corps);
  } else if (config.runsInApp) {
    await menu();
  } else {
    Script.setShortcutOutput("❓ Aucun texte reçu du raccourci");
  }
  Script.complete();
}

if (IS_SCRIPTABLE) await main();
else module.exports = { parse, analyser, texteVerdict, calculRetour, extraireDestination, DEFAUTS, ZONES_DEFAUT };
