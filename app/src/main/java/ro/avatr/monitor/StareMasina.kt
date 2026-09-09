package ro.avatr.monitor

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tot ce se arata pe ecranul masinii, tinut intr-un singur loc.
 *
 * Nimic nu se recalculeaza aici. Cifrele se citesc din elementele in care
 * pagina le-a scris deja, iar barele din chiar figurile pe care pagina le-a
 * desenat in SVG. Asa, ce vezi pe telefon si ce vezi in masina sunt acelasi
 * lucru, si nicio formula nu traieste in doua locuri.
 */
object StareMasina {

    const val LEGAT = 0     // vin raspunsuri
    const val TACE = 1      // legatura sta, dar OBD-ul nu raspunde
    const val RUPT = 2      // nici legatura nu mai e

    // ---- pagina intai
    @Volatile var soc = "—"
    @Volatile var autonomie = "—"
    @Volatile var energie = "—"
    @Volatile var recuperat = "—"
    @Volatile var kmSesiune = "—"
    @Volatile var durata = ""
    @Volatile var consum5 = "—"
    @Volatile var consum5Nota = ""
    @Volatile var consumVal = "—"
    @Volatile var consumUm = ""
    @Volatile var consumNota = ""
    @Volatile var consumRau = false
    @Volatile var eNet = "—"
    @Volatile var eBrut = "—"
    @Volatile var eRegen = "—"
    @Volatile var eNota = ""
    @Volatile var celMin = "—"
    @Volatile var celMax = "—"
    @Volatile var celAbatere = "—"
    @Volatile var celVerdict = "—"
    @Volatile var celNumar = "—"
    @Volatile var tempMin = "—"
    @Volatile var tempMax = "—"
    @Volatile var tempDif = "—"
    @Volatile var stare = "—"
    /** ultima pozitie stiuta, ca sa se vada de la distanta unde a ramas masina */
    @Volatile var lat: Double? = null
    @Volatile var lon: Double? = null
    @Volatile var legatura = RUPT
    /** chiar textul pe care il scrie pagina, ca cele doua ecrane sa nu difere */
    @Volatile var textLegatura = ""

    // ---- pagina a doua
    @Volatile var hKwhE = "—"
    @Volatile var hPriza = "—"
    @Volatile var hKmE = "—"
    @Volatile var hCE = ""
    @Volatile var hLitB = "—"
    @Volatile var hGenIn = "—"
    @Volatile var hKmB = "—"
    @Volatile var hCB = ""
    @Volatile var hTxtE = ""
    @Volatile var hTxtB = ""
    @Volatile var bRpm = "—"
    @Volatile var bRegim = "—"
    @Volatile var bLitri = "—"
    @Volatile var bRez = ""
    @Volatile var bLh = "—"
    @Volatile var bPgen = "—"
    @Volatile var bRand = "—"
    @Volatile var bAuto = "—"
    @Volatile var bCalib = ""
    @Volatile var sarcina = "—"
    @Volatile var lichid = "—"
    @Volatile var admisie = "—"
    @Volatile var bord = "—"
    @Volatile var arcRpm = 0f
    @Volatile var arcLitri = 0f

    // ---- figurile barelor, copiate din SVG-urile paginii
    @Volatile var figConsum: JSONArray? = null
    @Volatile var figCelule: JSONArray? = null
    @Volatile var figEnergie: JSONArray? = null

    /** ecranul din masina se anunta aici, ca sa fie redesenat la date noi */
    @Volatile private var asculta: (() -> Unit)? = null
    fun asculta(f: (() -> Unit)?) { asculta = f }

    private fun s(j: JSONObject, k: String, prest: String = "—"): String {
        val v = j.optString(k, prest)
        return if (v.isBlank()) prest else v
    }

    fun punePaginaIntai(j: JSONObject, fConsum: JSONArray?) {
        soc = s(j, "soc"); autonomie = s(j, "autonomie")
        energie = s(j, "energie"); recuperat = s(j, "recup")
        kmSesiune = s(j, "km"); durata = s(j, "durata", "")
        consum5 = s(j, "c5"); consum5Nota = s(j, "c5t", "")
        consumVal = s(j, "eVal"); consumUm = s(j, "eUm", "")
        consumNota = s(j, "eSfat", "")
        consumRau = consumNota.contains("mare") || consumNota.contains("ridici")
        eNet = s(j, "eNet"); eBrut = s(j, "eBrut"); eRegen = s(j, "eRegen")
        eNota = s(j, "eNota", "")
        stare = s(j, "stare")
        val la = j.optDouble("lat", Double.NaN); val lo = j.optDouble("lon", Double.NaN)
        if (!la.isNaN() && !lo.isNaN() && la != 0.0) { lat = la; lon = lo }
        if (fConsum != null && fConsum.length() > 0) figConsum = fConsum
        anunta()
    }

    fun puneCelule(j: JSONObject, fCelule: JSONArray?) {
        celMin = s(j, "cMin"); celMax = s(j, "cMax")
        celAbatere = s(j, "cDisp"); celVerdict = s(j, "cVerdict")
        celNumar = s(j, "cUlt").filter { it.isDigit() }.ifBlank { "—" }
        tempMin = s(j, "tMin"); tempMax = s(j, "tMax"); tempDif = s(j, "tDif")
        if (fCelule != null && fCelule.length() > 0) figCelule = fCelule
        anunta()
    }

    fun punePaginaDoua(j: JSONObject, fEnergie: JSONArray?) {
        hKwhE = s(j, "hKwhE"); hPriza = s(j, "hPriza")
        hKmE = s(j, "hKmE"); hCE = s(j, "hCE", "")
        hLitB = s(j, "hLitB"); hGenIn = s(j, "hGenIn")
        hKmB = s(j, "hKmB"); hCB = s(j, "hCB", "")
        hTxtE = s(j, "hTxtE", ""); hTxtB = s(j, "hTxtB", "")
        bRpm = s(j, "bRpm"); bRegim = s(j, "bRegim")
        bLitri = s(j, "bLitri"); bRez = s(j, "bRez", "")
        bLh = s(j, "bLh"); bPgen = s(j, "bPgen")
        bRand = s(j, "bRand"); bAuto = s(j, "bAuto")
        bCalib = s(j, "bCalib", "")
        sarcina = s(j, "sarcina"); lichid = s(j, "lichid")
        admisie = s(j, "admisie"); bord = s(j, "bord")
        arcRpm = j.optDouble("arcRpm", 0.0).toFloat()
        arcLitri = j.optDouble("arcLitri", 0.0).toFloat()
        if (fEnergie != null && fEnergie.length() > 0) figEnergie = fEnergie
        anunta()
    }

    /**
     * Starea legaturii, cea din bulina de sus.
     *
     * "Tace" e cazul care conteaza: legatura BLE sta ridicata, dar OBD-ul nu
     * mai raspunde. Fara bulina, ecranul ar arata cifre vechi si ai crede ca
     * sunt de acum. Asa se vede dintr-o privire ca s-au oprit.
     */
    fun puneLegatura(nou: Int) {
        if (nou != legatura) { legatura = nou; anunta() }
    }

    private fun anunta() { asculta?.invoke() }
}
