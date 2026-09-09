package ro.avatr.monitor

import android.graphics.Color

/**
 * Culorile ecranului din masina, pe zi si pe noapte.
 *
 * Android Auto spune singur in ce mod e masina, prin carContext.isDarkMode, si
 * comuta odata cu farurile. Nu e doar fundalul care se schimba: pe noapte,
 * albul curat al paginii — facut pentru hartie — orbeste, iar cenusiurile
 * deschise devin mai stralucitoare decat cifrele. De asta sunt doua palete
 * intregi, nu una cu fundalul inversat.
 */
data class Paleta(
    val fond: Int,
    val caseta: Int,
    val margine: Int,
    val text: Int,
    val stins: Int,
    val accent: Int,
    val verde: Int,
    val rosu: Int,
    val portocaliu: Int,
    val pista: Int,
    val noapte: Boolean
) {
    companion object {
        val NOAPTE = Paleta(
            fond = Color.rgb(16, 22, 30),
            caseta = Color.rgb(30, 39, 51),
            margine = Color.rgb(52, 65, 82),
            text = Color.rgb(240, 245, 250),
            stins = Color.rgb(150, 164, 182),
            accent = Color.rgb(96, 178, 255),
            verde = Color.rgb(52, 199, 140),
            rosu = Color.rgb(232, 90, 78),
            portocaliu = Color.rgb(230, 150, 60),
            pista = Color.rgb(58, 70, 88),
            noapte = true
        )

        val ZI = Paleta(
            fond = Color.rgb(238, 242, 247),
            caseta = Color.WHITE,
            margine = Color.rgb(205, 214, 226),
            text = Color.rgb(20, 28, 40),
            stins = Color.rgb(96, 110, 128),
            accent = Color.rgb(0, 113, 197),
            verde = Color.rgb(0, 135, 90),
            rosu = Color.rgb(192, 40, 28),
            portocaliu = Color.rgb(178, 94, 0),
            pista = Color.rgb(222, 228, 238),
            noapte = false
        )

        fun pentru(intuneric: Boolean) = if (intuneric) NOAPTE else ZI
    }

    /**
     * Trece o culoare a paginii prin filtrul temei.
     *
     * Culorile din grafice le alege pagina, si le pastram: rosul de avertisment
     * trebuie sa ramana rosu. Umblam doar la capete, unde altfel s-ar pierde:
     * negrul aproape pur pe noapte, si albul aproape pur tot pe noapte.
     */
    fun dinPagina(cod: String?): Int {
        if (cod.isNullOrBlank() || cod == "none") return Color.TRANSPARENT
        val c = try {
            Color.parseColor(if (cod.startsWith("#")) cod else "#$cod")
        } catch (e: Exception) {
            return stins
        }
        if (!noapte) return c
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val lumina = (r * 299 + g * 587 + b * 114) / 1000
        return when {
            lumina < 45 -> Color.rgb(
                (r + (255 - r) * 0.55).toInt(),
                (g + (255 - g) * 0.55).toInt(),
                (b + (255 - b) * 0.55).toInt()
            )
            lumina > 228 -> Color.rgb((r * 0.55).toInt(), (g * 0.58).toInt(), (b * 0.62).toInt())
            else -> c
        }
    }
}
