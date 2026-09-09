# Avatr Monitor — proiect Android complet

Tot ce trebuie ca sa iasa un APK. Interfata e ultimul `Avatr_Monitor_v23`, cu Bluetooth
prin punte, Leaflet local, asezare stransa pentru telefon, si redarea traseului
mutata langa harta.

## APK-ul, prin GitHub

1. Depozit nou pe GitHub, privat. Numele nu conteaza.
2. Dezarhiveaza acest fisier pe calculator. Intra in folderul `AvatrMonitor`.
3. Selecteaza **tot ce e inauntru** (`app`, `.github`, `build.gradle.kts`,
   `gradle.properties`, `settings.gradle.kts`, `pregateste.py`, `CITESTE.md`)
   si trage-le in pagina goala a depozitului.
   **Din Chrome** — Firefox nu trimite continutul folderelor.
4. `Commit changes`.
5. Fila `Actions`, astepti vreo 5 minute, apoi din `Artifacts` descarci
   `AvatrMonitor-APK`. Inauntru e `app-debug.apk`.

Daca `.github` e ascuns in Windows si nu-l poti trage: pe GitHub, `Add file` →
`Create new file`, la nume scrii `.github/workflows/apk.yml`, si lipesti
continutul fisierului cu acelasi nume din arhiva.

Dupa incarcare, la radacina depozitului trebuie sa vezi `app` si `.github`. Daca
lipseste vreunul, compilarea nu porneste.

## Cand vine o versiune noua de HTML

Nu se copiaza direct peste `index.html`. Trece intai prin:

    python3 pregateste.py Avatr_Monitor_v24.html

Scriptul leaga Leaflet-ul local, scoate `crossorigin` de pe cele doua etichete
Leaflet, si adauga `android.css`, `punte.js` si `android-redare.js`. Restul
fisierului ramane neatins. Apoi urci `app/src/main/assets/index.html` pe GitHub.

`crossorigin` conteaza mai mult decat pare: are rost cat fisierele vin de pe
unpkg, dar pe un fisier local cere verificari fara rost si poate opri
incarcarea de tot. Harta ramane goala si nimic nu spune de ce.

## Ce contine, pe scurt

| Fisier | Rol |
|---|---|
| `assets/index.html` | interfata, versiunea ta, adaptata de script |
| `assets/punte.js` | imitatie de Web Bluetooth peste Bluetooth-ul nativ |
| `assets/android.css` | asezare stransa, ca ecranele sa intre fara derulare |
| `assets/android-redare.js` | muta panoul de redare sub harta |
| `assets/leaflet/` | Leaflet 1.9.4 local, cu icoanele de marcaj |
| `PunteBle.kt` | Bluetooth nativ: scanare, coada de scrieri, MTU 247 |
| `PunteFisiere.kt` | exportul scrie fisier adevarat in Descarcari |
| `MainActivity.kt` | gazda WebView, permisiuni, GPS pe origine sigura |
| `ServiciuFundal.kt` | notificare permanenta, ca sistemul sa nu opreasca citirea |

## De stiut la testare

**Inchide de tot aplicatia OBDLink** inainte. Un adaptor BLE accepta o singura
legatura; daca o tine ea, nicio alta aplicatie nu poate intra.

**Prima pornire** cere apasat „Conecteaza". De la a doua, aplicatia se leaga
singura la adaptorul dovedit bun.

**Ecranul aprins.** Cand WebView-ul nu e vizibil, Android incetineste
temporizatoarele din JavaScript si interogarea se rareste. De aceea aplicatia
tine ecranul aprins. Inregistrarea cu ecranul stins ar cere mutarea buclei de
interogare din HTML in Kotlin.

**Sapte ecrane din zece** intra fara derulare. ENERGIE mai are 46px si SETARI
140px; acolo strangerea ar taia etichete sau ar face casetele de scris prea
mici, si pe niciunul nu te uiti din mers.

**Jurnalul**, daca ceva merge prost:

    adb logcat -c && adb logcat -s PunteBle:I PunteBle:W Pagina:I

In „ULTIMELE INTEROGARI" din aplicatie, un esec sub 10ms ar insemna ca o comanda
a fost respinsa fara sa plece pe radio. Unul la 1600ms e o expirare adevarata:
adaptorul sau masina nu au raspuns, si pagina reincearca singura.

## Android Auto

Pe ecranul masinii merg doua pagini, desenate pe toata suprafata:

- **Consum** — autonomie, energie sesiune, ultimele 5 minute, consum acum, bara
  de consum cu cifrele ei, cele patru casete de celule si graficul de abatere.
- **Energie** — priza, km pe priza, rezervor, km pe benzina, bara de proveninta
  a energiei, cadranul extenderului cu sarcina, lichid, admisie si tensiunea de
  bord, si cadranul cu litri, plus debit, putere, randament si autonomia
  generatorului.

Se schimba cu butonul din bara laterala sau cu o atingere pe ecran.

**De ce doua pagini.** Masurat, nu estimat: toate blocurile cerute cer vreo
1390 px inaltime, iar ecranul are vreo 900. Micsorate cat sa incapa, cifrele
ajung nefolositoare in mers.

**Zi si noapte.** Doua palete intregi, comutate de Android Auto odata cu
farurile. Nu doar fundalul: albul curat al paginii, facut pentru hartie,
orbeste noaptea, iar cenusiurile deschise ajung mai stralucitoare decat cifrele.

**Bulina de stare**, langa nume: verde cand vin raspunsuri, chihlimbariu cand
legatura sta dar OBD-ul tace, rosu la deconectare. Fara ea, ecranul ar arata
cifre vechi si ai crede ca sunt de acum.

**Cifrele si graficele nu se recalculeaza.** Se citesc din elementele in care
pagina le-a scris deja, iar barele din chiar figurile pe care pagina le-a
desenat in SVG. Daca pagina isi schimba socoteala, ecranul masinii se schimba
odata cu ea.

**Aplicatia se declara ca aplicatie de navigatie.** Android Auto da panza
intreaga doar acestora; celelalte primesc sabloane cu randuri de text, in care
casetele si cadranele nu se pot desena. Pentru uz personal, instalata direct, e
in regula. Daca gazda refuza totusi panza, ecranul nu ramane gol: dupa sase
secunde se intoarce singur la o lista simpla.

Ca sa se vada, aplicatia trebuie lasata sa ruleze desi nu vine din magazin:

1. Pe telefon, deschide aplicatia **Android Auto** (in Setari → Aplicatii, daca
   nu are pictograma proprie).
2. Apasa de sapte ori pe **Versiune**, pana scrie ca setarile de dezvoltator
   sunt pornite.
3. Meniul din dreapta sus → **Setari pentru dezvoltatori**.
4. Bifeaza **Surse necunoscute**.
5. Leaga telefonul la masina. Avatr Monitor apare in lista de aplicatii.

Aplicatia de pe telefon trebuie sa fie pornita si conectata la adaptor; ecranul
masinii doar arata ce citeste ea.
