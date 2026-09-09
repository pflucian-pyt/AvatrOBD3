package ro.avatr.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.abs
import kotlin.math.floor

/**
 * Anunturile care apar peste harta, ca la un apel.
 *
 * Regula dupa care sunt alese: se anunta doar ce merita sa-ti ia ochii de pe
 * drum. Un anunt la fiecare secunda ar fi mai periculos decat lipsa lui, si
 * Android Auto oricum le stinge daca vin prea des. De aceea fiecare are un
 * prag, o histereza si o pauza minima, iar unul care s-a stins nu se reaprinde
 * decat dupa ce lucrul chiar s-a schimbat.
 *
 * Cele patru:
 *  - legatura cu OBD-ul s-a rupt sau adaptorul a amutit
 *  - benzina sub 10 litri, apoi din doi in doi litri
 *  - generatorul a pornit
 *  - o celula s-a departat de restul
 */
object Alarme {

    private const val TAG = "Alarme"
    private const val CANAL = "avatr_alarme"

    /**
     * Cat de departe are voie sa fie o celula, in milivolti, inainte sa te
     * anuntam. Ai cerut 3. Atentie: pachetul tau sta de obicei pe 4 mV
     * dispersie, deci la 3 anuntul va aparea aproape imediat si des. Daca vrei
     * sa auzi doar cand chiar se strica ceva, ridica numarul spre 15-20.
     */
    private const val PRAG_CELULA_MV = 3

    /** sub cat incepem sa numaram benzina, si din cat in cat anuntam */
    private const val BENZINA_PRAG = 10.0
    private const val BENZINA_PAS = 2.0

    /** cat lasam sa treaca intre doua anunturi de acelasi fel */
    private const val PAUZA_CELULA_MS = 15 * 60_000L
    private const val PAUZA_LEGATURA_MS = 2 * 60_000L

    private var pornit = false

    // ---- ce stim de data trecuta, ca sa anuntam doar schimbarile
    private var ultimaLegatura = -1
    private var ultimulPragBenzina = Double.MAX_VALUE
    private var generatorMergea = false
    private var celulaAnuntata = false
    private var candCelula = 0L
    private var candLegatura = 0L

    fun porneste(c: Context) {
        if (pornit) return
        pornit = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL, "Avertismente din mașină", NotificationManager.IMPORTANCE_HIGH
            )
            canal.description = "Deconectare, benzină, generator, celule"
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(canal)
        }
    }

    /** se cheama la fiecare citire; decide singura daca e ceva de spus */
    fun verifica(c: Context, s: StareMasina) {
        val acum = System.currentTimeMillis()

        // ---------- legatura cu OBD-ul ----------
        if (s.legatura != ultimaLegatura) {
            val eraBun = ultimaLegatura == StareMasina.LEGAT
            if (eraBun && s.legatura != StareMasina.LEGAT &&
                acum - candLegatura > PAUZA_LEGATURA_MS) {
                candLegatura = acum
                anunta(c, 1,
                    if (s.legatura == StareMasina.TACE) "OBD-ul nu mai răspunde"
                    else "Legătura cu adaptorul s-a rupt",
                    "Datele s-au oprit. Aplicația încearcă singură să se relege.")
            }
            ultimaLegatura = s.legatura
        }

        // ---------- benzina ----------
        val litri = numar(s.bLitri)
        if (litri != null && litri > 0.0) {
            if (litri > BENZINA_PRAG + BENZINA_PAS) {
                // s-a alimentat: uitam pragurile si o luam de la capat
                ultimulPragBenzina = Double.MAX_VALUE
            } else if (litri <= BENZINA_PRAG) {
                // pragul imediat sub nivelul de acum: 10, 8, 6, 4, 2
                val prag = floor(litri / BENZINA_PAS) * BENZINA_PAS
                if (prag < ultimulPragBenzina) {
                    ultimulPragBenzina = prag
                    anunta(c, 2, "Benzină ${virgula(litri)} litri",
                        if (prag <= 2.0) "Rezervorul e aproape gol."
                        else "Sub ${virgula(prag + BENZINA_PAS)} litri în rezervor.")
                }
            }
        }

        // ---------- generatorul ----------
        val merge = s.arcRpm > 0f ||
                (s.bRegim.isNotBlank() && !s.bRegim.contains("oprit", true) &&
                 s.bRegim != "—")
        if (merge && !generatorMergea) {
            anunta(c, 3, "Generatorul a pornit",
                "Turație ${s.bRpm} rpm · rezervor ${s.bLitri} litri")
        }
        generatorMergea = merge

        // ---------- celulele ----------
        // Pagina scrie dispersia, adica departarea dintre cea mai mica si cea
        // mai mare celula. O celula "departata cu 3 mV de restul" inseamna,
        // practic, o dispersie de cel putin atat.
        val dispersie = numar(s.celAbatere)
        if (dispersie != null) {
            if (dispersie >= PRAG_CELULA_MV && !celulaAnuntata &&
                acum - candCelula > PAUZA_CELULA_MS) {
                celulaAnuntata = true; candCelula = acum
                anunta(c, 4, "O celulă s-a depărtat",
                    "${dispersie.toInt()} mV între cea mai mică și cea mai mare " +
                    "(${s.celMin} – ${s.celMax} V)")
            }
            // histereza de un milivolt: fara ea, o valoare care danseaza in
            // jurul pragului ar aprinde si stinge anuntul la nesfarsit
            if (dispersie < PRAG_CELULA_MV - 1) celulaAnuntata = false
        }
    }

    // ------------------------------------------------------------------ unelte

    private fun anunta(c: Context, id: Int, titlu: String, text: String) {
        try {
            val deschide = PendingIntent.getActivity(
                c, id, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(c, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(titlu)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(deschide)
                // fara asta, anuntul apare pe telefon dar nu si pe ecranul masinii
                .extend(
                    CarAppExtender.Builder()
                        .setContentTitle(titlu)
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        .setImportance(NotificationManager.IMPORTANCE_HIGH)
                        .build()
                )
                .build()
            NotificationManagerCompat.from(c).notify(id, n)
            Log.i(TAG, "anunt: $titlu — $text")
        } catch (e: Throwable) {
            Jurnal.scrie("anuntul nu a putut fi trimis", e)
        }
    }

    /** cifrele paginii vin cu virgula zecimala, ca in scris romanesc */
    private fun numar(s: String?): Double? {
        if (s.isNullOrBlank() || s == "—") return null
        return try {
            s.replace(",", ".").filter { it.isDigit() || it == '.' || it == '-' }
                .toDoubleOrNull()
        } catch (e: Exception) { null }
    }

    private fun virgula(x: Double): String =
        if (abs(x - x.toInt()) < 0.05) x.toInt().toString()
        else String.format("%.1f", x).replace(".", ",")
}
