/*
 * grupare.js — afla ce celule sta pe ce senzor de temperatura.
 *
 * Pachetul da 120 de tensiuni si 14 temperaturi, dar nimeni nu spune care
 * senzor sta langa care celule. Se poate afla din date, fara sa deschizi
 * nimic: rezistenta interna a unei celule creste la rece, deci sub sarcina
 * celula rece cade mai mult decat celelalte. Daca un senzor si un grup de
 * celule stau in acelasi modul, abaterea celulelor si abaterea senzorului
 * urca si coboara impreuna.
 *
 * Toata unealta se sprijina pe un truc: nu corelam valorile brute, ci
 * abaterile. Tensiunea celulei minus media celor 120, temperatura senzorului
 * minus media celor 14. Altfel incalzirea pachetului si scaderea SOC-ului ar
 * corela totul cu tot si n-ar ieste nimic.
 *
 * Ce face, de la sine, la fiecare cursa:
 *   · ia o proba la 15 secunde, cat timp pagina da celule si temperaturi;
 *   · duce sumele de corelatie din mers, deci nu tine in memorie mii de probe;
 *   · la sfarsitul cursei socoteste harta senzor -> celule, cantareste
 *     ipotezele de modul (12 x 10, 10 x 12, 8 x 15 ...) si pregateste un
 *     fisier json;
 *   · pastreaza si un al doilea rand de sume, care se adauga de la o cursa la
 *     alta, fiindca verdictul se limpezeste cu kilometrii.
 *
 * Trimiterea fisierului cere o apasare, nu se poate face singura: navigator
 * .share are nevoie de un gest al omului. Deci la sfarsitul cursei apare o
 * fasie jos, cu fisierul gata facut, si el asteapta acolo pana il trimiti.
 * Nu se pierde: sta in localStorage, ultimele trei curse.
 *
 * Are nevoie de o singura linie in index.html, inauntrul scriptului paginii:
 *     window.AV={V:V, tempPeDid:tempMod};
 * Fara ea, starea paginii nu se vede din afara si unealta doarme.
 */
(function () {
  "use strict";

  var PAS = 15000;          // o proba la 15 secunde
  var TACERE = 90000;       // atata liniste si socotim cursa incheiata
  var MIN_PROBE = 40;       // sub atat nu iese nimic de crezut
  var MAX_FISIER = 180;     // cate probe intra in fisier
  var MIN_SENZORI = 12;     // sub atat nu inghetam lista de senzori
  var CHEIE_F = "grupareFisiere";
  var CHEIE_L = "grupareLung";
  var CHEIE_U = "grupareUrcate";

  var CULORI = ["#0071C5", "#00875A", "#B25E00", "#7B4FBF", "#C0281C", "#0E7C86",
                "#A6761D", "#D6336C", "#2F6F2F", "#5C6B7A", "#1F4E9C", "#8A5A00",
                "#00695C", "#9C1F5F"];

  var S = null;             // cursa de acum
  var L = null;             // sumele lungi, peste curse
  var ultimaCitire = 0, ultimulPas = 0, semnCel = "", inchisa = false;

  function G(id) { return document.getElementById(id); }
  function nr(v, z) {
    if (v === null || v === undefined || !isFinite(v)) return "\u2014";
    return (v < 0 ? "\u2212" : "") + Math.abs(v).toFixed(z).replace(".", ",");
  }
  function stare() { return (window.AV && window.AV.V) ? window.AV.V : null; }
  function temperaturi() { return (window.AV && window.AV.tempPeDid) ? window.AV.tempPeDid : null; }

  /* ------------------------------------------------------------- acumulatorii
     Pentru fiecare celula i si senzor s ducem sumele lui Pearson. 120 x 14
     inseamna 1680 de numere, deci nu e nevoie sa pastram probele. */
  function acumulator(nc, ns) {
    return {
      n: 0, nc: nc, ns: ns,
      sx: new Float64Array(nc), sxx: new Float64Array(nc),
      sy: new Float64Array(ns), syy: new Float64Array(ns),
      sxy: new Float64Array(nc * ns)
    };
  }
  function adauga(A, d, y) {
    if (A.nc !== d.length || A.ns !== y.length) return;
    A.n++;
    for (var i = 0; i < A.nc; i++) {
      A.sx[i] += d[i]; A.sxx[i] += d[i] * d[i];
      var b = i * A.ns;
      for (var s = 0; s < A.ns; s++) A.sxy[b + s] += d[i] * y[s];
    }
    for (var q = 0; q < A.ns; q++) { A.sy[q] += y[q]; A.syy[q] += y[q] * y[q]; }
  }
  function corelatii(A) {
    var r = [], n = A.n;
    for (var i = 0; i < A.nc; i++) {
      var rand = [], vx = n * A.sxx[i] - A.sx[i] * A.sx[i];
      for (var s = 0; s < A.ns; s++) {
        var vy = n * A.syy[s] - A.sy[s] * A.sy[s];
        var jos = Math.sqrt(vx * vy);
        rand.push(jos > 1e-9 ? (n * A.sxy[i * A.ns + s] - A.sx[i] * A.sy[s]) / jos : 0);
      }
      r.push(rand);
    }
    return r;
  }
  function inSir(A) {
    return { n: A.n, nc: A.nc, ns: A.ns, sx: [].slice.call(A.sx), sxx: [].slice.call(A.sxx),
             sy: [].slice.call(A.sy), syy: [].slice.call(A.syy), sxy: [].slice.call(A.sxy) };
  }
  function dinSir(o) {
    if (!o || !o.nc) return null;
    var A = acumulator(o.nc, o.ns);
    A.n = o.n;
    ["sx", "sxx", "sy", "syy", "sxy"].forEach(function (k) {
      for (var i = 0; i < o[k].length; i++) A[k][i] = o[k][i];
    });
    return A;
  }

  /* ------------------------------------------------------------------ verdict */
  function verdict(A, senzori) {
    if (!A || A.n < MIN_PROBE) return null;
    var r = corelatii(A), nc = A.nc, ns = A.ns;

    /* senzorul cel mai potrivit pentru fiecare celula. Cautam maximul, nu
       maximul in modul: o celula mai rece decat media sta pe un senzor mai
       rece decat media, deci potrivirea e pozitiva. */
    var ales = [], cat = [];
    for (var i = 0; i < nc; i++) {
      var b = 0;
      for (var s = 1; s < ns; s++) if (r[i][s] > r[i][b]) b = s;
      ales.push(b); cat.push(r[i][b]);
    }

    /* cat de bine se aseaza alegerile in module de marimi rotunde */
    var ipoteze = [];
    [4, 5, 6, 8, 10, 12, 15, 20, 24, 30].forEach(function (k) {
      if (nc % k !== 0) return;
      var blocuri = [], deAcord = 0, sumaR = 0, folositi = {};
      for (var b0 = 0; b0 < nc; b0 += k) {
        var vot = {}, cel = null, max = -1;
        for (var i2 = b0; i2 < b0 + k; i2++) {
          vot[ales[i2]] = (vot[ales[i2]] || 0) + 1;
          if (vot[ales[i2]] > max) { max = vot[ales[i2]]; cel = ales[i2]; }
        }
        deAcord += max; folositi[cel] = true;
        for (var i3 = b0; i3 < b0 + k; i3++) sumaR += r[i3][cel];
        blocuri.push({ de: b0 + 1, la: b0 + k, senzor: senzori[cel], vot: max + "/" + k });
      }
      var nrBl = nc / k;
      var unici = Object.keys(folositi).length;
      ipoteze.push({
        marime: k, module: nrBl, blocuri: blocuri,
        potrivire: deAcord / nc,
        rMediu: sumaR / nc,
        senzoriFolositi: unici,
        /* un senzor pe modul e semnul bun: daca doua module cad pe acelasi
           senzor, ipoteza e prea fina sau prea groasa */
        scor: (deAcord / nc) * (unici / nrBl) + Math.max(0, sumaR / nc) * 0.5
      });
    });
    ipoteze.sort(function (a, b) { return b.scor - a.scor; });

    /* statistici pe senzor: cine se mișcă repede și are bătaie mare e mai
       degrabă pe circuitul de răcire decât pe celule */
    var folosit = {};
    ales.forEach(function (s) { folosit[s] = (folosit[s] || 0) + 1; });
    var sz = senzori.map(function (did, s) {
      return { did: did, celule: folosit[s] || 0 };
    });

    return {
      probe: A.n,
      ales: ales.map(function (s) { return senzori[s]; }),
      cat: cat,
      ipoteze: ipoteze.slice(0, 4),
      senzori: sz,
      fara: sz.filter(function (x) { return x.celule === 0; }).map(function (x) { return x.did; }),
      r: r
    };
  }

  function rezumat(v) {
    if (!v) return "se adun\u0103 \u2014 nevoie de cel pu\u021bin " + MIN_PROBE + " probe";
    var i = v.ipoteze[0];
    if (!i) return v.probe + " probe";
    var t = i.module + " module de c\u00e2te " + i.marime + " celule, potrivire " +
      Math.round(i.potrivire * 100) + "%, leg\u0103tur\u0103 medie " + nr(i.rMediu, 2);
    if (v.fara.length) {
      t += " \u00b7 " + v.fara.join(", ") + " nu prind celule, probabil tur \u0219i retur";
    }
    return t;
  }

  /* -------------------------------------------------------------------- proba */
  function proba() {
    var V = stare(), T = temperaturi();
    if (!V || !T) return;
    var cel = V.celule;
    if (!cel || cel.length < 20) return;

    var chei = Object.keys(T).sort();
    if (!S) return;
    if (!S.senzori) {
      if (chei.length < MIN_SENZORI) return;      // asteptam sa se umple lista
      S.senzori = chei;
      S.A = acumulator(cel.length, chei.length);
    }
    /* toti senzorii inghetati trebuie sa aiba valoare, altfel media lor
       sare si abaterile ies strambe */
    for (var q = 0; q < S.senzori.length; q++) if (T[S.senzori[q]] === undefined) return;
    if (cel.length !== S.A.nc) return;

    var acum = Date.now();
    if (acum - ultimulPas < PAS) return;
    ultimulPas = acum;

    var sum = 0, i;
    for (i = 0; i < cel.length; i++) sum += cel[i];
    var medie = sum / cel.length;
    var d = [];
    for (i = 0; i < cel.length; i++) d.push((cel[i] - medie) * 1000);   // mV

    var tv = S.senzori.map(function (k) { return T[k]; });
    var st = 0;
    tv.forEach(function (x) { st += x; });
    var mt = st / tv.length;
    var y = tv.map(function (x) { return x - mt; });

    adauga(S.A, d, y);
    if (L && L.nc === S.A.nc && L.ns === S.A.ns) adauga(L, d, y);

    /* probele brute intra si in fisier, ca sa se poata socoti altfel mai
       tarziu: abateri in zecimi de mV, temperaturi in zecimi de grad */
    S.probe.push({
      s: Math.round((acum - S.t0) / 1000),
      m: Math.round(medie * 10000) / 10,
      d: d.map(function (x) { return Math.round(x * 10); }),
      t: tv.map(function (x) { return Math.round(x * 10); })
    });
    if (S.probe.length > MAX_FISIER) S.probe.splice(0, S.probe.length - MAX_FISIER);

    S.km = (V.km !== undefined) ? V.km : S.km;
    arata();
  }

  /* ------------------------------------------------------------------ fisierul */
  function construieste() {
    var v = verdict(S.A, S.senzori);
    var vl = L ? verdict(L, S.senzori) : null;
    return JSON.stringify({
      unealta: "grupare.js 1",
      cand: new Date().toISOString(),
      durata_s: Math.round((Date.now() - S.t0) / 1000),
      celule: S.A ? S.A.nc : 0,
      senzori: S.senzori,
      cursa: v ? {
        probe: v.probe, ales: v.ales,
        cat: v.cat.map(function (x) { return Math.round(x * 1000) / 1000; }),
        ipoteze: v.ipoteze, fara: v.fara,
        r: v.r.map(function (rand) {
          return rand.map(function (x) { return Math.round(x * 1000) / 1000; });
        })
      } : null,
      lung: vl ? { probe: vl.probe, ales: vl.ales, ipoteze: vl.ipoteze, fara: vl.fara } : null,
      probe: S.probe
    });
  }

  function nume() {
    var d = new Date();
    return "grupare_" + d.getFullYear() + String(d.getMonth() + 1).padStart(2, "0") +
      String(d.getDate()).padStart(2, "0") + "_" + String(d.getHours()).padStart(2, "0") +
      String(d.getMinutes()).padStart(2, "0") + ".json";
  }

  function fisiere() {
    try { return JSON.parse(localStorage.getItem(CHEIE_F) || "[]") || []; }
    catch (e) { return []; }
  }
  function pune(lista) {
    try { localStorage.setItem(CHEIE_F, JSON.stringify(lista.slice(-3))); } catch (e) { }
  }

  /* trei cai, in ordinea din pagina: partajare, descarcare, textul pe ecran */
  function trimite(text, n) {
    var kb = Math.round(text.length / 1024);
    function laMana(motiv) {
      var z = G("gr-zona"), t = G("gr-text");
      if (z && t) { z.style.display = "block"; t.value = text; }
      scrie("nu s-a putut trimite (" + motiv + ") \u2014 copiaz\u0103 din caset\u0103, " + kb + " KB");
    }
    try {
      var f = new File([text], n, { type: "application/json" });
      if (navigator.canShare && navigator.canShare({ files: [f] })) {
        navigator.share({ files: [f], title: n })
          .then(function () { scrie("trimis: " + n + " (" + kb + " KB)"); })
          .catch(function () { clasic(); });
        return;
      }
    } catch (e) { }
    clasic();
    function clasic() {
      try {
        var b = new Blob([text], { type: "application/json" });
        var u = URL.createObjectURL(b), a = document.createElement("a");
        a.href = u; a.download = n; a.style.display = "none";
        document.body.appendChild(a); a.click();
        setTimeout(function () { document.body.removeChild(a); URL.revokeObjectURL(u); }, 400);
        scrie("desc\u0103rcat: " + n + " (" + kb + " KB)");
      } catch (e2) { laMana(e2.message); }
    }
  }
  function scrie(t) { var e = G("gr-stare"); if (e) e.textContent = t; }

  /* ------------------------------------------------------------ pe server
     Pagina sta pe file:// si nu are nici adresa, nici cheia serverului: ele
     stau in "Server Avatr", in SharedPreferences. Deci trimite Kotlin, prin
     puntea "Server", iar raspunsul se intoarce in window.grupareRaspuns.
     Fisierul iese din coada numai cand serverul a spus da; pana atunci se
     reincearca, la pornire si din cinci in cinci minute. */
  var urcaInCurs = null;

  function areServer() {
    try { return !!(window.Server && window.Server.configurat()); } catch (e) { return false; }
  }

  function incearcaUrcare() {
    if (urcaInCurs || !areServer()) return;
    var lista = fisiere();
    if (!lista.length) return;
    var u = lista[0];
    urcaInCurs = u.nume;
    scrie("urc " + u.nume + " pe server\u2026");
    try { window.Server.urca(u.nume, u.text); }
    catch (e) { urcaInCurs = null; scrie("nu am putut urca: " + e.message); }
  }

  window.grupareRaspuns = function (nume, ok, mesaj) {
    urcaInCurs = null;
    if (ok) {
      pune(fisiere().filter(function (x) { return x.nume !== nume; }));
      var f = G("gr-fasie");
      if (f) f.style.display = "none";
      scrie(nume + ": " + mesaj);
      setTimeout(incearcaUrcare, 1500);      // urmatorul din coada
    } else {
      scrie(nume + " a r\u0103mas \u00een coad\u0103: " + mesaj);
      aratFasie("Fi\u0219ierul n-a plecat pe server (" + mesaj + ")");
    }
  };

  function aratFasie(text) {
    var f = G("gr-fasie");
    if (!f) return;
    G("gr-fasieTxt").textContent = text;
    f.style.display = "block";
  }

  /* ------------------------------------------------------------- urcarea pe server
     Pagina nu poate urca singura: e deschisa din file:///android_asset, deci
     un fetch catre serverul tau ar da peste CORS, si adresa cu cheia stau in
     SharedPreferences, nu aici. Deci nu urcam noi, ci punem fisierul la
     ghiseu, si MainActivity il ia cand are internet, prin acelasi drum si cu
     aceeasi cheie ca starea masinii. Fisierul iese din coada numai dupa ce
     serverul a spus 200, deci o cursa nu se pierde daca ai fost prin pustie. */
  function urcate() {
    try { return JSON.parse(localStorage.getItem(CHEIE_U) || "[]") || []; }
    catch (e) { return []; }
  }
  window.AV = window.AV || {};
  window.AV.grupare = {
    /** cel mai vechi fisier netrimis, sau null */
    iaFisier: function () {
      var l = fisiere();
      if (!l.length) return null;
      return { nume: l[0].nume, text: l[0].text };
    },
    /** Kotlin confirma ca serverul l-a primit */
    trimis: function (n) {
      var l = fisiere().filter(function (x) { return x.nume !== n; });
      pune(l);
      var u = urcate();
      u.push({ nume: n, cand: Date.now() });
      try { localStorage.setItem(CHEIE_U, JSON.stringify(u.slice(-8))); } catch (e) { }
      arata();
      return true;
    },
    cate: function () { return fisiere().length; }
  };

  /* ------------------------------------------------------------------- ecranul */
  var MARCAJ = ''
    + '<div class="tl" id="gr-cutie">'
    + '  <p class="lbl" style="margin-bottom:6px">GRUPAREA CELULELOR PE SENZORII DE TEMPERATUR\u0102</p>'
    + '  <p class="mic" id="gr-rez" style="margin:0 0 8px">\u2014</p>'
    + '  <svg width="100%" viewBox="0 0 600 96" id="gr-desen"'
    + '   aria-label="Fiecare celul\u0103, colorat\u0103 dup\u0103 senzorul cu care se potrive\u0219te"></svg>'
    + '  <p class="mic" id="gr-blocuri" style="margin:6px 0 0">\u2014</p>'
    + '  <p class="mic" id="gr-lung" style="margin:6px 0 0;color:var(--text3)">\u2014</p>'
    + '  <p class="mic" id="gr-server" style="margin:4px 0 0;color:var(--text3)">\u2014</p>'
    + '  <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:10px">'
    + '    <button class="btn" id="gr-trimite">Trimite fi\u0219ierul</button>'
    + '    <button class="btn" id="gr-acum">\u00cencheie cursa acum</button>'
    + '    <button class="btn" id="gr-sterge">\u0218terge datele</button>'
    + '  </div>'
    + '  <p class="mic" id="gr-stare" style="margin:8px 0 0">\u2014</p>'
    + '  <div id="gr-zona" style="display:none;margin-top:8px">'
    + '    <textarea id="gr-text" style="width:100%;height:120px;font-size:11px"></textarea></div>'
    + '</div>';

  var FASIE = ''
    + '<div id="gr-fasie" style="position:fixed;left:8px;right:8px;bottom:8px;z-index:60;'
    + 'background:var(--panou);border:1px solid var(--linie);border-radius:14px;'
    + 'padding:10px 12px;display:none;box-shadow:0 6px 24px rgba(0,0,0,.18)">'
    + '  <p class="mic" id="gr-fasieTxt" style="margin:0 0 8px">\u2014</p>'
    + '  <div style="display:flex;gap:8px">'
    + '    <button class="btn" id="gr-fasieDa">Trimite acum</button>'
    + '    <button class="btn" id="gr-fasieNu">Mai t\u00e2rziu</button>'
    + '  </div></div>';

  function arata() {
    if (!G("gr-rez") || !S) return;
    var v = verdict(S.A, S.senzori);
    var min = S.A ? Math.round((Date.now() - S.t0) / 60000) : 0;
    G("gr-rez").textContent = (S.A ? S.A.n : 0) + " probe \u00b7 " + min + " min \u00b7 " + rezumat(v);

    var d = '';
    if (v && S.senzori) {
      var i = v.ipoteze[0], nc = v.ales.length;
      var lat = 560 / nc;
      for (var q = 0; q < nc; q++) {
        var s = S.senzori.indexOf(v.ales[q]);
        var h = Math.max(4, Math.min(46, Math.abs(v.cat[q]) * 46));
        d += '<rect x="' + (20 + q * lat).toFixed(1) + '" y="' + (56 - h).toFixed(1) +
          '" width="' + Math.max(1.2, lat - 0.4).toFixed(1) + '" height="' + h.toFixed(1) +
          '" fill="' + (CULORI[s % CULORI.length]) + '" opacity="' +
          (v.cat[q] > 0.35 ? "1" : "0.45") + '"/>';
      }
      d += '<line x1="20" y1="56" x2="580" y2="56" stroke="#C7CFD9" stroke-width="1.4"/>';
      if (i) {
        for (var b = i.marime; b < nc; b += i.marime) {
          d += '<line x1="' + (20 + b * lat).toFixed(1) + '" y1="4" x2="' + (20 + b * lat).toFixed(1) +
            '" y2="64" stroke="#5C6B7A" stroke-width="1" stroke-dasharray="3 3" opacity=".7"/>';
        }
      }
      d += '<text x="20" y="80" fill="#5C6B7A" font-size="14">celula 1</text>' +
        '<text x="580" y="80" text-anchor="end" fill="#5C6B7A" font-size="14">celula ' + nc + '</text>' +
        '<text x="300" y="92" text-anchor="middle" fill="#5C6B7A" font-size="13">' +
        '\u00een\u0103l\u021bimea barei = c\u00e2t de sigur\u0103 e potrivirea</text>';
      G("gr-blocuri").textContent = i ? i.blocuri.map(function (x) {
        return "celulele " + x.de + "\u2013" + x.la + " \u2192 " + x.senzor + " (" + x.vot + ")";
      }).join(" \u00b7 ") : "\u2014";
    } else {
      G("gr-blocuri").textContent = "\u2014";
    }
    G("gr-desen").innerHTML = d;

    var coada = fisiere().length;
    var unde = areServer()
      ? ("pleac\u0103 singur pe server" + (coada ? ", " + coada + " \u00een coad\u0103" : ""))
      : "serverul nu e pus \u00een Server Avatr, deci fi\u0219ierul se trimite cu m\u00e2na";
    var vl = L ? verdict(L, S.senzori) : null;
    G("gr-lung").textContent = unde + " \u00b7 " + (vl
      ? "adunat peste curse: " + vl.probe + " probe \u00b7 " + rezumat(vl)
      : "adunat peste curse: se str\u00e2nge \u00eenc\u0103");

    var coada = fisiere(), u = urcate();
    var t = "";
    if (u.length) {
      var ult = new Date(u[u.length - 1].cand);
      t = u.length + " urcate pe server, ultimul la " +
        String(ult.getHours()).padStart(2, "0") + ":" +
        String(ult.getMinutes()).padStart(2, "0");
    } else {
      t = "nimic urcat \u00eenc\u0103";
    }
    t += coada.length ? " \u00b7 " + coada.length + " a\u0219teapt\u0103 s\u0103 urce" : " \u00b7 coada e goal\u0103";
    var es = G("gr-server");
    if (es) es.textContent = t;
    var f = G("gr-fasie");
    if (f && !coada.length) f.style.display = "none";
  }

  /* ------------------------------------------------------ pornirea si inchiderea */
  function cursaNoua() {
    S = { t0: Date.now(), probe: [], senzori: null, A: null, km: null };
    inchisa = false;
    ultimulPas = 0;
  }

  function inchide(deMana) {
    if (!S || inchisa) return;
    inchisa = true;
    if (!S.A || S.A.n < MIN_PROBE) { cursaNoua(); return; }
    var text = construieste(), n = nume();
    var lista = fisiere();
    lista.push({ nume: n, cand: Date.now(), kb: Math.round(text.length / 1024), text: text });
    pune(lista);
    salveazaLung();
    if (areServer()) {
      scrie("cursa s-a \u00eencheiat, " + S.A.n + " probe \u2014 urc pe server");
      incearcaUrcare();
    } else {
      aratFasie("Cursa s-a \u00eencheiat \u00b7 " + S.A.n + " probe \u00b7 " +
        rezumat(verdict(S.A, S.senzori)));
    }
    if (deMana) scrie("fi\u0219ier preg\u0103tit: " + n);
    cursaNoua();
  }

  function salveazaLung() {
    if (!L) return;
    try { localStorage.setItem(CHEIE_L, JSON.stringify(inSir(L))); } catch (e) { }
  }

  function bate() {
    var V = stare();
    if (V && V.celule && V.celule.length > 20) {
      var semn = V.celule.length + ":" + V.celule[0] + ":" + V.celule[V.celule.length - 1];
      if (semn !== semnCel) { semnCel = semn; ultimaCitire = Date.now(); }
    }
    if (!S) cursaNoua();
    if (ultimaCitire && Date.now() - ultimaCitire > TACERE) inchide(false);
    else if (ultimaCitire) { inchisa = false; proba(); }
  }

  /* ------------------------------------------------------------------ pornirea */
  var STIL = ''
    + '#gr-cutie .btn,#gr-fasie .btn{font:inherit;font-size:13px;font-weight:600;'
    + '  color:var(--text2);background:var(--panou);border:1px solid var(--linie);'
    + '  border-radius:11px;padding:7px 12px;cursor:pointer}'
    + '#gr-cutie .btn:focus-visible,#gr-fasie .btn:focus-visible{outline:2px solid var(--rece);'
    + '  outline-offset:2px}'
    + '#gr-fasie #gr-fasieDa{background:var(--rece);border-color:var(--rece);color:#fff}';

  function porneste() {
    var sect = G("p-celule");
    if (!sect || G("gr-cutie")) return;
    var st = document.createElement("style");
    st.textContent = STIL;
    document.head.appendChild(st);
    sect.insertAdjacentHTML("beforeend", MARCAJ);
    document.body.insertAdjacentHTML("beforeend", FASIE);

    try {
      var o = JSON.parse(localStorage.getItem(CHEIE_L) || "null");
      L = dinSir(o);
    } catch (e) { L = null; }

    cursaNoua();

    G("gr-trimite").addEventListener("click", function () {
      var lista = fisiere();
      if (!lista.length) {
        if (S && S.A && S.A.n >= MIN_PROBE) { trimite(construieste(), nume()); return; }
        scrie("nu e nimic de trimis \u2014 mai adun\u0103 probe");
        return;
      }
      if (areServer()) { incearcaUrcare(); return; }
      var u = lista[lista.length - 1];
      trimite(u.text, u.nume);
    });
    G("gr-acum").addEventListener("click", function () { inchide(true); arata(); });
    G("gr-sterge").addEventListener("click", function () {
      try { localStorage.removeItem(CHEIE_F); localStorage.removeItem(CHEIE_L); } catch (e) { }
      L = null; cursaNoua(); arata();
      scrie("datele au fost \u0219terse");
    });
    G("gr-fasieDa").addEventListener("click", function () {
      var lista = fisiere();
      if (lista.length) trimite(lista[lista.length - 1].text, lista[lista.length - 1].nume);
      G("gr-fasie").style.display = "none";
    });
    G("gr-fasieNu").addEventListener("click", function () {
      G("gr-fasie").style.display = "none";
    });

    var lista = fisiere();
    if (lista.length) {
      if (areServer()) incearcaUrcare();
      else aratFasie("Ai " + lista.length + " fi\u0219ier(e) netrimise, ultimul " +
        lista[lista.length - 1].nume);
    }

    setInterval(incearcaUrcare, 5 * 60000);
    setInterval(bate, 2000);
    setInterval(arata, 10000);
    arata();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", porneste);
  } else {
    porneste();
  }
})();
