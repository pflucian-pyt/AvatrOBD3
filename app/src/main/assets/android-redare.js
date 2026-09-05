/*
 * android-redare.js — panoul de redare, mutat langa harta.
 *
 * De ce: butonul "Reda traseul" sta pe ecranul ISTORIC, dar pasRedare()
 * deseneaza pe ecranul HARTA. Cand alegi o cursa din istoric, pagina te duce
 * singura pe HARTA — insa ca sa apesi Reda trebuie sa te intorci la ISTORIC,
 * iar de acolo desenul se face pe un ecran ascuns. Redarea chiar rula; nu se
 * vedea nimic pentru ca nu te uitai unde deseneaza.
 *
 * Reparatia e de asezare, nu de logica: panoul de revizuire trece imediat sub
 * harta. Asa, cand apesi Reda, vezi masina mergand pe traseu si dedesubt
 * valorile din chiar momentul acela.
 *
 * Nu se atinge nimic din index.html si nici o functie a paginii nu e
 * rescrisa — doar se muta un nod din DOM, cu ascultatorii lui cu tot.
 */
(function () {
  "use strict";

  function porneste() {
    var panou = document.getElementById("revizuire");
    var harta = document.getElementById("p-harta");
    if (!panou || !harta) return;

    // cartonasul care contine harta: dupa el punem panoul
    var cartHarta = null;
    var mapa = document.getElementById("mapa") || document.getElementById("svgHarta");
    if (mapa) cartHarta = mapa.closest(".tl");

    panou.classList.add("redare-android");

    if (cartHarta && cartHarta.parentNode === harta) {
      harta.insertBefore(panou, cartHarta.nextSibling);
    } else {
      harta.appendChild(panou);
    }

    // Cand panoul se aprinde (pagina ii pune display:block la alegerea unei
    // curse), aducem harta in dreptul ochilor. Fara asta, pe un ecran de
    // telefon panoul ar putea ramane sub marginea de jos.
    var obs = new MutationObserver(function () {
      if (panou.style.display !== "none" && cartHarta) {
        setTimeout(function () {
          try { cartHarta.scrollIntoView({ block: "start", behavior: "smooth" }); }
          catch (e) { }
        }, 120);
      }
    });
    obs.observe(panou, { attributes: true, attributeFilter: ["style"] });

    console.log("android-redare.js: panoul de redare mutat sub harta");
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", porneste);
  } else {
    porneste();
  }
})();
