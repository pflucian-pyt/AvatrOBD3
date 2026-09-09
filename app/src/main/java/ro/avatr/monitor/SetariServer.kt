package ro.avatr.monitor

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Ecranul unde pui adresa serverului si cheia.
 *
 * E facut din cod, nu din fisiere de asezare: sunt trei campuri si doua butoane,
 * iar un ecran atat de mic nu merita trei fisiere pe langa.
 *
 * Butonul de proba nu e de fatada. Fara el, singurul fel de a afla ca adresa e
 * gresita ar fi sa nu apara datele pe server ore in sir, fara sa stii de ce.
 */
class SetariServer : AppCompatActivity() {

    private val fir = Executors.newSingleThreadExecutor()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val P = (resources.displayMetrics.density * 16).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(P, P, P, P)
        }

        fun titlu(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setPadding(0, P, 0, 4)
        }
        fun camp(valoare: String, sugestie: String, parola: Boolean = false) =
            EditText(this).apply {
                setText(valoare); hint = sugestie
                inputType = if (parola)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
            }

        col.addView(TextView(this).apply {
            text = "Trimitere pe server"
            textSize = 22f
        })
        col.addView(TextView(this).apply {
            text = "Starea mașinii se trimite din minut în minut, plus o dată " +
                   "imediat când se pierde legătura — ca să știi unde a rămas."
            textSize = 13f; setPadding(0, 6, 0, 0)
        })

        col.addView(titlu("Adresa (unde trimite)"))
        val cAdresa = camp(Sincronizare.adresa(this), "https://serverul-tau.ro/stare")
        col.addView(cAdresa)

        col.addView(titlu("Cheia"))
        val cParola = camp(Sincronizare.parola(this), "parola din AVATR_CHEIE", true)
        col.addView(cParola)

        col.addView(titlu("Numele acestui aparat"))
        val cNume = camp(Sincronizare.nume(this), "telefon / unitatea din mașină")
        col.addView(cNume)

        val stare = TextView(this).apply { textSize = 13f; setPadding(0, P, 0, 0) }

        col.addView(Button(this).apply {
            text = "Salvează"
            setOnClickListener {
                Sincronizare.pune(this@SetariServer,
                    cAdresa.text.toString(), cParola.text.toString(), cNume.text.toString())
                Toast.makeText(this@SetariServer, "Salvat", Toast.LENGTH_SHORT).show()
            }
        })

        col.addView(Button(this).apply {
            text = "Trimite o probă"
            setOnClickListener {
                Sincronizare.pune(this@SetariServer,
                    cAdresa.text.toString(), cParola.text.toString(), cNume.text.toString())
                stare.text = "trimit…"
                proba(cAdresa.text.toString(), cParola.text.toString(),
                      cNume.text.toString()) { rezultat ->
                    runOnUiThread { stare.text = rezultat }
                }
            }
        })

        col.addView(stare)

        setContentView(ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun proba(url: String, parola: String, nume: String, gata: (String) -> Unit) {
        fir.execute {
            var leg: HttpURLConnection? = null
            try {
                val corp = ("{\"sursa\":" + org.json.JSONObject.quote(nume) +
                        ",\"proba\":true,\"stare\":\"probă din Setări\"}")
                    .toByteArray(Charsets.UTF_8)
                leg = URL(url).openConnection() as HttpURLConnection
                leg.requestMethod = "POST"
                leg.connectTimeout = 8000; leg.readTimeout = 8000; leg.doOutput = true
                leg.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (parola.isNotBlank()) leg.setRequestProperty("X-Cheie", parola)
                leg.outputStream.use { it.write(corp) }
                gata(when (val c = leg.responseCode) {
                    in 200..299 -> "A mers. Serverul a primit proba."
                    401 -> "Cheia nu se potrivește."
                    404 -> "Adresa e greșită: serverul nu are calea asta."
                    else -> "Serverul a răspuns $c."
                })
            } catch (e: Throwable) {
                gata("Nu am ajuns la server: ${e.message}")
            } finally {
                try { leg?.disconnect() } catch (e: Throwable) { }
            }
        }
    }
}
