package ro.avatr.monitor

import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator

/**
 * Aplicatia pe ecranul masinii.
 *
 * Android Auto da panza intreaga doar aplicatiilor de navigatie; celelalte
 * primesc sabloane cu randuri de text. Ca sa iasa asezarea din telefon — casete
 * una langa alta, bare cu cifrele lor, cadrane — ne trebuie panza, deci
 * aplicatia se declara ca aplicatie de navigatie si cere accesul la ea.
 *
 * REGULA DE AUR AICI, invatata pe pielea noastra: nimic din codul asta nu are
 * voie sa arunce. O exceptie pe ecranul masinii nu se vede ca un rand in jurnal,
 * ci ca "aplicatia a intampinat o eroare neasteptata" si ecran gol. De aceea
 * fiecare pas care poate esua — cererea panzei, desenul, chiar si sablonul — e
 * imbracat in try/catch, iar la orice necaz se cade pe lista simpla, care merge
 * oriunde. Mai bine putin decat nimic.
 */
class ServiciuMasina : CarAppService() {

    // Aplicatia se instaleaza direct pe telefon si se foloseste personal.
    // O lista de semnaturi permise are rost doar pentru una publicata.
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreate() {
        super.onCreate()
        // ecranul masinii poate porni fara ca aplicatia de pe telefon sa fi
        // fost deschisa, deci jurnalul trebuie pornit si de aici
        Jurnal.porneste(this)
    }

    override fun onCreateSession(): Session = SesiuneMasina()
}

class SesiuneMasina : Session() {
    override fun onCreateScreen(intent: Intent): Screen = EcranMasina(carContext)
}

class EcranMasina(ctx: CarContext) : Screen(ctx), SurfaceCallback {

    companion object {
        private const val TAG = "EcranMasina"
        private const val PAGINI = 2
        /** cat asteptam panza inainte sa trecem pe varianta simpla */
        private const val RABDARE_PANZA_MS = 6000L
    }

    private val principal = Handler(Looper.getMainLooper())
    private var recipient: SurfaceContainer? = null
    /** dreptunghiul chiar liber, dupa bara laterala si panoul de muzica */
    private var zonaLibera: Rect? = null
    private var faraPanza = false
    private var cerutaPanza = false
    private var pagina = 0

    init {
        // Ascultatorul de date e nevinovat, il putem pune de pe acum.
        // Panza NU se cere aici: in constructor serviciile masinii pot sa nu
        // fie inca gata, iar o exceptie de aici omoara ecranul din start.
        StareMasina.asculta { principal.post { deseneaza() } }
    }

    private fun cerePanza() {
        if (cerutaPanza) return
        cerutaPanza = true
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
            principal.postDelayed({
                if (recipient == null && !faraPanza) {
                    Log.w(TAG, "panza nu a venit, trec pe lista simpla")
                    faraPanza = true
                    invalidate()
                }
            }, RABDARE_PANZA_MS)
        } catch (e: Throwable) {
            Jurnal.scrie("panza refuzata", e)
            faraPanza = true
        }
    }

    // ------------------------------------------------------------------- panza

    override fun onSurfaceAvailable(c: SurfaceContainer) {
        recipient = c; faraPanza = false; deseneaza()
    }

    override fun onSurfaceDestroyed(c: SurfaceContainer) { recipient = null }

    /**
     * Gazda ne spune ce parte din panza ramane descoperita.
     *
     * "Stabila" e portiunea care nu se schimba cat timp aplicatia e afisata;
     * "vizibila" se poate ingusta temporar. Desenam in cea stabila, ca tabloul
     * sa nu tresara la fiecare notificare, si cadem pe cea vizibila daca gazda
     * nu ne da una stabila.
     */
    override fun onVisibleAreaChanged(a: Rect) {
        if (zonaLibera == null && a.width() > 100) zonaLibera = Rect(a)
        deseneaza()
    }

    override fun onStableAreaChanged(a: Rect) {
        if (a.width() > 100 && a.height() > 100) zonaLibera = Rect(a)
        deseneaza()
    }

    /** o atingere oriunde pe panza schimba pagina */
    override fun onClick(x: Float, y: Float) { schimbaPagina() }

    private fun deseneaza() {
        val c = recipient ?: return
        val supr = c.surface ?: return
        if (c.width <= 0 || c.height <= 0) return
        var panza: android.graphics.Canvas? = null
        try {
            panza = supr.lockCanvas(null) ?: return
            Tablou.deseneaza(panza, c.width, c.height, StareMasina, pagina,
                             Paleta.pentru(carContext.isDarkMode), zonaLibera)
        } catch (e: Throwable) {
            // un desen stricat nu are voie sa dea jos ecranul
            Jurnal.scrie("desen esuat", e)
        } finally {
            if (panza != null) try { supr.unlockCanvasAndPost(panza) } catch (e: Throwable) { }
        }
    }

    private fun schimbaPagina() {
        pagina = (pagina + 1) % PAGINI
        deseneaza()
        try { invalidate() } catch (e: Throwable) { }
    }

    // ---------------------------------------------------------------- sabloane

    override fun onGetTemplate(): Template {
        // panza se cere abia acum: aici ecranul chiar e afisat, deci serviciile
        // masinii sunt gata
        cerePanza()

        if (!faraPanza) {
            try {
                val butoane = ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(if (pagina == 0) "Energie" else "Consum")
                            .setOnClickListener { schimbaPagina() }
                            .build()
                    )
                    .build()
                return NavigationTemplate.Builder().setActionStrip(butoane).build()
            } catch (e: Throwable) {
                Jurnal.scrie("sablonul de navigatie a esuat", e)
                faraPanza = true
            }
        }
        return listaSimpla()
    }

    /**
     * Varianta de rezerva, fara panza. Titlurile randurilor raman fixe: in mers
     * sistemul primeste doar improspatari, si o improspatare cu alte titluri e
     * respinsa, deci ecranul ar ingheta la prima valoare.
     */
    private fun listaSimpla(): Template {
        val s = StareMasina
        val lista = ItemList.Builder()
            .addItem(rand("Autonomie", "${s.autonomie} km", "baterie ${s.soc} %"))
            .addItem(rand("Energie sesiune", "${s.energie} kWh", "${s.kmSesiune} km"))
            .addItem(rand("Ultimele 5 minute", s.consum5, s.consum5Nota))
            .addItem(rand("Consum acum", "${s.consumVal} ${s.consumUm}", s.consumNota))
            .addItem(rand("Celule", "${s.celMin} – ${s.celMax} V", "abatere ${s.celAbatere}"))
            .build()
        return ListTemplate.Builder()
            .setSingleList(lista)
            .setTitle("Avatr Monitor")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun rand(titlu: String, a: String, b: String): Row =
        Row.Builder()
            .setTitle(titlu)
            .addText(if (a.isBlank()) "—" else a)
            .addText(if (b.isBlank()) "—" else b)
            .build()
}
