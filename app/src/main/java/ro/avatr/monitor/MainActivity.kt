package ro.avatr.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gazda interfetei. Nicio formula si nicio logica de masina aici: acelea stau
 * in index.html, exact asa cum au fost verificate pe drum.
 *
 * Un lucru merita explicat, fiindca nu se ghiceste din cod.
 *
 * Pagina NU se incarca de la "file:///android_asset/index.html", ci de la
 * "https://appassets.androidplatform.net/assets/index.html". Adresa arata ca de
 * pe internet, dar nu iese nimic in retea: WebViewAssetLoader raspunde din
 * assets, local. Rostul e altul — Chromium da locatia doar paginilor de pe o
 * origine sigura. De la "file://" cererea de GPS poate fi respinsa tacut, si
 * ecranul HARTA ramane gol fara sa spuna de ce. De pe "https://" nu mai e
 * indoiala. Ca efect secundar, datele salvate de pagina stau pe o origine
 * stabila, deci sesiunea nu se pierde.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** cifrele de pe ecranul BORD si starea legaturii */
        /** cat timp a trecut de la ultima comanda trimisa de pagina */
        private const val VERIFICA_BUCLA =
            "String(window.__ultimaComanda ? (Date.now()-window.__ultimaComanda) : -1)"

        private const val CITIRE_BORD = """
            (function(){
              function t(id){ var e=document.getElementById(id);
                              return e ? e.textContent.trim() : ""; }
              // Starea adevarata sta in clasa led-ului: "viu" cat timp pagina
              // citeste. Textul butonului nu spune niciodata "Deconecteaza",
              // asa ca vechea verificare iesea mereu falsa si ecranul masinii
              // arata "deconectat" desi telefonul citea.
              // Starea o luam intreaga de la pagina, text si culoare. Inainte
              // o deduceam singuri, cu un numarator propriu de tacere, si
              // ecranul masinii spunea altceva decat telefonul.
              var led=document.getElementById("led");
              var st=document.getElementById("stare");
              return JSON.stringify({
                // pozitia o scrie pagina in "h-poz", ca "45.6512, 25.6012"
                lat:(function(){var e=document.getElementById("h-poz");
                  if(!e)return null; var m=/(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)/.exec(e.textContent);
                  return m?parseFloat(m[1]):null;})(),
                lon:(function(){var e=document.getElementById("h-poz");
                  if(!e)return null; var m=/(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)/.exec(e.textContent);
                  return m?parseFloat(m[2]):null;})(),
                soc:t("socTxt"), autonomie:t("autoKm"), energie:t("d-energie"),
                recup:t("d-recup"), km:t("d-km"), durata:t("d-durata"),
                c5:t("d-c5"), c5t:t("d-c5tend"),
                eVal:t("e-val"), eUm:t("e-um"), eSfat:t("e-sfat"),
                eNet:t("e-net"), eBrut:t("e-brut"), eRegen:t("e-regen"),
                eNota:t("e-medieNota"), stare:t("stareMasina"),
                ceas:t("ultimeleInterogari")||t("d-010D")+"|"+t("e-val"),
                legat: !!(led && led.className.indexOf("viu")>=0),
                eroare: !!(led && led.className.indexOf("eroare")>=0),
                stareLegatura: st ? st.textContent.trim() : ""
              });
            })()
        """

        /**
         * Ce se trimite pe server: tot ce e pe BORD, poziția, plus orice
         * element pe care pagina il marcheaza singura.
         *
         * Bucata cu "data-server" e portita pentru viitor. Cand vei gasi
         * parametrii de incuiere si de geamuri si ii vei afisa in pagina, e de
         * ajuns sa pui pe elementul acela atributul data-server="incuiat", si
         * valoarea ajunge pe server fara sa umblam iar in Kotlin.
         */
        private const val CITIRE_SERVER = """
            (function(){
              function t(id){ var e=document.getElementById(id);
                              return e ? e.textContent.trim() : ""; }
              var o = {
                soc:t("socTxt"), autonomie:t("autoKm"), autoNota:t("socNota"),
                energie:t("d-energie"), recup:t("d-recup"),
                km:t("d-km"), durata:t("d-durata"), vmed:t("d-vmed"),
                viteza:t("d-010D"), c5:t("d-c5"), c5t:t("d-c5tend"),
                eNet:t("e-net"), eBrut:t("e-brut"), eRegen:t("e-regen"),
                eVal:t("e-val"), eUm:t("e-um"), eSfat:t("e-sfat"),
                eNota:t("e-medieNota"),
                stare:t("stareMasina"), stareNota:t("stareNota"), sursa:t("sursa"),
                cMin:t("c-min"), cMax:t("c-max"), cDisp:t("c-disp"),
                cVerdict:t("c-verdict"), tMin:t("c-tmin"), tMax:t("c-tmax"),
                litri:t("b-litri"), rpm:t("b-rpm"), regim:t("b-regim"),
                bord:t("m-0142")
              };
              var e=document.getElementById("h-poz");
              if(e){ var m=/(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)/.exec(e.textContent);
                     if(m){ o.lat=parseFloat(m[1]); o.lon=parseFloat(m[2]); } }
              document.querySelectorAll("[data-server]").forEach(function(x){
                var n=x.getAttribute("data-server");
                if(n) o[n]=x.textContent.trim();
              });

              // Pe langa cifrele numite mai sus, matura tot ce scrie pagina,
              // dupa id. Asa nu mai tinem aici o lista de intretinut: ce apare
              // pe vreun ecran ajunge si pe server, cu numele elementului.
              var bun = /^(d|e|c|h|b|m|a|k|u|cp|f[0-9])-/;
              document.querySelectorAll("[id]").forEach(function(x){
                var id = x.id;
                if(!bun.test(id)) return;
                if(x.children.length) return;          // numai foile, nu cutiile
                var v = (x.textContent||"").trim();
                if(!v || v === "\u2014" || v.length > 48) return;
                if(o[id] === undefined) o[id] = v;
              });

              // celulele si senzorii, prin puntea pusa pentru unealta de grupare
              try{
                var V = window.AV && window.AV.V;
                var T = window.AV && window.AV.tempPeDid;
                if(V && V.celule && V.celule.length > 20){
                  o.celuleMv = V.celule.map(function(q){
                    return Math.round(q*1000); }).join(",");
                }
                if(T){
                  var st = [];
                  Object.keys(T).sort().forEach(function(k){ st.push(k+":"+T[k]); });
                  if(st.length) o.tempC = st.join(",");
                }
              }catch(e){}

              return JSON.stringify(o);
            })()
        """

        /** cifrele de pe ecranul CELULE */
        /**
         * Fisierul de grupare a celulelor, daca pagina are unul netrimis.
         *
         * Pagina nu poate urca singura: e deschisa din file:///android_asset,
         * deci un fetch ar da peste CORS, iar adresa si cheia serverului stau
         * in SharedPreferences, nu in JavaScript. Deci ea il pune la ghiseu si
         * noi il ducem, pe acelasi drum ca starea masinii.
         */
        private const val CITIRE_GRUPARE = """
            (function(){
              if(!window.AV || !AV.grupare) return "{}";
              var f = AV.grupare.iaFisier();
              return JSON.stringify(f ? {nume:f.nume, text:f.text} : {});
            })()
        """

        private const val CITIRE_CELULE = """
            (function(){
              function t(id){ var e=document.getElementById(id);
                              return e ? e.textContent.trim() : ""; }
              return JSON.stringify({
                cMin:t("c-min"), cMax:t("c-max"), cDisp:t("c-disp"),
                cVerdict:t("c-verdict"), cUlt:t("cUltima"),
                tMin:t("c-tmin"), tMax:t("c-tmax"), tDif:t("c-tdif")
              });
            })()
        """

        /** cifrele de pe ecranele ENERGIE si MOTOR */
        private const val CITIRE_ENERGIE = """
            (function(){
              function t(id){ var e=document.getElementById(id);
                              return e ? e.textContent.trim() : ""; }
              function arc(id,total){ var e=document.getElementById(id); if(!e) return 0;
                var o=parseFloat(e.getAttribute("stroke-dashoffset")||
                                 e.style.strokeDashoffset||total);
                return Math.max(0,Math.min(1,1-o/total)); }
              return JSON.stringify({
                hKwhE:t("h-kwhE"), hPriza:t("h-priza"), hKmE:t("h-kmE"), hCE:t("h-cE"),
                hLitB:t("h-litB"), hGenIn:t("h-genIn"), hKmB:t("h-kmB"), hCB:t("h-cB"),
                hTxtE:t("h-txtE"), hTxtB:t("h-txtB"),
                bRpm:t("b-rpm"), bRegim:t("b-regim"), bLitri:t("b-litri"),
                bRez:t("b-rez"), bLh:t("b-lh"), bPgen:t("b-pgen"),
                bRand:t("b-rand"), bAuto:t("b-auto"), bCalib:t("b-calib"),
                sarcina:t("d-0104"), lichid:t("d-0105"), admisie:t("d-010F"),
                bord:t("m-0142"),
                arcRpm:arc("arcRpm",371), arcLitri:arc("b-arc",465)
              });
            })()
        """
        private const val GAZDA = "appassets.androidplatform.net"
        private const val PORNIRE = "https://appassets.androidplatform.net/assets/index.html"
        private const val CERERE_PERMISIUNI = 1
        private const val CERERE_GPS = 2
    }

    private lateinit var web: WebView
    private lateinit var punte: PunteBle

    /** cererea de locatie a paginii, pusa in asteptare pana raspunde omul */
    private var origineGps: String? = null
    private var apelGps: GeolocationPermissions.Callback? = null

    private val permisiuni: Array<String>
        get() {
            val l = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                l.add(Manifest.permission.BLUETOOTH_SCAN)
                l.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                l.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                l.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return l.toTypedArray()
        }

    private fun areLocatie() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Jurnal.porneste(this)
        Alarme.porneste(this)

        // in masina, ecranul care se stinge inseamna inregistrare pierduta
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WebView.setWebContentsDebuggingEnabled(true)

        val incarcator = WebViewAssetLoader.Builder()
            .setDomain(GAZDA)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // pagina isi salveaza sesiunea aici
            databaseEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                vedere: WebView, cerere: WebResourceRequest
            ): WebResourceResponse? {
                // ce e in assets vine local; dalele de harta pleaca in retea
                return incarcator.shouldInterceptRequest(cerere.url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origine: String?, apel: GeolocationPermissions.Callback?
            ) {
                if (areLocatie()) {
                    apel?.invoke(origine, true, true)
                    return
                }
                // permisiunea nu e data inca: o cerem si tinem cererea in asteptare,
                // ca butonul de GPS sa functioneze de la prima apasare
                origineGps = origine
                apelGps = apel
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    CERERE_GPS
                )
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.i("Pagina", m.message() + " (linia " + m.lineNumber() + ")")
                return true
            }
        }

        punte = PunteBle(this, web)
        web.addJavascriptInterface(punte, "Punte")
        web.addJavascriptInterface(PunteFisiere(this), "Fisiere")
        web.addJavascriptInterface(PunteServer(this, web), "Server")

        setContentView(web)
        ecranComplet()

        cerePermisiuni()
        porneisteServiciul()   // porneste doar daca permisiunile sunt deja date

        // daca ultima oara aplicatia a cazut, spunem unde e scris de ce
        if (Jurnal.areCaderi(this)) {
            android.widget.Toast.makeText(
                this, "S-a salvat o eroare în Descărcări: avatr_erori.txt",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        web.loadUrl(PORNIRE)
        porneistePompa()
    }

    // ---------------------------------------------------- date pentru masina

    private val pompa = Handler(Looper.getMainLooper())
    private var tura = 0
    private var tacere = 0

    /**
     * Ceasul de rezerva al buclei de interogare.
     *
     * Cand ecranul se stinge, Chromium incetineste ceasurile din JavaScript
     * pana aproape de oprire, iar bucla paginii tace. Pe drumul din 7
     * septembrie s-au vazut doua gauri, de 74 si de 34 de secunde, fara nicio
     * comanda. Waze nu pateste asta fiindca e scris nativ, fara pagina in el.
     *
     * Handler-ul din Kotlin nu e incetinit: serviciul de prim-plan tine
     * procesul treaz. Deci batem noi bucla, dar numai cand chiar a amutit —
     * altfel pagina si-ar face treaba de doua ori.
     */
    private val ceasRezerva = Handler(Looper.getMainLooper())
    private var candBatut = 0L

    private val bateBucla = object : Runnable {
        override fun run() {
            web.evaluateJavascript(VERIFICA_BUCLA) { r ->
                val vechime = try {
                    JSONObject("{\"v\":$r}").getString("v").toLongOrNull() ?: -1L
                } catch (e: Exception) { -1L }
                // peste doua secunde fara nicio comanda inseamna ceas incetinit
                if (vechime in 2000..600000) {
                    web.evaluateJavascript("window.__tic && window.__tic()", null)
                    if (System.currentTimeMillis() - candBatut > 30000) {
                        candBatut = System.currentTimeMillis()
                        Jurnal.scrie("bat bucla din Kotlin: pagina tacea de ${vechime} ms", null)
                    }
                }
            }
            ceasRezerva.postDelayed(this, 400)
        }
    }

    /**
     * Citeste de pe ecranul paginii tot ce se arata in masina.
     *
     * Cifrele vin din elementele in care pagina le-a scris deja, barele din
     * chiar figurile SVG pe care pagina le-a desenat. Nimic nu se recalculeaza.
     *
     * Ritmuri diferite, pentru ca nu tot se misca la fel: consumul in fiecare
     * secunda, celulele si ecranul de energie o data la cinci. Celulele
     * inseamna peste o suta de figuri de trecut prin punte, si oricum se misca
     * incet.
     *
     * "resumeTimers" e acolo dintr-un motiv anume: cand Android Auto
     * proiecteaza, aplicatia de pe telefon nu mai e vizibila, iar Chromium
     * incetineste ceasurile din JavaScript pana aproape de oprire. Fara asta,
     * ecranul din masina ar arata cifre inghetate.
     */
    private val citeste = object : Runnable {
        override fun run() {
            web.resumeTimers()
            tura++

            web.evaluateJavascript(CITIRE_BORD) { r ->
                val j = obiect(r)
                if (j == null) tacere++
                else {
                    // daca ceasul paginii nu mai inainteaza, OBD-ul tace
                    val acum = j.optString("ceas", "")
                    if (acum == ultimulCeas) tacere++ else { tacere = 0; ultimulCeas = acum }
                    StareMasina.punePaginaIntai(j, null)
                }
                // ce scrie pagina, aceea se arata si in masina
                StareMasina.textLegatura = j?.optString("stareLegatura", "") ?: ""
                StareMasina.puneLegatura(
                    when {
                        j == null -> StareMasina.RUPT
                        j.optBoolean("eroare", false) -> StareMasina.RUPT
                        !j.optBoolean("legat", false) -> StareMasina.RUPT
                        tacere >= 6 -> StareMasina.TACE
                        else -> StareMasina.LEGAT
                    }
                )
                web.evaluateJavascript(DesenMasina.citireSvg("e-bara")) { f ->
                    StareMasina.figConsum = sir(f) ?: StareMasina.figConsum
                }
            }

            if (tura % 5 == 0) {
                web.evaluateJavascript(CITIRE_CELULE) { r ->
                    obiect(r)?.let { StareMasina.puneCelule(it, null) }
                    web.evaluateJavascript(DesenMasina.citireSvg("cBare")) { f ->
                        StareMasina.figCelule = sir(f) ?: StareMasina.figCelule
                    }
                }
                web.evaluateJavascript(CITIRE_ENERGIE) { r ->
                    obiect(r)?.let { StareMasina.punePaginaDoua(it, null) }
                    web.evaluateJavascript(DesenMasina.citireSvg("h-barE")) { f ->
                        StareMasina.figEnergie = sir(f) ?: StareMasina.figEnergie
                    }
                }
            }

            // dupa ce valorile s-au asezat, vedem daca e ceva de anuntat
            try { Alarme.verifica(this@MainActivity, StareMasina) }
            catch (e: Throwable) { Jurnal.scrie("verificarea alarmelor a esuat", e) }

            if (Sincronizare.trebuie(this@MainActivity, StareMasina)) {
                web.evaluateJavascript(CITIRE_SERVER) { r ->
                    try { Sincronizare.trimite(this@MainActivity, StareMasina, obiect(r)) }
                    catch (e: Throwable) { Jurnal.scrie("trimiterea pe server a esuat", e) }
                }
            }

            // Fisierul de grupare urca o data pe cursa, deci il intrebam rar.
            // Iese din coada paginii numai dupa ce serverul a spus 200.
            if (tura % 30 == 0 && Sincronizare.adresa(this@MainActivity).isNotBlank()) {
                web.evaluateJavascript(CITIRE_GRUPARE) { r ->
                    val j = obiect(r)
                    val numeF = j?.optString("nume", "") ?: ""
                    val text = j?.optString("text", "") ?: ""
                    if (numeF.isNotBlank() && text.length > 200) {
                        Sincronizare.trimiteGrupare(this@MainActivity, numeF, text) { bine ->
                            if (bine) this@MainActivity.runOnUiThread {
                                web.evaluateJavascript(
                                    "window.AV&&AV.grupare&&AV.grupare.trimis(" +
                                        JSONObject.quote(numeF) + ")", null
                                )
                            }
                        }
                    }
                }
            }

            pompa.postDelayed(this, 1000)
        }
    }

    private var ultimulCeas = ""

    /** raspunsul vine ca sir JSON in ghilimele; il despachetam de doua ori */
    private fun obiect(r: String?): JSONObject? = try {
        JSONObject(JSONObject("{\"v\":$r}").getString("v"))
    } catch (e: Exception) { null }

    private fun sir(r: String?): JSONArray? = try {
        val a = JSONArray(JSONObject("{\"v\":$r}").getString("v"))
        if (a.length() == 0) null else a
    } catch (e: Exception) { null }

    private fun porneistePompa() {
        pompa.removeCallbacks(citeste)
        pompa.postDelayed(citeste, 2500)   // dupa ce pagina apuca sa se aseze
        ceasRezerva.removeCallbacks(bateBucla)
        ceasRezerva.postDelayed(bateBucla, 6000)
    }

    private fun cerePermisiuni() {
        val lipsa = permisiuni.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (lipsa.isNotEmpty())
            ActivityCompat.requestPermissions(this, lipsa.toTypedArray(), CERERE_PERMISIUNI)
    }

    override fun onRequestPermissionsResult(
        cod: Int, cerute: Array<out String>, rezultate: IntArray
    ) {
        super.onRequestPermissionsResult(cod, cerute, rezultate)

        // abia acum stim daca avem voie sa pornim serviciul
        if (cod == CERERE_PERMISIUNI) { porneisteServiciul(); return }

        if (cod != CERERE_GPS) return
        val dat = rezultate.isNotEmpty() && rezultate[0] == PackageManager.PERMISSION_GRANTED
        apelGps?.invoke(origineGps, dat, dat)
        apelGps = null
        origineGps = null
    }

    /**
     * Porneste serviciul de prim-plan, dar numai cand se poate.
     *
     * De la Android 14, un serviciu de tip "dispozitiv conectat" cere ca
     * aplicatia sa DETINA deja o permisiune Bluetooth. Cererea de permisiune e
     * insa asincrona: daca pornim serviciul in aceeasi clipa in care intrebam,
     * el porneste inaintea raspunsului si sistemul opreste aplicatia. De aceea
     * asteptam sa avem permisiunea, si reincercam dupa ce omul raspunde.
     */
    private fun poatePorniServiciul(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun porneisteServiciul() {
        if (!poatePorniServiciul()) return
        try {
            val i = Intent(this, ServiciuFundal::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } catch (e: Throwable) {
            Jurnal.scrie("serviciul de fundal nu a pornit", e)
        }
    }

    private fun ecranComplet() {
        @Suppress("DEPRECATION")
        web.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    /** butonul „inapoi" umbla intre ecranele paginii, nu inchide aplicatia */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        pompa.removeCallbacks(citeste)
        ceasRezerva.removeCallbacks(bateBucla)
        StareMasina.asculta(null)
        punte.inchide()
        stopService(Intent(this, ServiciuFundal::class.java))
        super.onDestroy()
    }
}
