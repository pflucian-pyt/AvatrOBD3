#!/usr/bin/env python3
"""
adauga_bord2.py — pune ecranul BORD nou intr-o pagina Avatr Monitor.

Folosire:
    python3 adauga_bord2.py app/src/main/assets/index.html

Scrie fisierul pe loc si lasa alaturi o copie "index.html.inainte" la prima
rulare. Rularea de a doua ori nu strica nimic: daca linia exista deja, se
opreste.

Peticul e o singura linie. Tot restul sta in bord2.js, care la pornire ia
continutul vechi al sectiunii "p-bord", il pune intr-un container ascuns si
deseneaza deasupra ecranul nou. Elementele vechi rasman in pagina, deci:

  · fiecare G("e-net").textContent = ... al paginii merge la fel ca inainte;
  · ecranul din masina, care citeste dreptunghiurile din SVG-ul "e-bara",
    primeste aceleasi figuri, fiindca ele se deseneaza in continuare;
  · cand vine o versiune noua de index.html, se copiaza peste si se rulează
    iar scriptul asta. Nimic din ecranul nou nu se pierde.
"""
import os
import shutil
import sys

LINIE = '<script src="bord2.js"></script>\n'


def petec(cale: str) -> int:
    if not os.path.isfile(cale):
        print("Nu gasesc fisierul:", cale)
        return 1

    with open(cale, encoding="utf-8") as f:
        text = f.read()

    if "bord2.js" in text:
        print("Pagina are deja ecranul BORD nou. Nu am schimbat nimic.")
        return 0

    if "</body>" not in text:
        print("Pagina nu are </body>. M-am oprit ca sa nu stric fisierul.")
        return 1

    inainte = cale + ".inainte"
    if not os.path.exists(inainte):
        shutil.copy2(cale, inainte)
        print("Copie de siguranta:", inainte)

    text = text.replace("</body>", LINIE + "</body>", 1)
    with open(cale, "w", encoding="utf-8") as f:
        f.write(text)

    alaturi = os.path.join(os.path.dirname(cale), "bord2.js")
    if not os.path.exists(alaturi):
        print("ATENTIE: bord2.js lipseste din", os.path.dirname(cale) or ".")
        print("Copiaza-l acolo, altfel pagina rasmane cu ecranul vechi.")
    print("Gata. Reconstruieste APK-ul si ecranul BORD e cel nou.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(petec(sys.argv[1]))
