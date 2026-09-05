package ro.avatr.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

        setContentView(web)
        ecranComplet()

        cerePermisiuni()
        porneisteServiciul()

        web.loadUrl(PORNIRE)
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
        if (cod != CERERE_GPS) return
        val dat = rezultate.isNotEmpty() && rezultate[0] == PackageManager.PERMISSION_GRANTED
        apelGps?.invoke(origineGps, dat, dat)
        apelGps = null
        origineGps = null
    }

    private fun porneisteServiciul() {
        val i = Intent(this, ServiciuFundal::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
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
        punte.inchide()
        stopService(Intent(this, ServiciuFundal::class.java))
        super.onDestroy()
    }
}
