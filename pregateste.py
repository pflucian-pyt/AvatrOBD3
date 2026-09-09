#!/usr/bin/env python3
"""
pregateste.py — pregateste un HTML Avatr Monitor pentru aplicatia Android.

Folosire:
    python3 pregateste.py Avatr_Monitor_v24.html

Ce face, si de ce:

1. Leaflet din assets, nu de pe unpkg.com. Harta nu mai depinde de un server
   strain ca sa porneasca. Dalele vin tot din retea, biblioteca nu.

2. Scoate crossorigin de pe cele doua etichete Leaflet. Atributul avea rost cat
   fisierele veneau de pe unpkg; pentru un fisier local cere verificari CORS
   fara rost si poate opri incarcarea de tot. Fara el, harta ramane goala si
   nimic nu spune de ce.

3. Adauga android.css si punte.js in <head>, si android-redare.js la sfarsit.

Restul fisierului ramane neatins, octet cu octet.
"""
import sys, os, shutil, re

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "app", "src", "main", "assets")

def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(1)
    sursa = sys.argv[1]
    h = open(sursa, encoding="utf-8").read()
    orig = h

    # 1 + 2: Leaflet local, fara crossorigin
    h = h.replace('href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"',
                  'href="leaflet/leaflet.css"')
    h = h.replace('src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"',
                  'src="leaflet/leaflet.js"')
    if "unpkg.com" in h:
        print("ATENTIE: a ramas o trimitere la unpkg.com — verifica manual")

    h = re.sub(r'(<(?:link|script)[^>]*leaflet/leaflet\.(?:css|js)"[^>]*?)\s+crossorigin="[^"]*"',
               r'\1', h)

    # 3: fisierele noastre
    if "punte.js" not in h:
        cap = ('<!-- Android: asezare stransa si Bluetooth prin punte -->\n'
               '<link rel="stylesheet" href="android.css">\n'
               '<script src="punte.js"></script>\n')
        if h.count("</head>") != 1:
            print("EROARE: nu gasesc un singur </head>"); sys.exit(1)
        h = h.replace("</head>", cap + "</head>", 1)

    if "android-redare.js" not in h:
        if h.count("</body>") != 1:
            print("EROARE: nu gasesc un singur </body>"); sys.exit(1)
        h = h.replace("</body>", '<script src="android-redare.js"></script>\n</body>', 1)

    tinta = os.path.join(ASSETS, "index.html")
    open(tinta, "w", encoding="utf-8").write(h)

    print(f"gata: {os.path.basename(sursa)} -> app/src/main/assets/index.html")
    print(f"      {len(orig)} -> {len(h)} octeti")
    for c in ("crossorigin", "unpkg.com"):
        print(f"      {c}: {h.count(c)} aparitii ramase")

if __name__ == "__main__":
    main()
