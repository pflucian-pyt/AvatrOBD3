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
