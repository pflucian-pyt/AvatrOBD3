#!/usr/bin/env python3
"""
avatr_server.py — serverul de stare al masinii.

Un singur fisier, numai biblioteca standard a lui Python. Fara pachete de
instalat, fara baza de date. Datele stau in fisiere, langa el.

Pornire:
    export AVATR_CHEIE="o-parola-lunga-inventata-de-tine"
    python3 avatr_server.py 8077

Ce face:
    POST /stare   — primeste starea, cu antetul X-Cheie
    GET  /stare   — intoarce ultima stare stiuta
    GET  /istoric — ultimele inregistrari
    GET  /        — o pagina de citit pe telefon

De ce fisiere si nu baza de date: e vorba de o masina si de cateva zeci de
octeti pe minut. O baza de date ar fi o piesa in plus de intretinut, pentru
nimic. Istoricul se taie singur cand trece de o limita.

Doua lucruri se trimit, nu unul: starea CURENTA si ultima stare de dinainte de
oprire. Cand masina se stinge, unitatea din bord tace, iar telefonul pierde
legatura cu adaptorul. Fara o insemnare separata, dupa cateva ore n-ai mai sti
daca ce vezi e de acum sau de asta-noapte. De aceea fiecare inregistrare are
ceasul ei, iar pagina scrie limpede cat de veche e.
"""
import json, os, sys, time, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

CHEIE = os.environ.get("AVATR_CHEIE", "")
DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "date")
ULTIMA = os.path.join(DIR, "ultima.json")
ISTORIC = os.path.join(DIR, "istoric.ndjson")
GRUPARE = os.path.join(DIR, "grupare")     # fisierele de grupare a celulelor
MAXIM_FISIER = 4 * 1024 * 1024             # un fisier de grupare, cel mult
MAXIM_ISTORIC = 8000           # randuri; peste asta se taie jumatatea veche
# De ce mai putine randuri decat inainte: o inregistrare nu mai are treizeci de
# cifre, ci tot ce scrie pagina plus cele 120 de celule, deci vreo 3 KB. Opt mii
# de randuri sunt cam 25 MB, si tot ai cateva zile de mers in ele.
lacat = threading.Lock()

os.makedirs(DIR, exist_ok=True)
os.makedirs(GRUPARE, exist_ok=True)


def citeste_ultima():
    try:
        with open(ULTIMA, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def scrie(stare):
    with lacat:
        stare["primit"] = int(time.time())
        tmp = ULTIMA + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(stare, f, ensure_ascii=False)
        os.replace(tmp, ULTIMA)          # scriere atomica: nu ramane fisier rupt

        with open(ISTORIC, "a", encoding="utf-8") as f:
            f.write(json.dumps(stare, ensure_ascii=False) + "\n")
        taie_istoric()


def taie_istoric():
    try:
        if not os.path.exists(ISTORIC):
            return
        with open(ISTORIC, encoding="utf-8") as f:
            randuri = f.readlines()
        if len(randuri) <= MAXIM_ISTORIC:
            return
        with open(ISTORIC, "w", encoding="utf-8") as f:
            f.writelines(randuri[len(randuri) // 2:])
    except Exception:
        pass


def nume_curat(n):
    """Numele vine din telefon, deci nu are voie sa aleaga unde scriem."""
    n = os.path.basename(n or "").strip()
    bun = "".join(c for c in n if c.isalnum() or c in "._-")
    if not bun.endswith(".json"):
        bun += ".json"
    return bun[-80:] if len(bun) > 4 else "grupare.json"


def scrie_grupare(nume, sursa, corp):
    """
    Fiecare cursa ajunge un fisier al ei, cu sursa in nume. Nu se suprascrie
    nimic: daca numele exista deja, punem un numar la coada. O cursa costa
    vreo suta de kilobiti, deci nu e nevoie de nimic mai deștept decat asta.
    """
    baza = nume_curat(nume)
    if sursa:
        curat = "".join(c for c in sursa if c.isalnum() or c in "._-")[:24]
        if curat:
            baza = curat + "_" + baza
    cale = os.path.join(GRUPARE, baza)
    q = 2
    while os.path.exists(cale):
        cale = os.path.join(GRUPARE, baza[:-5] + "_" + str(q) + ".json")
        q += 1
    with lacat:
        tmp = cale + ".tmp"
        with open(tmp, "wb") as f:
            f.write(corp)
        os.replace(tmp, cale)
    return os.path.basename(cale)


def lista_grupare():
    out = []
    try:
        for n in sorted(os.listdir(GRUPARE)):
            if not n.endswith(".json"):
                continue
            c = os.path.join(GRUPARE, n)
            out.append({"nume": n, "octeti": os.path.getsize(c),
                        "cand": int(os.path.getmtime(c))})
    except Exception:
        pass
    return out


def cheie_buna(h, cale):
    if not CHEIE:
        return True                       # fara cheie pusa, serverul e deschis
    if h.headers.get("X-Cheie") == CHEIE:
        return True
    return parse_qs(urlparse(cale).query).get("cheie", [""])[0] == CHEIE


PAGINA = r"""<!doctype html><html lang="ro"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Avatr — starea mașinii</title>
<style>
 body{margin:0;background:#0F161D;color:#E6EEF5;
   font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
 .p{max-width:640px;margin:0 auto;padding:18px}
 h1{font-size:20px;margin:0 0 4px}
 .v{font-size:44px;font-weight:700;margin:2px 0}
 /* "Stare" e text, nu cifra: la marimea cifrelor se rupea pe doua randuri */
 .v.text{font-size:26px;line-height:1.25}
 .u{font-size:16px;color:#94A3B4;font-weight:400}
 .c{background:#1E2733;border:1px solid #34414F;border-radius:14px;
    padding:12px 14px;margin-bottom:10px}
 .l{font-size:12px;letter-spacing:.06em;color:#94A3B4;text-transform:uppercase}
 .g{display:grid;grid-template-columns:1fr 1fr;gap:10px}
 .m{font-size:13px;color:#94A3B4}
 details summary{cursor:pointer;color:#94A3B4}
 .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));
   gap:2px 14px;margin-top:8px}
 .r{display:flex;justify-content:space-between;gap:8px;font-size:13px;color:#94A3B4;
   border-bottom:1px solid #1E2B38;padding:2px 0}
 .r b{color:#E6EEF5}
 a{color:#60B2FF}
 .vechi{color:#E6963C}
</style>
<div class="p">
 <h1>Avatr Monitor</h1>
 <p class="m" id="cand">se încarcă…</p>
 <div id="tot"></div>
</div>
<script>
var cheie=new URLSearchParams(location.search).get("cheie")||"";
function nr(x,z){ return (x===null||x===undefined||x==="")?"—":x; }
function varsta(s){
  if(s<90) return "acum "+Math.round(s)+" s";
  if(s<5400) return "acum "+Math.round(s/60)+" min";
  if(s<172800) return "acum "+Math.round(s/3600)+" h";
  return "acum "+Math.round(s/86400)+" zile";
}
function caseta(l,v,u,n){
  // daca valoarea nu incepe cu o cifra, o scriem mai mic: e o vorba, nu o masura
  var text = !/^[0-9\-]/.test(String(nr(v)));
  return '<div class="c"><p class="l">'+l+'</p><p class="v'+(text?' text':'')+'">'+nr(v)+
         ' <span class="u">'+(u||"")+'</span></p>'+
         (n?'<p class="m">'+n+'</p>':'')+'</div>';
}
function incarca(){
  fetch("/stare?cheie="+encodeURIComponent(cheie))
   .then(function(r){ if(!r.ok) throw new Error(r.status); return r.json(); })
   .then(function(d){
     if(!d){ document.getElementById("cand").textContent="Nicio dată încă."; return; }
     var s=Math.max(0,Date.now()/1000-(d.primit||0));
     var e=document.getElementById("cand");
     e.textContent="ultima dată "+varsta(s)+" · de la "+(d.sursa||"?");
     e.className="m"+(s>3600?" vechi":"");
     var h="";
     h+='<div class="g">';
     h+=caseta("Baterie",d.soc,"%",d.autonomie&&d.autonomie!=="—"?d.autonomie+" km rămași":"");
     h+=caseta("Sesiune",d.km,"km",(d.energie?d.energie+" kWh":"")+(d.durata?" · "+d.durata:""));
     h+='</div><div class="g">';
     h+=caseta("Ultimele 5 min",d.c5||d.consum5,"kWh/100",d.c5t||"");
     h+=caseta("Stare",d.stare,"",d.legatura||"");
     h+='</div>';
     if(d.lat&&d.lon){
       h+='<div class="c"><p class="l">Unde e</p><p class="m">'+
          d.lat.toFixed(5)+", "+d.lon.toFixed(5)+'</p>'+
          '<p><a target="_blank" href="https://www.openstreetmap.org/?mlat='+
          d.lat+'&mlon='+d.lon+'#map=17/'+d.lat+'/'+d.lon+'">deschide harta</a></p></div>';
     }
     if(d.cMin) { d.celMin=d.cMin; d.celMax=d.cMax; d.celAbatere=d.cDisp; d.celVerdict=d.cVerdict; }
     if(d.celMin){
       h+='<div class="c"><p class="l">Celule</p><p class="m">'+d.celMin+' – '+
          d.celMax+' V · abatere '+nr(d.celAbatere)+' mV · '+nr(d.celVerdict)+'</p>'+
          ((d.tMin||d.tempMin)?'<p class="m">temperaturi '+nr(d.tMin||d.tempMin)+
            '–'+nr(d.tMax||d.tempMax)+' °C</p>':'')+'</div>';
     }
     if(d.litri){
       h+='<div class="c"><p class="l">Rezervor</p><p class="v">'+d.litri+
          ' <span class="u">litri</span></p>'+
          (d.regim?'<p class="m">generator '+d.regim+
           (d.rpm&&d.rpm!=="0"?' · '+d.rpm+' rpm':'')+'</p>':'')+'</div>';
     }
     if(d.eNet||d.eBrut){
       h+='<div class="c"><p class="l">Consum sesiune</p><p class="m">'+
          nr(d.eNet)+' net · '+nr(d.eBrut)+' consumat · '+nr(d.eRegen)+
          ' recuperat kWh/100</p>'+(d.eNota?'<p class="m">'+d.eNota+'</p>':'')+'</div>';
     }
     // Starea masinii, cand va exista. Aplicatia trimite orice element marcat
     // in pagina cu data-server, deci aceste casete se aprind singure in ziua
     // in care parametrii de incuiere si de geamuri vor fi gasiti — fara sa
     // schimbam nimic nici aici, nici in Kotlin.
     var caroserie=[["incuiat","Încuiat"],["geamuri","Geamuri"],
                    ["portbagaj","Portbagaj"],["capota","Capotă"],
                    ["climatizare","Climatizare"]];
     var cs="";
     caroserie.forEach(function(x){
       if(d[x[0]]) cs+='<p class="m">'+x[1]+': <b>'+d[x[0]]+'</b></p>';
     });
     if(cs) h+='<div class="c"><p class="l">Starea mașinii</p>'+cs+'</div>';

     // orice altceva primit si necunoscut, ca sa nu se piarda nimic pe drum
     // celulele, cand vin: o fasie de bare, ca sa se vada dintr-o privire
     if(d.celuleMv){
       var cel=d.celuleMv.split(",").map(Number).filter(function(x){return x>1500;});
       if(cel.length>20){
         var mn=Math.min.apply(null,cel), mx=Math.max.apply(null,cel);
         var me=cel.reduce(function(a,b){return a+b;},0)/cel.length;
         var lat=560/cel.length, bare="";
         cel.forEach(function(v,i){
           var ab=v-me, h=Math.max(1,Math.min(38,Math.abs(ab)*1.6));
           bare+='<rect x="'+(20+i*lat).toFixed(1)+'" y="'+(ab>=0?40-h:40).toFixed(1)+
             '" width="'+Math.max(1,lat-0.5).toFixed(1)+'" height="'+h.toFixed(1)+
             '" fill="'+(ab>=0?"#4FA3E3":"#E0894F")+'"/>';
         });
         h+='<div class="c"><p class="l">Celule ('+cel.length+')</p>'
          +'<svg viewBox="0 0 600 88" style="width:100%">'+bare
          +'<line x1="20" y1="40" x2="580" y2="40" stroke="#33465A" stroke-width="1.2"/>'
          +'<text x="20" y="60" fill="#8A9AAB" font-size="15">celula 1</text>'
          +'<text x="580" y="60" text-anchor="end" fill="#8A9AAB" font-size="15">celula '
          +cel.length+'</text></svg>'
          +'<p class="m">minim <b>'+(mn/1000).toFixed(3)+' V</b> · maxim <b>'
          +(mx/1000).toFixed(3)+' V</b> · dispersie <b>'+(mx-mn)+' mV</b></p></div>';
       }
     }
     if(d.tempC){
       var t=d.tempC.split(",").map(function(x){
         var q=x.split(":"); return q[0]+" <b>"+q[1]+"</b>"; });
       h+='<div class="c"><p class="l">Senzori de temperatură ('+t.length+')</p>'
        +'<p class="m">'+t.join(" · ")+'</p></div>';
     }

     var stiute={sursa:1,primit:1,ultimaSuflare:1,celuleMv:1,tempC:1,soc:1,autonomie:1,autoNota:1,
       energie:1,recup:1,km:1,durata:1,vmed:1,viteza:1,c5:1,c5t:1,eNet:1,eBrut:1,
       eRegen:1,eVal:1,eUm:1,eSfat:1,eNota:1,stare:1,stareNota:1,legatura:1,
       cMin:1,cMax:1,cDisp:1,cVerdict:1,tMin:1,tMax:1,litri:1,rpm:1,regim:1,
       bord:1,lat:1,lon:1,consum5:1,celMin:1,celMax:1,celAbatere:1,celVerdict:1,
       tempMin:1,tempMax:1,proba:1};
     caroserie.forEach(function(x){ stiute[x[0]]=1; });
     var alte="", cate=0;
     Object.keys(d).sort().forEach(function(k){
       if(!stiute[k] && d[k]!==""&&d[k]!==null && typeof d[k]!=="object"){
         alte+='<div class="r"><span>'+k+'</span><b>'+d[k]+'</b></div>'; cate++;
       }
     });
     if(alte) h+='<div class="c"><details><summary class="l">Toate cifrele paginii ('
       +cate+')</summary><div class="grid">'+alte+'</div></details></div>';
     document.getElementById("tot").innerHTML=h;
   })
   .catch(function(e){
     document.getElementById("cand").textContent =
       (""+e.message==="401") ? "Cheie greșită." : "Nu pot citi: "+e.message;
   });
}
incarca(); setInterval(incarca,30000);
</script>
</html>"""


class Manevrant(BaseHTTPRequestHandler):
    server_version = "AvatrStare/1.0"

    def log_message(self, *a):
        pass                                  # fara zgomot in jurnal

    def _raspunde(self, cod, corp, tip="application/json; charset=utf-8"):
        b = corp.encode("utf-8") if isinstance(corp, str) else corp
        self.send_response(cod)
        self.send_header("Content-Type", tip)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        cale = urlparse(self.path).path
        if cale == "/":
            return self._raspunde(200, PAGINA, "text/html; charset=utf-8")
        if not cheie_buna(self, self.path):
            return self._raspunde(401, '{"eroare":"cheie"}')
        if cale == "/stare":
            d = citeste_ultima()
            return self._raspunde(200, json.dumps(d, ensure_ascii=False) if d else "null")
        if cale == "/grupare":
            # fara nume: lista; cu ?nume=...: chiar fisierul
            cerut = parse_qs(urlparse(self.path).query).get("nume", [""])[0]
            if not cerut:
                return self._raspunde(200, json.dumps(lista_grupare(), ensure_ascii=False))
            c = os.path.join(GRUPARE, nume_curat(cerut))
            if not os.path.exists(c):
                return self._raspunde(404, '{"eroare":"nu exista"}')
            with open(c, "rb") as f:
                return self._raspunde(200, f.read())
        if cale == "/istoric":
            try:
                cate = int(parse_qs(urlparse(self.path).query).get("cate", ["200"])[0])
            except Exception:
                cate = 200
            try:
                with open(ISTORIC, encoding="utf-8") as f:
                    randuri = f.readlines()[-max(1, min(cate, 5000)):]
            except Exception:
                randuri = []
            return self._raspunde(200, "[" + ",".join(r.strip() for r in randuri) + "]")
        self._raspunde(404, '{"eroare":"nu exista"}')

    def do_POST(self):
        cale = urlparse(self.path).path
        if cale not in ("/stare", "/grupare"):
            return self._raspunde(404, '{"eroare":"nu exista"}')
        if not cheie_buna(self, self.path):
            return self._raspunde(401, '{"eroare":"cheie"}')

        if cale == "/grupare":
            try:
                n = int(self.headers.get("Content-Length", "0"))
                if n <= 0 or n > MAXIM_FISIER:
                    return self._raspunde(400, '{"eroare":"marime"}')
                corp = self.rfile.read(n)
                json.loads(corp.decode("utf-8"))      # sa nu primim gunoi
            except Exception as e:
                return self._raspunde(400, json.dumps({"eroare": str(e)}))
            pus = scrie_grupare(self.headers.get("X-Fisier", ""),
                                self.headers.get("X-Sursa", ""), corp)
            return self._raspunde(200, json.dumps({"ok": True, "nume": pus}))

        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n <= 0 or n > 200000:
                return self._raspunde(400, '{"eroare":"marime"}')
            stare = json.loads(self.rfile.read(n).decode("utf-8"))
            if not isinstance(stare, dict):
                raise ValueError("nu e obiect")
        except Exception as e:
            return self._raspunde(400, json.dumps({"eroare": str(e)}))
        scrie(stare)
        self._raspunde(200, '{"ok":true}')


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8077
    if not CHEIE:
        print("ATENTIE: AVATR_CHEIE nu e pusa — oricine poate citi si scrie.")
    print(f"ascult pe portul {port}, datele in {DIR}")
    ThreadingHTTPServer(("0.0.0.0", port), Manevrant).serve_forever()
