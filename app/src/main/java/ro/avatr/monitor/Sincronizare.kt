package ro.avatr.monitor

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Trimite starea masinii pe serverul tau.
 *
 * Doua feluri de trimiteri, si al doilea conteaza mai mult decat pare:
 *
 *  - din minut in minut, cat timp aplicatia citeste
 *  - o data, IMEDIAT, cand legatura cu OBD-ul se pierde sau masina se parcheaza
 *
 * Al doilea e "ultima suflare". Cand opresti masina, unitatea din bord se
 * stinge in cateva minute si telefonul pierde adaptorul. Fara trimiterea de
 * atunci, ultima stare de pe server ar fi cea de acum cateva minute, si n-ai
 * sti unde s-a oprit masina si cu cat a ramas. Asa, ultimul lucru pe care il
 * afli e chiar clipa in care s-a stins.
 *
 * Cine trimite nu conteaza: si telefonul, si unitatea din masina pot avea
 * aceeasi aplicatie. Fiecare inregistrare poarta numele sursei, iar serverul
 * pastreaza ultima sosita. Care apuca.
 */
object Sincronizare {

    private const val PREF = "avatr"
    private const val CHEIE_ADRESA = "serverAdresa"
    private const val CHEIE_PAROLA = "serverParola"
    private const val CHEIE_NUME = "serverNume"

    private const val PAS_MS = 60_000L
    private val fir = Executors.newSingleThreadExecutor()

    private var ultima = 0L
    private var ultimaLegatura = -1
    private var ultimaStare = ""

    fun adresa(c: Context): String =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(CHEIE_ADRESA, "") ?: ""

    fun parola(c: Context): String =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(CHEIE_PAROLA, "") ?: ""

    fun nume(c: Context): String =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(CHEIE_NUME, "")
            ?.ifBlank { null } ?: (Build.MODEL ?: "telefon")

    fun pune(c: Context, adresa: String, parola: String, nume: String) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(CHEIE_ADRESA, adresa.trim())
            .putString(CHEIE_PAROLA, parola.trim())
            .putString(CHEIE_NUME, nume.trim())
            .apply()
    }

    private var suflare = false

    /* ---- cat de bine merge trimiterea, ca sa nu mai taca esecurile
       Pana acum, o adresa greșita sau http-ul taiat de Android treceau fara
       nici o urma: se prindea excepția si se mergea mai departe, deci pareai
       ca trimiti cand de fapt nu pleca nimic. Acum primul esec se scrie in
       jurnal, apoi din douazeci in douazeci, si se scrie si clipa in care
       lucrurile se indreapta. */
    @Volatile
    var esecuri = 0
        private set

    @Volatile
    var candIzbanda = 0L
        private set

    @Volatile
    var ultimaVorba = ""
        private set

    private fun izbanda() {
        if (esecuri > 0) Jurnal.scrie("serverul primeste iar ($esecuri incercari pierdute)", null)
        esecuri = 0
        candIzbanda = System.currentTimeMillis()
        ultimaVorba = "trimis"
    }

    private fun greseala(ce: String, e: Throwable?) {
        esecuri++
        ultimaVorba = ce
        if (esecuri == 1 || esecuri % 20 == 0) Jurnal.scrie("$ce (esec $esecuri)", e)
    }

    /**
     * Spune daca e clipa sa trimitem. Separat de trimiterea insasi, ca sa
     * putem citi valorile din pagina doar cand chiar avem nevoie: o
     * interogare cu treizeci de campuri nu merita facuta in fiecare secunda.
     */
    fun trebuie(c: Context, s: StareMasina): Boolean {
        if (adresa(c).isBlank()) return false
        val acum = System.currentTimeMillis()

        // ultima suflare: legatura tocmai s-a pierdut, sau masina s-a parcat
        suflare = (ultimaLegatura == StareMasina.LEGAT && s.legatura != StareMasina.LEGAT) ||
                  (ultimaStare != s.stare && s.stare.contains("Parc", true))
        ultimaLegatura = s.legatura
        ultimaStare = s.stare

        if (suflare || acum - ultima >= PAS_MS) { ultima = acum; return true }
        return false
    }

    /**
     * Urca fisierul de grupare a celulelor pe acelasi server, pe /grupare.
     *
     * Merge pe langa starea masinii, nu peste ea: starea e mica si pleaca din
     * minut in minut, iar asta e un fisier de vreo suta de kilobiti, o data pe
     * cursa. Adresa si cheia sunt aceleasi, deci nu ai de configurat nimic in
     * plus. Daca serverul nu raspunde 200, nu chemam @param gata cu true, si
     * pagina pastreaza fisierul in coada pentru urmatoarea incercare.
     */
    fun trimiteGrupare(c: Context, nume: String, text: String, gata: (Boolean) -> Unit) {
        val baza = adresa(c)
        if (baza.isBlank()) { gata(false); return }
        val url = if (baza.endsWith("/stare")) baza.dropLast(6) + "/grupare"
                  else baza.trimEnd('/') + "/grupare"
        val parola = parola(c); val numele = nume(c)
        val corp = text.toByteArray(Charsets.UTF_8)

        fir.execute {
            var leg: HttpURLConnection? = null
            var bine = false
            try {
                leg = URL(url).openConnection() as HttpURLConnection
                leg.requestMethod = "POST"
                leg.connectTimeout = 10000
                leg.readTimeout = 20000
                leg.doOutput = true
                leg.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                leg.setRequestProperty("X-Fisier", nume)
                leg.setRequestProperty("X-Sursa", numele)
                if (parola.isNotBlank()) leg.setRequestProperty("X-Cheie", parola)
                leg.outputStream.use { it.write(corp) }
                val cod = leg.responseCode
                bine = cod in 200..299
                if (!bine) Jurnal.scrie("gruparea: serverul a raspuns $cod", null)
                else Jurnal.scrie("gruparea a urcat: $nume (${corp.size / 1024} KB)", null)
            } catch (e: Throwable) {
                Jurnal.scrie("gruparea nu a urcat, rasmane in coada: $url", e)
            } finally {
                try { leg?.disconnect() } catch (e: Throwable) { }
            }
            gata(bine)
        }
    }

    /**
     * @param dinPagina tot ce s-a citit de pe ecran, inclusiv elementele pe care
     *   pagina le marcheaza cu data-server. Trece nefiltrat: ce apare acolo
     *   ajunge pe server, fara sa fie nevoie de o lista tinuta la zi aici.
     */
    /**
     * Adresa pusa in setari e cea a starii, de pilda
     * https://domeniu/avatr/stare. Fisierele de grupare merg la fratele ei,
     * /grupare, deci schimbam doar ultima bucata. Daca omul a pus adresa fara
     * "stare" la coada, lipim noi.
     */
    fun adresaGrupare(c: Context): String {
        val a = adresa(c).trim().trimEnd('/')
        if (a.isBlank()) return ""
        return if (a.endsWith("/stare")) a.dropLast(6) + "/grupare" else "$a/grupare"
    }

    /**
     * Urca un fisier de grupare. Se cheama dintr-un fir de fundal si intoarce
     * ce s-a intamplat, ca sa poata pagina sa stie daca il mai pastreaza in
     * coada sau nu.
     */
    fun trimiteGrupare(c: Context, nume: String, text: String): Pair<Boolean, String> {
        val url = adresaGrupare(c)
        if (url.isBlank()) return false to "adresa serverului nu e pusă"
        val parola = parola(c)
        val corp = text.toByteArray(Charsets.UTF_8)
        var leg: HttpURLConnection? = null
        return try {
            leg = URL(url).openConnection() as HttpURLConnection
            leg.requestMethod = "POST"
            leg.connectTimeout = 10000
            leg.readTimeout = 20000
            leg.doOutput = true
            leg.setFixedLengthStreamingMode(corp.size)
            leg.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (parola.isNotBlank()) leg.setRequestProperty("X-Cheie", parola)
            leg.setRequestProperty("X-Fisier", nume)
            leg.setRequestProperty("X-Sursa", nume(c))
            leg.outputStream.use { it.write(corp) }
            val cod = leg.responseCode
            if (cod in 200..299) {
                true to "urcat pe server (${(corp.size + 512) / 1024} KB)"
            } else {
                Jurnal.scrie("gruparea nu a plecat, serverul a raspuns $cod", null)
                false to "serverul a răspuns $cod"
            }
        } catch (e: Throwable) {
            // fara internet e normal: fisierul rasmane in coada si se reincearca
            false to (e.message ?: "fără legătură")
        } finally {
            try { leg?.disconnect() } catch (e: Throwable) { }
        }
    }

    fun trimite(c: Context, s: StareMasina, dinPagina: JSONObject?) {
        val url = adresa(c); val parola = parola(c); val numele = nume(c)
        val j = JSONObject()
        dinPagina?.keys()?.forEach { k -> j.put(k, dinPagina.get(k)) }
        j.apply {
            put("sursa", numele)
            put("ultimaSuflare", suflare)
            put("legatura", when (s.legatura) {
                StareMasina.LEGAT -> "citesc date"
                StareMasina.TACE -> "OBD nu răspunde"
                else -> "deconectat"
            })
        }
        val ultimaSuflare = suflare
        val corp = j.toString().toByteArray(Charsets.UTF_8)

        fir.execute {
            var leg: HttpURLConnection? = null
            try {
                leg = URL(url).openConnection() as HttpURLConnection
                leg.requestMethod = "POST"
                leg.connectTimeout = 8000
                leg.readTimeout = 8000
                leg.doOutput = true
                leg.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (parola.isNotBlank()) leg.setRequestProperty("X-Cheie", parola)
                leg.outputStream.use { it.write(corp) }
                val cod = leg.responseCode
                if (cod in 200..299) izbanda() else greseala("serverul a raspuns $cod", null)
            } catch (e: Throwable) {
                greseala("nu am ajuns la $url", e)
                if (ultimaSuflare) Jurnal.scrie("ultima suflare nu a plecat", e)
            } finally {
                try { leg?.disconnect() } catch (e: Throwable) { }
            }
        }
    }
}
