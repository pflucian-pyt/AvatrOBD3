/*
 * bord2.js — ecranul BORD in forma noua: consumul cu istoric strans.
 *
 * Fisierul asta nu atinge nici o socoteala a paginii. La pornire ia tot ce era
 * in <section id="p-bord">, il pune intr-un container ascuns si deseneaza
 * deasupra ecranul nou. Elementele vechi rasman in pagina, deci fiecare
 * G("e-net").textContent = ... al paginii merge la fel ca inainte, iar ecranul
 * din masina, care citeste figurile din SVG-ul "e-bara", nu observa nimic.
 *
 * Cifrele de aici sunt citite din chiar elementele in care pagina le-a scris:
 *   autoKm, socTxt            -> autonomia si bateria din cap
 *   e-val + e-um              -> consumul de acum, o proba pe secunda
 *   e-net, e-brut, e-regen    -> net / consumat / recuperat, in kWh/100
 *   e-medieC, e-medieR        -> media sesiunii si recuperarea medie, citite
 *                                din chiar pozitia marcajelor de pe bara
 *                                veche, ca sa nu existe a doua formula
 *   a-mediaLunga              -> media lunga, adunata din cursele salvate
 *   d-010D, d-vmed, d-c5, d-c5tend, d-energie, d-recup, d-bord,
 *   sursa, sursaNota, stareMasina, stareNota  -> cele cinci casute de jos
 *
 * Axa timpului nu e liniara, si asta e tot rostul ecranului:
 *   · prima linie, lipita de marginea din stanga, e cursa anterioara;
 *   · fiecare secunda adauga o linie noua la dreapta;
 *   · ultima treime din latime tine ultimele doua minute, linie cu linie;
 *   · restul tine toata sesiunea, strans logaritmic: cu cat o clipa e mai
 *     veche, cu atat sta mai la stanga si mai inghesuita. Cand o coloana de un
 *     punct acopera mai multe secunde, linia arata media lor, iar umbra din
 *     spate arata varfurile, ca sa nu se piarda franarile.
 *
 * Tot ecranul incape fara derulare: sectiunea e o coloana flexibila, graficul
 * ia ce rasmane, iar SVG-ul isi socoteste viewBox-ul din inaltimea masurata.
 */
(function () {
  "use strict";

  var FEREASTRA = 120;        // secundele desenate linie cu linie
  var PARTE_NOUA = 0.35;      // cat din latime primesc
  var TAU = 45;               // cat de tare se strange trecutul
  var SUS = 48, JOS = -16;    // kWh/100 la marginile de sus si de jos
  var MAXIM = 3 * 3600;       // trei ore de probe
  var CHEIE_A = "bord2Anterior", CHEIE_I = "bord2Istoric";

  var probe = [], anterior = null, kmAnt = null, sect = null, ceas = null;
  var coloane = [];

  function G(id) { return document.getElementById(id); }
  function txt(id) { var e = G(id); return e ? (e.textContent || "").trim() : ""; }
  function culoareA(id) { var e = G(id); return e && e.style && e.style.color ? e.style.color : ""; }

  function nrDin(id) {
    var t = txt(id).replace("\u2212", "-").replace(",", ".").replace(/[^0-9.\-]/g, "");
    var v = parseFloat(t);
    return isFinite(v) ? v : null;
  }
  function nr(v, z) {
    return (v < 0 ? "\u2212" : "") + Math.abs(v).toFixed(z).replace(".", ",");
  }
  function durata(s) {
    if (s < 60) return s + " s";
    var m = Math.floor(s / 60);
    if (m < 60) return m + " min";
    return Math.floor(m / 60) + " h " + (m % 60) + " min";
  }

  /* ------------------------------------------------------------------ stilul */
  var STIL = ''
    + '#p-bord.on{display:flex;flex-direction:column;height:100%;min-height:0;'
    + '  animation:none}'
    + 'body.b2-activ .corp{overflow:hidden}'
    + '.b2{display:flex;flex-direction:column;flex:1 1 auto;min-height:0;gap:4px}'
    + '.b2 .b2-cap{display:flex;justify-content:space-between;align-items:flex-end;'
    + '  gap:10px;flex:0 0 auto}'
    + '.b2 .b2-cit{display:flex;align-items:baseline;gap:5px;min-width:0}'
    + '.b2 .b2-cit.dr{flex-direction:row-reverse}'
    + '.b2 .b2-et{font-size:clamp(9px,1.25vmin,15px);color:var(--text3)}'
    + '.b2 .b2-n{font-size:clamp(26px,4.4vmin,54px);font-weight:700;line-height:1;'
    + '  letter-spacing:-.025em;font-variant-numeric:tabular-nums}'
    + '.b2 .b2-u{font-size:clamp(11px,1.5vmin,18px);font-weight:600;color:var(--text3)}'
    + '.b2 .b2-graf{flex:1 1 auto;min-height:96px;position:relative}'
    + '.b2 .b2-graf svg{position:absolute;inset:0;width:100%;height:100%;'
    + '  display:block;touch-action:none}'
    + '.b2 .b2-pod{flex:0 0 auto;display:flex;gap:clamp(8px,2vw,26px);align-items:baseline;'
    + '  border-top:1px solid var(--linie);padding-top:3px}'
    + '.b2 .b2-pod .p{display:flex;align-items:baseline;gap:4px;min-width:0}'
    + '.b2 .b2-pod .v{font-size:clamp(15px,2.3vmin,28px);font-weight:700;'
    + '  font-variant-numeric:tabular-nums;letter-spacing:-.02em}'
    + '.b2 .b2-pod .k{font-size:clamp(9px,1.2vmin,14px);color:var(--text3);white-space:nowrap}'
    + '.b2 .b2-pod .gol{flex:1 1 auto}'
    + '.b2 .b2-casete{flex:0 0 auto;display:grid;gap:4px;'
    + '  grid-template-columns:repeat(5,minmax(0,1fr))}'
    + '.b2 .cs{background:var(--panou);border:1px solid var(--linie);border-radius:10px;'
    + '  padding:4px 6px;min-width:0}'
    + '.b2 .cs .e{margin:0;font-size:clamp(7px,.95vmin,11px);font-weight:600;letter-spacing:.04em;'
    + '  text-transform:uppercase;color:var(--text3);white-space:nowrap;overflow:hidden;'
    + '  text-overflow:ellipsis}'
    + '.b2 .cs .v{margin:1px 0 0;font-weight:700;font-variant-numeric:tabular-nums;'
    + '  letter-spacing:-.02em;font-size:clamp(13px,2.1vmin,26px);line-height:1.05;'
    + '  white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.b2 .cs .v.mica{font-size:clamp(10px,1.5vmin,18px)}'
    + '.b2 .cs .u{font-size:clamp(8px,1vmin,12px);font-weight:600;color:var(--text3)}'
    + '.b2 .cs .n{margin:1px 0 0;font-size:clamp(7px,.95vmin,12px);color:var(--text2);'
    + '  line-height:1.2;overflow:hidden}';

  /* ----------------------------------------------------------------- marcajul */
  var MARCAJ = ''
    + '<div class="b2-cap">'
    + '  <div class="b2-cit"><span class="b2-et">autonomie</span>'
    + '    <span class="b2-n" id="b2-km">\u2014</span><span class="b2-u">km</span></div>'
    + '  <div class="b2-cit dr"><span class="b2-et">baterie</span>'
    + '    <span class="b2-n" id="b2-soc">\u2014</span><span class="b2-u">%</span></div>'
    + '</div>'
    + '<div class="b2-graf" id="b2-graf">'
    + '  <svg id="b2-sv" preserveAspectRatio="xMidYMid meet" viewBox="0 0 1200 400"'
    + '   role="img" aria-label="Consumul instantaneu, o linie pe secunda">'
    + '    <g id="b2-fund"></g><g id="b2-serii"></g><g id="b2-ref"></g>'
    + '    <g id="b2-ax"></g><g id="b2-cursor"></g></svg>'
    + '</div>'
    + '<div class="b2-pod">'
    + '  <div class="p"><span class="v" id="b2-net">\u2014</span><span class="k">net kWh/100</span></div>'
    + '  <div class="p"><span class="v" id="b2-brut" style="color:var(--cald)">\u2014</span>'
    + '    <span class="k">consumat</span></div>'
    + '  <div class="p"><span class="v" id="b2-regen" style="color:var(--verde)">\u2014</span>'
    + '    <span class="k">recuperat</span></div>'
    + '  <div class="gol"></div>'
    + '  <div class="p"><span class="v" id="b2-kmses">\u2014</span><span class="k">km</span></div>'
    + '  <div class="p"><span class="v" id="b2-durata">\u2014</span><span class="k">de la plecare</span></div>'
    + '</div>'
    + '<div class="b2-casete">'
    + '  <div class="cs"><p class="e">viteza</p>'
    + '    <p class="v"><span id="b2-viteza">\u2014</span> <span class="u">km/h</span></p>'
    + '    <p class="n">medie <span id="b2-vmed">\u2014</span></p></div>'
    + '  <div class="cs"><p class="e">ultimele 5 min</p>'
    + '    <p class="v"><span id="b2-c5">\u2014</span> <span class="u">kWh/100</span></p>'
    + '    <p class="n" id="b2-c5tend">\u2014</p></div>'
    + '  <div class="cs"><p class="e">energie sesiune</p>'
    + '    <p class="v"><span id="b2-energie">\u2014</span> <span class="u">kWh</span></p>'
    + '    <p class="n"><span id="b2-recup">\u2014</span> rec. \u00b7 <span id="b2-bord">\u2014</span> bord</p></div>'
    + '  <div class="cs"><p class="e">sursa</p>'
    + '    <p class="v mica" id="b2-sursa">\u2014</p>'
    + '    <p class="n" id="b2-sursanota">\u2014</p></div>'
    + '  <div class="cs"><p class="e">stare</p>'
    + '    <p class="v mica" id="b2-stare">\u2014</p>'
    + '    <p class="n" id="b2-starenota">\u2014</p></div>'
    + '</div>';

  /* ------------------------------------------------------------- geometria vie */
  function geo() {
    var box = G("b2-graf");
    var w = Math.max(160, box.clientWidth || 600);
    var h = Math.max(96, box.clientHeight || 240);
    var H = Math.max(190, Math.round(1200 * h / w));
    var F = 1200 / w;                       // un punct desenat = F puncte reale
    var g = {
      H: H,
      mic: Math.max(9, Math.round(11 * F)),
      ax: Math.max(9, Math.round(12 * F)),
      ref: Math.max(11, Math.round(14 * F)),
      acum: Math.max(20, Math.round(26 * F))
    };
    g.l = Math.max(76, Math.round(3.1 * g.mic + 14));
    g.r = 1200 - Math.max(44, Math.round(1.9 * g.ax + 14));
    g.sus = Math.round(g.mic * 0.6);
    g.jos = H - (g.mic + Math.round(g.mic * 0.8));
    g.latAnt = Math.max(14, Math.round(g.mic * 1.3));
    g.golAnt = Math.max(12, Math.round(g.mic * 1.1));
    g.x0 = g.l + g.latAnt + g.golAnt;
    g.span = Math.max(60, g.r - g.x0);
    g.latNoua = g.span * PARTE_NOUA;
    g.latVeche = g.span - g.latNoua;
    g.rupt = g.r - g.latNoua;
    g.y = function (v) {
      var c = Math.max(JOS, Math.min(SUS, v));
      return g.sus + (SUS - c) * (g.jos - g.sus) / (SUS - JOS);
    };
    g.zero = g.y(0);
    g.xVarsta = function (varsta, varstaMax) {
      if (varsta <= FEREASTRA) return g.r - (varsta / FEREASTRA) * g.latNoua;
      var vm = Math.max(varstaMax, FEREASTRA + 1);
      var k = Math.log(1 + (varsta - FEREASTRA) / TAU) / Math.log(1 + (vm - FEREASTRA) / TAU);
      return g.rupt - Math.min(1, k) * g.latVeche;
    };
    return g;
  }

  function culoare(v, mtot) {
    if (v < 0) return "var(--verde)";
    return (mtot !== null && v > mtot + 9) ? "var(--cald)" : "var(--rece)";
  }

  /* -------------------------------------------------------------- marcajele vechi
     Pozitia marcajului de pe bara veche se intoarce in valoare, ca sa nu
     existe a doua formula pentru media sesiunii. Bara paginii: zero la 200,
     45 kWh/100 la 600, iar 25 recuperare la 0. */
  function dinMarcaj(id) {
    var e = G(id);
    if (!e) return null;
    if (parseFloat(e.style.opacity || "1") < 0.5) return null;
    var m = /translate\(\s*(-?[\d.]+)/.exec(e.getAttribute("transform") || "");
    if (!m) return null;
    var x = parseFloat(m[1]);
    if (!isFinite(x)) return null;
    return x >= 200 ? (x - 200) / 400 * 45 : -((200 - x) / 200 * 25);
  }

  /* consumul de acum: numai cand pagina il da in kWh/100. La stat pe loc ea
     trece pe kW, si acolo kWh/100 nu are inteles, deci punem zero. */
  function instant() {
    var v = nrDin("e-val");
    if (v === null) return null;
    return txt("e-um").indexOf("100") < 0 ? 0 : v;
  }

  /* ------------------------------------------------------------------- desenul */
  function deseneaza() {
    if (!sect || !G("b2-graf")) return;
    var g = geo(), n = probe.length;
    var varstaMax = Math.max(n, FEREASTRA + 1);
    var mtot = nrDin("a-mediaLunga");
    coloane = [];

    G("b2-sv").setAttribute("viewBox", "0 0 1200 " + g.H);

    /* fundul: banda ferestrei vii, caroiajul, linia lui zero */
    var f = '<rect x="' + g.rupt.toFixed(1) + '" y="' + g.sus + '" width="' +
      (g.r - g.rupt).toFixed(1) + '" height="' + (g.jos - g.sus).toFixed(1) +
      '" fill="var(--rece)" opacity=".06"/>';
    [15, 30, 45, -15].forEach(function (t) {
      f += '<line x1="' + g.l + '" y1="' + g.y(t).toFixed(1) + '" x2="' + g.r + '" y2="' +
        g.y(t).toFixed(1) + '" stroke="var(--linie)" stroke-width="1"/>';
    });
    G("b2-fund").innerHTML = f;

    /* seriile */
    var cai = { v: {}, n: {} };
    function pune(zona, cul, xx, val) {
      cai[zona][cul] = (cai[zona][cul] || '') +
        'M' + xx.toFixed(2) + ' ' + g.zero.toFixed(1) + 'V' + g.y(val).toFixed(1);
    }
    var s = '', umbraP = '', umbraN = '', contur = '';

    /* prima linie: cursa anterioara */
    var xa = g.l + g.latAnt / 2;
    if (anterior !== null) {
      s += '<rect x="' + g.l + '" y="' + Math.min(g.zero, g.y(anterior)).toFixed(1) +
        '" width="' + g.latAnt + '" height="' + Math.abs(g.y(anterior) - g.zero).toFixed(1) +
        '" fill="var(--text3)" opacity=".22"/>' +
        '<line x1="' + xa.toFixed(1) + '" y1="' + g.zero.toFixed(1) + '" x2="' + xa.toFixed(1) +
        '" y2="' + g.y(anterior).toFixed(1) + '" stroke="var(--text2)" stroke-width="4"/>';
    }
    s += '<line x1="' + (g.x0 - g.golAnt / 2).toFixed(1) + '" y1="' + g.sus + '" x2="' +
      (g.x0 - g.golAnt / 2).toFixed(1) + '" y2="' + (g.jos + 6) + '" stroke="var(--linie)"' +
      ' stroke-width="1.5" stroke-dasharray="3 5"/>';

    /* istoricul strans */
    var strans = {}, chei = [];
    for (var i = 0; i < n; i++) {
      var varsta = n - i;
      if (varsta <= FEREASTRA) continue;
      var col = Math.round(g.xVarsta(varsta, varstaMax));
      var q = strans[col];
      if (!q) { strans[col] = q = { s: 0, k: 0, min: 0, max: 0, v0: varsta, v1: varsta }; chei.push(col); }
      var val = probe[i];
      q.s += val; q.k++; q.v1 = varsta;
      if (val < q.min) q.min = val;
      if (val > q.max) q.max = val;
    }
    chei.forEach(function (col) {
      var q = strans[col], med = q.s / q.k;
      if (q.k > 1) {
        if (q.max > 0.3) umbraP += 'M' + col + ' ' + g.zero.toFixed(1) + 'V' + g.y(q.max).toFixed(1);
        if (q.min < -0.3) umbraN += 'M' + col + ' ' + g.zero.toFixed(1) + 'V' + g.y(q.min).toFixed(1);
      }
      pune("v", culoare(med, mtot), col, med);
      coloane.push({ x: col, v: med, varsta: (q.v0 + q.v1) / 2, n: q.k });
    });

    /* fereastra vie: chiar liniile secunda cu secunda */
    var pas = g.latNoua / FEREASTRA, lat = Math.max(0.42, pas * 0.62);
    for (var j = Math.max(0, n - FEREASTRA); j < n; j++) {
      var vr = n - j, xx = g.r - (vr / FEREASTRA) * g.latNoua + pas / 2, vl = probe[j];
      contur += (contur ? 'L' : 'M') + (xx - pas / 2).toFixed(2) + ' ' + g.y(vl).toFixed(1) +
        'L' + (xx + pas / 2).toFixed(2) + ' ' + g.y(vl).toFixed(1);
      if (Math.abs(vl) < 0.15) continue;
      pune("n", culoare(vl, mtot), xx, vl);
      coloane.push({ x: xx, v: vl, varsta: vr, n: 1 });
    }

    if (umbraP) s += '<path d="' + umbraP + '" stroke="var(--rece)" stroke-width="1" opacity=".22" fill="none"/>';
    if (umbraN) s += '<path d="' + umbraN + '" stroke="var(--verde)" stroke-width="1" opacity=".22" fill="none"/>';
    [["v", 1.05], ["n", lat]].forEach(function (z) {
      Object.keys(cai[z[0]]).forEach(function (cul) {
        s += '<path d="' + cai[z[0]][cul] + '" stroke="' + cul + '" stroke-width="' + z[1].toFixed(2) +
          '" fill="none" shape-rendering="geometricPrecision"/>';
      });
    });
    if (contur) s += '<path d="' + contur + '" stroke="var(--text2)" stroke-width="1.2" fill="none" opacity=".32"/>';

    /* clipa de acum */
    var acum = n ? probe[n - 1] : null;
    if (acum !== null) {
      var yA = g.y(acum), cul = culoare(acum, mtot);
      s += '<line x1="' + g.r + '" y1="' + g.zero.toFixed(1) + '" x2="' + g.r + '" y2="' +
        yA.toFixed(1) + '" stroke="' + cul + '" stroke-width="3.5"/>' +
        '<circle cx="' + g.r + '" cy="' + yA.toFixed(1) + '" r="' + (g.acum * 0.16).toFixed(1) +
        '" fill="' + cul + '"/>';
      var latC = g.acum * 3.5, inC = g.acum * 1.35;
      s += '<rect x="' + (g.r - latC - 3).toFixed(1) + '" y="' + (g.sus + 2) + '" width="' + latC.toFixed(1) +
        '" height="' + inC.toFixed(1) + '" rx="' + (g.acum * 0.24).toFixed(1) +
        '" fill="var(--panou)" opacity=".9"/>' +
        '<text x="' + (g.r - 9) + '" y="' + (g.sus + inC * 0.78).toFixed(1) + '" text-anchor="end" font-size="' +
        g.acum + '" font-weight="700" fill="' + cul + '">' +
        (Math.abs(acum) < 0.2 ? "0" : nr(acum, 1)) + '</text>' +
        '<text x="' + (g.r - latC + g.mic * 0.6).toFixed(1) + '" y="' + (g.sus + inC * 0.72).toFixed(1) +
        '" font-size="' + g.mic + '" fill="var(--text3)">acum</text>';
    } else {
      s += '<text x="' + ((g.l + g.r) / 2) + '" y="' + (g.zero - g.mic).toFixed(1) +
        '" text-anchor="middle" font-size="' + g.mic + '" fill="var(--text3)">' +
        'graficul se adun\u0103 de la prima citire</text>';
    }
    G("b2-serii").innerHTML = s;

    /* liniile de reper, cu numele lor in stanga */
    var repere = [], r = '';
    function reper(val, nume, cul, gros) {
      if (val === null || val === undefined || !isFinite(val)) return;
      repere.push({ y: g.y(val), val: val, nume: nume, cul: cul, gros: gros });
    }
    reper(dinMarcaj("e-medieC"), "medie \u2191", "var(--text)", 2.2);
    reper(mtot, "M.tot", "var(--cald)", 1.8);
    reper(dinMarcaj("e-medieR"), "medie \u2193", "var(--verde)", 2.2);

    repere.forEach(function (p) {
      r += '<line x1="' + (g.l - 6) + '" y1="' + p.y.toFixed(1) + '" x2="' + g.r + '" y2="' +
        p.y.toFixed(1) + '" stroke="' + p.cul + '" stroke-width="' + p.gros +
        '" stroke-dasharray="7 6" opacity=".9"/>';
    });
    var sir = repere.slice().sort(function (a, b) { return a.y - b.y; });
    var MIN = g.mic + g.ref + 6, ultim = -1e9;
    sir.forEach(function (p) {
      p.ye = Math.max(p.y, ultim + MIN, g.sus + g.mic + 4);
      ultim = p.ye;
      r += '<text x="' + (g.l - 12) + '" y="' + (p.ye - g.ref * 0.35).toFixed(1) +
        '" text-anchor="end" font-size="' + g.mic + '" fill="' + p.cul + '">' + p.nume + '</text>' +
        '<text x="' + (g.l - 12) + '" y="' + (p.ye + g.ref * 0.82).toFixed(1) +
        '" text-anchor="end" font-size="' + g.ref + '" font-weight="600" fill="' + p.cul + '">' +
        nr(Math.abs(p.val), 1) + '</text>';
    });
    G("b2-ref").innerHTML = r;

    /* axele */
    var a = '<line x1="' + g.r + '" y1="' + g.sus + '" x2="' + g.r + '" y2="' + g.jos +
      '" stroke="var(--text3)" stroke-width="1.5"/>' +
      '<line x1="' + g.l + '" y1="' + g.zero.toFixed(1) + '" x2="' + (g.r + 6) + '" y2="' +
      g.zero.toFixed(1) + '" stroke="var(--text2)" stroke-width="2.5"/>';
    [[45, "45", "var(--text3)"], [30, "30", "var(--text3)"], [15, "15", "var(--text3)"],
     [0, "0", "var(--text3)"], [-15, "15", "var(--verde)"]].forEach(function (t) {
      a += '<line x1="' + g.r + '" y1="' + g.y(t[0]).toFixed(1) + '" x2="' + (g.r + 7) + '" y2="' +
        g.y(t[0]).toFixed(1) + '" stroke="' + t[2] + '" stroke-width="1.5"/>' +
        '<text x="' + (g.r + 11) + '" y="' + (g.y(t[0]) + g.ax * 0.36).toFixed(1) + '" font-size="' +
        g.ax + '" fill="' + t[2] + '">' + t[1] + '</text>';
    });
    var josT = g.jos + g.mic + 6;
    if (anterior !== null) {
      a += '<text x="' + xa.toFixed(1) + '" y="' + josT + '" text-anchor="middle" font-size="' +
        g.mic + '" fill="var(--text3)">anterior</text>';
    }
    a += '<text x="' + g.r + '" y="' + josT + '" text-anchor="end" font-size="' + g.mic +
      '" fill="var(--text2)">acum</text>' +
      '<line x1="' + g.rupt.toFixed(1) + '" y1="' + g.sus + '" x2="' + g.rupt.toFixed(1) + '" y2="' +
      (g.jos + 8) + '" stroke="var(--text3)" stroke-width="1" stroke-dasharray="4 4" opacity=".7"/>' +
      '<text x="' + g.rupt.toFixed(1) + '" y="' + josT + '" text-anchor="middle" font-size="' + g.mic +
      '" fill="var(--text3)">' + durata(FEREASTRA) + '</text>';

    var luate = [g.r, g.rupt, xa], minD = g.mic * 4.2;
    [[300, "5 min"], [600, "10 min"], [1200, "20 min"], [1800, "30 min"],
     [3600, "1 h"], [7200, "2 h"]].forEach(function (t) {
      if (n <= t[0] + 30) return;
      var xx = g.xVarsta(t[0], varstaMax);
      if (xx < g.x0 + 8 || xx > g.rupt - minD * 0.5) return;
      for (var q = 0; q < luate.length; q++) if (Math.abs(luate[q] - xx) < minD) return;
      luate.push(xx);
      a += '<line x1="' + xx.toFixed(1) + '" y1="' + g.zero.toFixed(1) + '" x2="' + xx.toFixed(1) +
        '" y2="' + (g.jos + 8) + '" stroke="var(--linie)" stroke-width="1"/>' +
        '<text x="' + xx.toFixed(1) + '" y="' + josT + '" text-anchor="middle" font-size="' + g.mic +
        '" fill="var(--text3)">' + t[1] + '</text>';
    });
    G("b2-ax").innerHTML = a;
    deseneazaMasina(mtot);
  }

  /* ------------------------------------------------ copia pentru ecranul masinii
     Ecranul din masina nu deseneaza nimic singur: MainActivity citeste
     dreptunghiurile si liniile din SVG-ul care il cuprinde pe "e-bara", iar
     Tablou.kt le aseaza pe panza. Deci desenam graficul nou chiar acolo, in
     sistemul de 600x62 pe care il stie deja, si stingem figurile barei vechi
     una cu una — cititorul sare peste ce are display:none pe el insusi, iar
     pagina poate scrie in ele in continuare.

     Aici culorile trebuie sa fie hexa, nu var(--...): Paleta.dinPagina le
     trece prin Color.parseColor si ce nu seamana cu o culoare iese sters. */
  var CUL = {
    rece: "#0071C5", verde: "#00875A", cald: "#B25E00",
    text: "#0D1520", stins: "#5C6B7A", linie: "#D9DEE6"
  };
  var gM = null;
  var MLAT = 600, MINALT = 62, MPAS = 4;   // pasul coloanelor din trecut

  function yM(v) {
    var c = Math.max(JOS, Math.min(SUS, v));
    return (SUS - c) * MINALT / (SUS - JOS);
  }
  function linM(x1, y1, x2, y2, cul, sw) {
    return '<line x1="' + x1.toFixed(1) + '" y1="' + y1.toFixed(1) + '" x2="' + x2.toFixed(1) +
      '" y2="' + y2.toFixed(1) + '" stroke="' + cul + '" stroke-width="' + sw + '"/>';
  }

  function pregatesteMasina() {
    var b = G("e-bara");
    if (!b || !b.closest) return;
    var svg = b.closest("svg");
    if (!svg) return;
    var noduri = svg.querySelectorAll("rect,line");
    for (var i = 0; i < noduri.length; i++) noduri[i].style.display = "none";
    gM = document.createElementNS("http://www.w3.org/2000/svg", "g");
    gM.setAttribute("id", "b2-masina");
    svg.appendChild(gM);
  }

  function deseneazaMasina(mtot) {
    if (!gM) return;
    var n = probe.length, zero = yM(0);
    var x0 = 14, r = 598, span = r - x0;
    var latN = span * PARTE_NOUA, latV = span - latN, rupt = r - latN;
    var varstaMax = Math.max(n, FEREASTRA + 1);
    var o = '';

    /* caroiajul si linia lui zero */
    [15, 30].forEach(function (t) {
      o += linM(0, yM(t), r, yM(t), CUL.linie, 0.7);
    });
    o += linM(0, zero, r, zero, CUL.stins, 1.1);
    o += linM(x0 - 5, 0, x0 - 5, MINALT, CUL.linie, 0.7);

    /* cursa anterioara */
    if (anterior !== null) {
      o += '<rect x="2" y="' + Math.min(zero, yM(anterior)).toFixed(1) + '" width="9" height="' +
        Math.abs(yM(anterior) - zero).toFixed(1) + '" fill="' + CUL.stins + '" opacity="0.45"/>';
    }

    /* trecutul strans, cu un pas mai gros ca sa nu trimitem mii de figuri */
    function xM(varsta) {
      if (varsta <= FEREASTRA) return r - (varsta / FEREASTRA) * latN;
      var k = Math.log(1 + (varsta - FEREASTRA) / TAU) /
              Math.log(1 + (varstaMax - FEREASTRA) / TAU);
      return rupt - Math.min(1, k) * latV;
    }
    var sume = {}, chei = [];
    for (var i = 0; i < n; i++) {
      var varsta = n - i;
      if (varsta <= FEREASTRA) continue;
      var col = Math.round(xM(varsta) / MPAS) * MPAS;
      var q = sume[col];
      if (!q) { sume[col] = q = { s: 0, k: 0 }; chei.push(col); }
      q.s += probe[i]; q.k++;
    }
    chei.forEach(function (col) {
      var med = sume[col].s / sume[col].k;
      if (Math.abs(med) < 0.2) return;
      o += linM(col, zero, col, yM(med),
        med < 0 ? CUL.verde : ((mtot !== null && med > mtot + 9) ? CUL.cald : CUL.rece), MPAS - 0.6);
    });

    /* fereastra vie, secunda cu secunda */
    var pas = latN / FEREASTRA, sw = Math.max(0.8, pas * 0.68);
    for (var j = Math.max(0, n - FEREASTRA); j < n; j++) {
      var vl = probe[j];
      if (Math.abs(vl) < 0.2) continue;
      var xx = r - ((n - j) / FEREASTRA) * latN + pas / 2;
      o += linM(xx, zero, xx, yM(vl),
        vl < 0 ? CUL.verde : ((mtot !== null && vl > mtot + 9) ? CUL.cald : CUL.rece), sw);
    }

    /* reperele: media sesiunii, media lunga, recuperarea medie */
    [[dinMarcaj("e-medieC"), CUL.text, 1.1], [mtot, CUL.cald, 0.9],
     [dinMarcaj("e-medieR"), CUL.verde, 1.1]].forEach(function (p) {
      if (p[0] === null || !isFinite(p[0])) return;
      o += linM(0, yM(p[0]), r, yM(p[0]), p[1], p[2]);
    });

    /* clipa de acum */
    if (n) {
      var a = probe[n - 1];
      o += linM(r, zero, r, yM(a),
        a < 0 ? CUL.verde : ((mtot !== null && a > mtot + 9) ? CUL.cald : CUL.rece), 2.5);
    }

    gM.innerHTML = o;
  }

  /* ------------------------------------------------------- citirea cu degetul */
  function laDeget(ev) {
    var sv = G("b2-sv"), cutie = sv.getBoundingClientRect();
    if (!cutie.width) return;
    var g = geo();
    var xx = (ev.clientX - cutie.left) / cutie.width * 1200;
    var cel = null, dist = 1e9;
    for (var i = 0; i < coloane.length; i++) {
      var d = Math.abs(coloane[i].x - xx);
      if (d < dist) { dist = d; cel = coloane[i]; }
    }
    if (!cel || dist > g.mic) { G("b2-cursor").innerHTML = ""; return; }
    var et = nr(cel.v, 1) + " kWh/100 \u00b7 acum " + durata(Math.round(cel.varsta)) +
      (cel.n > 1 ? " \u00b7 media a " + cel.n + " s" : "");
    G("b2-cursor").innerHTML =
      '<line x1="' + cel.x.toFixed(1) + '" y1="' + g.sus + '" x2="' + cel.x.toFixed(1) + '" y2="' +
      g.jos + '" stroke="var(--text3)" stroke-width="1"/>' +
      '<circle cx="' + cel.x.toFixed(1) + '" cy="' + g.y(cel.v).toFixed(1) + '" r="3.5" fill="var(--text)"/>' +
      '<text x="' + Math.min(Math.max(cel.x, g.l + 20), g.r - 20).toFixed(1) + '" y="' +
      (g.sus + g.mic) + '" text-anchor="' + (cel.x > 700 ? "end" : "start") + '" font-size="' +
      g.mic + '" fill="var(--text)">' + et + '</text>';
  }

  /* ------------------------------------------------------------ cifrele de jos */
  function copiaza(de, la, cu) {
    var e = G(la);
    if (!e) return;
    e.textContent = txt(de) || "\u2014";
    if (cu) { var c = culoareA(de); if (c) e.style.color = c; }
  }
  function citeste() {
    G("b2-km").textContent = txt("autoKm").replace(/\s*km$/, "") || "\u2014";
    copiaza("socTxt", "b2-soc");
    copiaza("e-net", "b2-net", true);
    copiaza("e-brut", "b2-brut");
    copiaza("e-regen", "b2-regen");
    copiaza("d-km", "b2-kmses");
    copiaza("d-durata", "b2-durata");
    copiaza("d-010D", "b2-viteza");
    copiaza("d-vmed", "b2-vmed");
    copiaza("d-c5", "b2-c5");
    copiaza("d-c5tend", "b2-c5tend", true);
    copiaza("d-energie", "b2-energie");
    copiaza("d-recup", "b2-recup");
    copiaza("d-bord", "b2-bord");
    copiaza("sursa", "b2-sursa", true);
    copiaza("sursaNota", "b2-sursanota");
    copiaza("stareMasina", "b2-stare", true);
    copiaza("stareNota", "b2-starenota");
  }

  /* --------------------------------------------------------------- pastrarea */
  function salveaza() {
    try {
      var v = [];
      for (var i = 0; i < probe.length; i++) v.push(Math.round(probe[i] * 10));
      localStorage.setItem(CHEIE_I, JSON.stringify({ t: Date.now(), v: v }));
    } catch (e) { }
  }
  function incarca() {
    try {
      var a = parseFloat(localStorage.getItem(CHEIE_A));
      if (isFinite(a)) anterior = a;
      var d = JSON.parse(localStorage.getItem(CHEIE_I) || "null");
      if (d && d.v && Date.now() - d.t < 20 * 60000) {
        for (var i = 0; i < d.v.length; i++) probe.push(d.v[i] / 10);
      }
    } catch (e) { }
  }
  function inchideSesiune() {
    var m = dinMarcaj("e-medieC");
    if (m === null) m = nrDin("e-net");
    if (m !== null && m > 1) {
      anterior = m;
      try { localStorage.setItem(CHEIE_A, String(m)); } catch (e) { }
    }
    probe = [];
    salveaza();
    deseneaza();
  }

  /* ------------------------------------------------------------------ pornirea */
  function bate() {
    var v = instant();
    if (v !== null) {
      probe.push(v);
      if (probe.length > MAXIM) probe.shift();
      if (probe.length % 20 === 0) salveaza();
    }
    var km = nrDin("d-km");
    if (km !== null) {
      if (kmAnt !== null && km < kmAnt - 0.3) inchideSesiune();
      kmAnt = km;
    }
    citeste();
    deseneaza();
  }

  function porneste() {
    sect = G("p-bord");
    if (!sect || G("b2-graf")) return;

    var st = document.createElement("style");
    st.textContent = STIL;
    document.head.appendChild(st);

    var vechi = document.createElement("div");
    vechi.id = "bordVechi";
    vechi.hidden = true;
    while (sect.firstChild) vechi.appendChild(sect.firstChild);
    var nou = document.createElement("div");
    nou.className = "b2";
    nou.innerHTML = MARCAJ;
    sect.appendChild(nou);
    sect.appendChild(vechi);

    incarca();
    pregatesteMasina();

    var b = G("btnSesiune");
    if (b) b.addEventListener("click", function () { setTimeout(inchideSesiune, 60); });

    var sv = G("b2-sv");
    sv.addEventListener("pointermove", laDeget);
    sv.addEventListener("pointerleave", function () { G("b2-cursor").innerHTML = ""; });

    function activ() { document.body.classList.toggle("b2-activ", sect.classList.contains("on")); }
    activ();
    if (window.MutationObserver) {
      new MutationObserver(activ).observe(sect, { attributes: true, attributeFilter: ["class"] });
    }
    window.addEventListener("resize", deseneaza);
    if (window.ResizeObserver) new ResizeObserver(deseneaza).observe(G("b2-graf"));

    ceas = setInterval(bate, 1000);
    bate();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", porneste);
  } else {
    porneste();
  }
})();
