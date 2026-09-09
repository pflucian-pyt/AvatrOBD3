package ro.avatr.monitor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/**
 * Graficele paginii, redesenate pentru ecranul masinii.
 *
 * Sabloanele Android Auto nu deseneaza nimic: primesc randuri de text si
 * imagini gata facute. Deci un grafic trebuie desenat de noi.
 *
 * Ce NU facem aici: sa recalculam abaterea celulelor sau scara consumului.
 * Acelea sunt socoteli verificate, si a doua oara ar putea iesi altfel.
 *
 * Ce facem: pagina isi deseneaza graficele in SVG, din dreptunghiuri si linii
 * simple, cu coordonate intr-un sistem fix. Citim chiar figurile acelea —
 * pozitie, marime, culoare — si le asezam la fel pe panza. Copiem desenul deja
 * terminat, nu datele din spatele lui. Daca pagina isi schimba felul de a
 * socoti, imaginea din masina se schimba odata cu ea, fara sa umblam aici.
 */
object DesenMasina {

    /** SVG-urile paginii sunt desenate in aceste sisteme de coordonate */
    private const val LAT_SURSA = 600f

    /**
     * Reface un desen din figurile citite din pagina.
     *
     * @param figuri lista de forme, asa cum le-a scos JavaScript din SVG
     * @param inaltimeSursa inaltimea sistemului de coordonate al paginii
     * @param latime cati pixeli sa aiba imaginea pentru ecranul masinii
     * @param peFundalInchis ecranul masinii e de obicei inchis la culoare
     */
    fun deseneaza(
        figuri: JSONArray,
        inaltimeSursa: Float,
        latime: Int = 720,
        peFundalInchis: Boolean = true
    ): Bitmap {
        val scara = latime / LAT_SURSA
        val inaltime = (inaltimeSursa * scara).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(latime, inaltime, Bitmap.Config.ARGB_8888)
        val panza = Canvas(bmp)
        panza.drawColor(Color.TRANSPARENT)

        val vopsea = Paint(Paint.ANTI_ALIAS_FLAG)

        for (i in 0 until figuri.length()) {
            val f = figuri.optJSONObject(i) ?: continue
            val culoare = culoare(f.optString("fill", ""), peFundalInchis)
            vopsea.alpha = 255
            val opac = f.optDouble("opacity", 1.0)

            when (f.optString("tip")) {
                "rect" -> {
                    vopsea.style = Paint.Style.FILL
                    vopsea.color = culoare
                    vopsea.alpha = (opac * 255).toInt().coerceIn(0, 255)
                    val x = f.optDouble("x").toFloat() * scara
                    val y = f.optDouble("y").toFloat() * scara
                    val w = f.optDouble("w").toFloat() * scara
                    val h = f.optDouble("h").toFloat() * scara
                    if (w <= 0f || h <= 0f) continue
                    val r = f.optDouble("rx").toFloat() * scara
                    panza.drawRoundRect(RectF(x, y, x + w, y + h), r, r, vopsea)
                }
                "line" -> {
                    vopsea.style = Paint.Style.STROKE
                    vopsea.color = culoare(f.optString("stroke", ""), peFundalInchis)
                    vopsea.strokeWidth = (f.optDouble("sw", 2.0).toFloat() * scara)
                        .coerceAtLeast(1.5f)
                    panza.drawLine(
                        f.optDouble("x1").toFloat() * scara, f.optDouble("y1").toFloat() * scara,
                        f.optDouble("x2").toFloat() * scara, f.optDouble("y2").toFloat() * scara,
                        vopsea
                    )
                }
            }
        }
        return bmp
    }

    /**
     * Cat de mult umblam la culorile paginii.
     *
     * Prima varianta le lumina mult, pornind de la ideea ca ecranul masinii e
     * negru. La proba pe masina adevarata s-a vazut ca panoul Android Auto e
     * albastru-gri mediu, nu negru. Pe fundalul acela, luminarea strica: rosul
     * de avertisment ajungea portocaliu si nu mai spunea nimic, iar fundalul
     * barei devenea aproape invizibil.
     *
     * Asa ca pastram culorile asa cum le-a ales pagina. Singura corectura e
     * pentru negrul aproape pur, care s-ar pierde in orice fundal inchis.
     */
    private fun culoare(cod: String, peFundalInchis: Boolean): Int {
        val c = try {
            if (cod.isBlank() || cod == "none") return Color.TRANSPARENT
            Color.parseColor(if (cod.startsWith("#")) cod else "#$cod")
        } catch (e: Exception) {
            return if (peFundalInchis) Color.LTGRAY else Color.DKGRAY
        }
        if (!peFundalInchis) return c

        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val lumina = (r * 299 + g * 587 + b * 114) / 1000
        return when {
            // doar negrul aproape pur: altfel se topeste in fundal
            lumina < 45 -> Color.rgb(
                (r + (255 - r) * 0.55).toInt(),
                (g + (255 - g) * 0.55).toInt(),
                (b + (255 - b) * 0.55).toInt()
            )
            else -> c
        }
    }

    /** JavaScript care scoate figurile dintr-un SVG al paginii */
    fun citireSvg(idSvgSauGrup: String): String = """
        (function(){
          var e = document.getElementById("$idSvgSauGrup");
          if(!e) return "[]";
          var svg = e.tagName.toLowerCase()==="svg" ? e : e.closest("svg");
          if(!svg) return "[]";
          var out = [];
          var noduri = svg.querySelectorAll("rect,line");
          for(var i=0;i<noduri.length;i++){
            var n = noduri[i];
            var st = window.getComputedStyle(n);
            if(st.display==="none" || st.visibility==="hidden") continue;
            // pozitia reala, dupa eventualele transformari (marcajul de medie
            // se muta prin transform, nu prin coordonate)
            var dx = 0, dy = 0;
            var p = n;
            while(p && p!==svg){
              var t = p.getAttribute && p.getAttribute("transform");
              if(t){ var m = /translate\(\s*(-?[\d.]+)[ ,]*(-?[\d.]+)?/.exec(t);
                     if(m){ dx += parseFloat(m[1])||0; dy += parseFloat(m[2])||0; } }
              p = p.parentNode;
            }
            if(n.tagName.toLowerCase()==="rect"){
              out.push({tip:"rect",
                x:(parseFloat(n.getAttribute("x"))||0)+dx,
                y:(parseFloat(n.getAttribute("y"))||0)+dy,
                w:parseFloat(n.getAttribute("width"))||0,
                h:parseFloat(n.getAttribute("height"))||0,
                rx:parseFloat(n.getAttribute("rx"))||0,
                fill:n.getAttribute("fill")||st.fill||"",
                opacity:parseFloat(n.getAttribute("opacity")||st.opacity||"1")});
            } else {
              out.push({tip:"line",
                x1:(parseFloat(n.getAttribute("x1"))||0)+dx,
                y1:(parseFloat(n.getAttribute("y1"))||0)+dy,
                x2:(parseFloat(n.getAttribute("x2"))||0)+dx,
                y2:(parseFloat(n.getAttribute("y2"))||0)+dy,
                stroke:n.getAttribute("stroke")||st.stroke||"",
                sw:parseFloat(n.getAttribute("stroke-width")||"2")});
            }
          }
          return JSON.stringify(out);
        })()
    """
}
