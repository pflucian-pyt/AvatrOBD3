package ro.avatr.monitor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONArray

/**
 * Tot ce se vede pe ecranul masinii, desenat de mana.
 *
 * De ce asa: sabloanele Android Auto dau randuri de text si o imagine laterala,
 * nimic mai mult. Ca sa iasa asezarea din telefon — casete una langa alta, bare
 * cu cifrele lor, cadrane — e nevoie de panza intreaga, pe care o primesc doar
 * aplicatiile de navigatie. O avem, deci desenam.
 *
 * Doua lucruri s-au invatat la machete si merita pastrate in minte:
 *
 * 1. Barele se intind pe latime cu inaltime fixa, nu se scaleaza proportional.
 *    O bara nu e o fotografie; la scalare proportionala iesea din caseta si
 *    acoperea randul de dedesubt.
 *
 * 2. Nu incape totul pe un ecran. Masurat: blocurile cerute cer vreo 1390 px
 *    inaltime, ecranul are vreo 900. La micsorare pana incap, cifrele ajung
 *    nefolositoare in mers. De aceea sunt doua pagini si un buton de comutare.
 */
object Tablou {

    private const val PAGINI = 2

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gros = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val subt = Typeface.DEFAULT

    /**
     * Desenul se socoteste intr-un sistem lat de 1600, iar inaltimea vine din
     * forma zonei libere. Asa umplem tot ce ni s-a dat, in loc sa scalam un
     * desen fix si sa ramana benzi goale sus si jos.
     */
    private const val LAT_PROIECT = 1600f
    private var INALT_PROIECT = 900f
    /** cate casete pe rand; la o zona ingusta, patru ar fi prea mici */
    private var COLOANE = 4

    /**
     * @param zona portiunea de ecran chiar libera, spusa de Android Auto.
     *
     * Panza pe care o primim e cat tot ecranul masinii, dar peste ea stau bara
     * laterala si panoul de muzica. Gazda ne spune, prin onStableAreaChanged,
     * ce dreptunghi ramane descoperit. Fara asta desenam si dedesubtul lor, si
     * jumatate din tablou nu se vede — cum s-a si intamplat: coloana din
     * dreapta intra sub panoul de muzica.
     */
    fun deseneaza(c: Canvas, latime: Int, inaltime: Int, s: StareMasina,
                  pagina: Int, t: Paleta, zona: android.graphics.Rect? = null) {
        c.drawColor(t.fond)

        val z = if (zona != null && zona.width() > 100 && zona.height() > 100) zona
                else android.graphics.Rect(0, 0, latime, inaltime)

        // inaltimea de proiect urmeaza forma zonei: umplem tot, fara benzi goale
        INALT_PROIECT = LAT_PROIECT * z.height() / z.width()
        // sub o zona destul de lata, patru casete pe rand devin ilizibile
        COLOANE = if (INALT_PROIECT > 1050f) 2 else 4

        val k = z.width() / LAT_PROIECT
        c.save()
        c.clipRect(z)
        c.translate(z.left.toFloat(), z.top.toFloat())
        c.scale(k, k)
        if (pagina % PAGINI == 0) paginaConsum(c, s, t) else paginaEnergie(c, s, t)
        c.restore()
    }

    /** aseaza un sir de casete pe COLOANE coloane si intoarce y-ul de dupa */
    private fun casete(c: Canvas, t: Paleta, y0: Float, inalt: Float,
                       lista: List<Casetuta>): Float {
        val m = 36f; val g = 16f
        val lc = (LAT_PROIECT - 2 * m - (COLOANE - 1) * g) / COLOANE
        var y = y0
        for ((i, b) in lista.withIndex()) {
            val col = i % COLOANE
            if (col == 0 && i > 0) y += inalt + g
            caseta(c, t, m + col * (lc + g), y, lc, inalt,
                   b.et, b.valoare, b.unitate, b.nota, b.culoare ?: t.text)
        }
        return y + inalt + 14f
    }

    private class Casetuta(val et: String, val valoare: String, val unitate: String,
                           val nota: String, val culoare: Int? = null)

    // ------------------------------------------------------------- pagina intai

    private fun paginaConsum(c: Canvas, s: StareMasina, t: Paleta) {
        antet(c, s, t, "${s.stare} · ${s.kmSesiune} km")

        val m = 36f; val lb = LAT_PROIECT - 2 * m - 40f
        val hc = if (COLOANE == 4) 145f else 130f

        var y = casete(c, t, 84f, hc, listOf(
            Casetuta("AUTONOMIE", s.autonomie, "km", "baterie ${s.soc} %", t.accent),
            Casetuta("ENERGIE SESIUNE", s.energie, "kWh", "recuperat ${s.recuperat} kWh"),
            Casetuta("ULTIMELE 5 MINUTE", s.consum5, "kWh/100", s.consum5Nota),
            Casetuta("CONSUM ACUM", s.consumVal, s.consumUm, s.consumNota,
                     if (s.consumRau) t.rosu else t.text)
        ))

        // ce ramane se imparte intre bara de consum, casetele de celule si grafic
        val ramas = INALT_PROIECT - y - 24f
        val hRanduriCelule = if (COLOANE == 4) 1 else 2
        val hCel = hc * hRanduriCelule + (hRanduriCelule - 1) * 16f
        val hb = (ramas - hCel - 28f) * 0.46f
        val h3 = ramas - hCel - 28f - hb

        cadru(c, t, m, y, LAT_PROIECT - m, y + hb)
        eticheta(c, t, m + 20f, y + 13f, "CONSUM ELECTRIC · SESIUNE · PE VERTICALĂ 0–45 kWh/100")
        var x = m + 20f
        for ((et, valoare, cul) in listOf(
            Triple("net", s.eNet, t.text),
            Triple("consumat", s.eBrut, t.portocaliu),
            Triple("recuperat", s.eRegen, t.verde)
        )) {
            p.typeface = gros; p.textSize = 36f; p.color = cul
            c.drawText(valoare, x, y + 68f, p)
            val lv = p.measureText(valoare)
            p.typeface = subt; p.textSize = 20f; p.color = t.stins
            c.drawText(et, x + lv + 8f, y + 68f, p)
            x += lv + 8f + p.measureText(et) + 32f
        }
        p.typeface = subt; p.textSize = 18f; p.color = t.stins
        c.drawText(s.eNota, m + 20f, y + 96f, p)
        bara(c, t, s.figConsum, m + 20f, y + 110f, lb, (hb - 152f).coerceAtLeast(34f), 62f)
        // Graficul nu mai e o bara cu scara pe orizontala, ci consumul pe timp:
        // o linie pe secunda, cu trecutul strans spre stanga. Deci si numele de
        // sub el sunt de timp. Ruptura, unde incep ultimele doua minute, cade la
        // 0,656 din latime — vine din PARTE_NOUA = 0,35 din bord2.js.
        for ((et, fr, laDreapta) in listOf(
            Triple("anterior", 0f, false), Triple("2 min", 0.656f, false),
            Triple("acum", 1f, true)
        )) {
            val xx = m + 20f + lb * fr
            val lat = p.measureText(et)
            c.drawText(et, if (laDreapta) xx - lat else xx, y + hb - 12f, p)
        }
        y += hb + 14f

        y = casete(c, t, y, hc, listOf(
            Casetuta("CELULĂ MINIMĂ", s.celMin, "V", "din ${s.celNumar} celule"),
            Casetuta("CELULĂ MAXIMĂ", s.celMax, "V", ""),
            Casetuta("ABATERE", s.celAbatere, "mV", s.celVerdict,
                     if (s.celVerdict == "excelent" || s.celVerdict == "bun") t.verde
                     else t.portocaliu),
            Casetuta("TEMPERATURI", "${s.tempMin}–${s.tempMax}", "°C",
                     "diferență ${s.tempDif} °C")
        ))

        cadru(c, t, m, y, LAT_PROIECT - m, y + h3)
        eticheta(c, t, m + 20f, y + 11f, "ABATEREA FIECĂREI CELULE FAȚĂ DE MEDIE")
        bara(c, t, s.figCelule, m + 20f, y + 38f, lb, (h3 - 72f).coerceAtLeast(40f), 120f)
        p.typeface = subt; p.textSize = 18f; p.color = t.stins
        c.drawText("celula 1", m + 20f, y + h3 - 10f, p)
        val ult = "celula ${s.celNumar}"
        c.drawText(ult, LAT_PROIECT - m - 20f - p.measureText(ult), y + h3 - 10f, p)
        numarPagina(c, t, 1)
    }

    // ------------------------------------------------------------- pagina a doua

    private fun paginaEnergie(c: Canvas, s: StareMasina, t: Paleta) {
        antet(c, s, t, "energie și extender")

        val m = 36f; val lb = LAT_PROIECT - 2 * m - 40f
        val hc = if (COLOANE == 4) 140f else 126f

        val yb = casete(c, t, 84f, hc, listOf(
            Casetuta("PRIZĂ", s.hKwhE, "kWh", "curat din priză ${s.hPriza} kWh", t.accent),
            Casetuta("KM PE PRIZĂ", s.hKmE, "km", s.hCE),
            Casetuta("REZERVOR", s.hLitB, "litri", "pus în baterie ${s.hGenIn} kWh",
                     t.portocaliu),
            Casetuta("KM PE BENZINĂ", s.hKmB, "km", s.hCB)
        ))
        val hb = 96f
        cadru(c, t, m, yb, LAT_PROIECT - m, yb + hb)
        eticheta(c, t, m + 20f, yb + 12f, "DE UNDE VINE ENERGIA")
        bara(c, t, s.figEnergie, m + 20f, yb + 40f, lb, 24f, 26f)
        p.typeface = subt; p.textSize = 19f; p.color = t.accent
        c.drawText("priză · ${s.hTxtE}", m + 20f, yb + hb - 12f, p)
        p.color = t.portocaliu
        val tb = "rezervor · ${if (s.hTxtB.isBlank()) "—" else s.hTxtB}"
        c.drawText(tb, LAT_PROIECT - m - 20f - p.measureText(tb), yb + hb - 12f, p)

        val y2 = yb + hb + 14f; val h2 = INALT_PROIECT - y2 - 46f
        // pe o zona ingusta cadranele se apropie de mijloc, ca sa nu iasa afara
        val dep = if (COLOANE == 4) 250f else 190f
        cadru(c, t, m, y2, LAT_PROIECT - m, y2 + h2)
        val cx1 = m + dep; val cx2 = LAT_PROIECT - m - dep; val cyc = y2 + h2 / 2f

        // regimul intra in titlu: sub cadran ar cadea peste valoarea lichidului
        cadran(c, t, cx1, cyc - 10f, 102f, s.arcRpm, "EXTENDER · ${s.bRegim}",
               s.bRpm, "rpm", "", semicerc = true,
               culoare = if (s.arcRpm > 0f) t.portocaliu else t.stins)

        val trei = listOf(
            Triple("SARCINĂ", s.sarcina, "%"),
            Triple("LICHID", s.lichid, "°C"),
            Triple("ADMISIE", s.admisie, "°C")
        )
        val yy = cyc + 58f
        for ((i, v) in trei.withIndex()) {
            val xc = cx1 - 152f + i * 152f
            p.typeface = gros; p.textSize = 16f; p.color = t.stins
            c.drawText(v.first, xc - p.measureText(v.first) / 2f, yy + 14f, p)
            val sir = "${v.second} ${v.third}"
            p.textSize = 30f; p.color = t.text
            c.drawText(sir, xc - p.measureText(sir) / 2f, yy + 48f, p)
        }
        p.typeface = subt; p.textSize = 18f; p.color = t.stins
        val bord = "baterie de bord ${s.bord} V"
        c.drawText(bord, cx1 - p.measureText(bord) / 2f, yy + 78f, p)

        cadran(c, t, cx2, cyc - 6f, 102f, s.arcLitri, "LITRI", s.bLitri,
               "litri în rezervor", s.bRez, semicerc = false, culoare = t.portocaliu)

        p.color = t.margine; p.strokeWidth = 2f
        for (xx in listOf(m + dep * 2f, LAT_PROIECT - m - dep * 2f))
            c.drawLine(xx, y2 + 24f, xx, y2 + h2 - 24f, p)

        val col = listOf(
            Triple("DEBIT", s.bLh, "l/h"),
            Triple("PUTERE", s.bPgen, "kW"),
            Triple("RANDAMENT", s.bRand, "kWh/l"),
            Triple("AUTONOMIE GENERATOR", s.bAuto, "km")
        )
        val x0 = m + dep * 2f + 46f; val lat = (LAT_PROIECT - 2 * m - dep * 4f - 92f) / 2f
        for ((i, v) in col.withIndex()) {
            val xx = x0 + (i % 2) * lat; val yy2 = y2 + 44f + (i / 2) * 104f
            p.typeface = gros; p.textSize = 17f; p.color = t.stins
            c.drawText(v.first, xx, yy2 + 14f, p)
            p.textSize = 36f; p.color = t.text
            c.drawText(v.second, xx, yy2 + 52f, p)
            val lv = p.measureText(v.second)
            p.typeface = subt; p.textSize = 18f; p.color = t.stins
            c.drawText(v.third, xx + lv + 8f, yy2 + 52f, p)
        }
        p.typeface = subt; p.textSize = 17f; p.color = t.stins
        c.drawText(s.bCalib.take(78), x0, y2 + h2 - 20f, p)
        numarPagina(c, t, 2)
    }

    // ------------------------------------------------------------------ bucatele

    private fun antet(c: Canvas, s: StareMasina, t: Paleta, dreapta: String) {
        p.typeface = gros; p.textSize = 27f; p.color = t.text
        c.drawText("Avatr Monitor", 36f, 46f, p)
        val lat = p.measureText("Avatr Monitor")

        // ceasul telefonului, mare, in coltul din dreapta
        val ceas = ceasAcum()
        p.textSize = 46f; p.color = t.text
        val latCeas = p.measureText(ceas)
        c.drawText(ceas, LAT_PROIECT - 36f - latCeas, 52f, p)

        // bulina de stare: primul lucru la care se uita ochiul, chiar langa nume
        val cul = when (s.legatura) {
            StareMasina.LEGAT -> t.verde
            StareMasina.TACE -> t.portocaliu
            else -> t.rosu
        }
        // textul e chiar al paginii, cand il avem: asa cele doua ecrane spun
        // acelasi lucru. Al nostru rămâne doar ca rezervă.
        val text = s.textLegatura.ifBlank {
            when (s.legatura) {
                StareMasina.LEGAT -> "citesc date"
                StareMasina.TACE -> "OBD nu răspunde"
                else -> "deconectat"
            }
        }
        val x = 36f + lat + 26f
        p.color = cul
        c.drawCircle(x + 9f, 39f, 9f, p)
        p.textSize = 22f
        c.drawText(text, x + 28f, 46f, p)

        p.typeface = subt; p.textSize = 21f; p.color = t.stins
        val latDr = p.measureText(dreapta)
        c.drawText(dreapta, LAT_PROIECT - 36f - latCeas - 26f - latDr, 46f, p)
        p.color = t.margine; p.strokeWidth = 2f
        c.drawLine(36f, 68f, LAT_PROIECT - 36f, 68f, p)
    }

    private fun ceasAcum(): String {
        val c = java.util.Calendar.getInstance()
        return String.format(java.util.Locale.US, "%d:%02d",
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private fun numarPagina(c: Canvas, t: Paleta, n: Int) {
        p.typeface = gros; p.textSize = 20f; p.color = t.stins
        c.drawText("$n / $PAGINI", LAT_PROIECT - 150f, INALT_PROIECT - 8f, p)
    }

    private fun cadru(c: Canvas, t: Paleta, x1: Float, y1: Float, x2: Float, y2: Float) {
        p.style = Paint.Style.FILL; p.color = t.caseta
        c.drawRoundRect(RectF(x1, y1, x2, y2), 14f, 14f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = t.margine
        c.drawRoundRect(RectF(x1, y1, x2, y2), 14f, 14f, p)
        p.style = Paint.Style.FILL
    }

    private fun eticheta(c: Canvas, t: Paleta, x: Float, y: Float, text: String) {
        p.typeface = gros; p.textSize = 19f; p.color = t.stins
        c.drawText(text, x, y + 16f, p)
    }

    private fun caseta(c: Canvas, t: Paleta, x: Float, y: Float, w: Float, h: Float,
                       et: String, valoare: String, unitate: String, nota: String,
                       culVal: Int = t.text) {
        cadru(c, t, x, y, x + w, y + h)
        eticheta(c, t, x + 20f, y + 15f, et)
        p.typeface = gros; p.textSize = 44f; p.color = culVal
        c.drawText(valoare, x + 20f, y + 78f, p)
        val lv = p.measureText(valoare)
        p.typeface = subt; p.textSize = 21f; p.color = t.stins
        if (unitate.isNotBlank()) c.drawText(unitate, x + 26f + lv, y + 78f, p)
        if (nota.isNotBlank()) {
            p.textSize = 18f
            val linii = rupe(nota, w - 40f)
            var yy = y + h - 22f - 20f * (linii.size - 1)
            for (l in linii) { c.drawText(l, x + 20f, yy, p); yy += 20f }
        }
    }

    /** rupe o nota in cel mult doua randuri, ca sa nu iasa din caseta */
    private fun rupe(text: String, latime: Float): List<String> {
        val cuvinte = text.split(" ")
        val linii = ArrayList<String>()
        var cur = ""
        for (cv in cuvinte) {
            val incercare = if (cur.isEmpty()) cv else "$cur $cv"
            if (p.measureText(incercare) > latime && cur.isNotEmpty()) { linii.add(cur); cur = cv }
            else cur = incercare
        }
        if (cur.isNotEmpty()) linii.add(cur)
        return linii.take(2)
    }

    /**
     * Intinde un desen al paginii pe latime, cu inaltime fixa.
     * Scalarea proportionala scotea barele din caseta.
     */
    private fun bara(c: Canvas, t: Paleta, figuri: JSONArray?, x: Float, y: Float,
                     w: Float, h: Float, inaltimeSursa: Float) {
        val f = figuri ?: return
        val sx = w / 600f; val sy = h / inaltimeSursa
        for (i in 0 until f.length()) {
            val o = f.optJSONObject(i) ?: continue
            when (o.optString("tip")) {
                "rect" -> {
                    val cul = t.dinPagina(o.optString("fill"))
                    if (cul == Color.TRANSPARENT) continue
                    val lw = o.optDouble("w").toFloat(); val lh = o.optDouble("h").toFloat()
                    if (lw <= 0f || lh <= 0f) continue
                    p.style = Paint.Style.FILL; p.color = cul
                    p.alpha = (o.optDouble("opacity", 1.0) * 255).toInt().coerceIn(0, 255)
                    val ox = o.optDouble("x").toFloat(); val oy = o.optDouble("y").toFloat()
                    c.drawRect(x + ox * sx, y + oy * sy,
                               x + (ox + lw) * sx, y + (oy + lh) * sy, p)
                    p.alpha = 255
                }
                "line" -> {
                    val cul = t.dinPagina(o.optString("stroke"))
                    if (cul == Color.TRANSPARENT) continue
                    p.style = Paint.Style.STROKE; p.color = cul
                    p.strokeWidth = (o.optDouble("sw", 2.0).toFloat() * sx).coerceAtLeast(2f)
                    c.drawLine(x + o.optDouble("x1").toFloat() * sx,
                               y + o.optDouble("y1").toFloat() * sy,
                               x + o.optDouble("x2").toFloat() * sx,
                               y + o.optDouble("y2").toFloat() * sy, p)
                    p.style = Paint.Style.FILL
                }
            }
        }
        p.style = Paint.Style.FILL
    }

    private fun cadran(c: Canvas, t: Paleta, cx: Float, cy: Float, r: Float, fractie: Float,
                       titlu: String, valoare: String, unitate: String, sub: String,
                       semicerc: Boolean, culoare: Int) {
        val gr = 14f
        val cutie = RectF(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.STROKE; p.strokeWidth = gr; p.strokeCap = Paint.Cap.ROUND
        p.color = t.pista
        if (semicerc) c.drawArc(cutie, 180f, 180f, false, p)
        else c.drawArc(cutie, 0f, 360f, false, p)
        if (fractie > 0f) {
            p.color = culoare
            if (semicerc) c.drawArc(cutie, 180f, 180f * fractie, false, p)
            else c.drawArc(cutie, -90f, 360f * fractie, false, p)
        }
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT   // altfel liniile desenate dupa ies rotunjite

        p.typeface = gros; p.textSize = 17f; p.color = t.stins
        c.drawText(titlu, cx - p.measureText(titlu) / 2f, cy - r - 18f, p)

        val dy = if (semicerc) 20f else 8f
        p.textSize = 46f; p.color = t.text
        c.drawText(valoare, cx - p.measureText(valoare) / 2f, cy + dy, p)
        p.typeface = subt; p.textSize = 19f; p.color = t.stins
        if (unitate.isNotBlank())
            c.drawText(unitate, cx - p.measureText(unitate) / 2f, cy + dy + 26f, p)
        if (sub.isNotBlank()) {
            p.textSize = 18f
            c.drawText(sub, cx - p.measureText(sub) / 2f, cy + r + 22f, p)
        }
    }
}
