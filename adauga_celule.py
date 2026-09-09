#!/usr/bin/env python3
"""
adauga_celule.py — repara urmarirea hartii, si adauga istoricul celulelor
daca pagina nu are deja unul.

Folosire:
    python3 adauga_celule.py Avatr_Monitor_v25.html

Scrie alaturi un fisier cu "_plus" in nume. Originalul ramane neatins.

Peticul face doua lucruri.

1. HARTA. Pagina foloseste un singur indicator, "urmareste", pentru doua
   lucruri diferite: urmarirea pozitiei tale reale si urmarirea redarii. Cand
   apesi "Reda traseul", el devine true, iar de atunci fiecare fix GPS muta
   harta la unde esti ACUM, smulgand-o de pe traseul revizuit. Le despartim in
   doua: una pentru pozitia vie, alta pentru redare. Cat esti in revizuire,
   pozitia vie nu mai misca harta, deci apropierea aleasa de tine ramane.

2. CELULE. Pagina nu pastreaza nimic despre celule: "V.celule" e o lista vie de
   120 de tensiuni, suprascrisa la fiecare citire. Adaugam un istoric propriu,
   cu esantioane rare, si o fereastra care se deschide la apasarea pe o celula.
"""
import sys, os, re

# ---------------------------------------------------------------- codul adaugat

ISTORIC_JS = r"""
  // ===========================================================
  //  ISTORICUL CELULELOR
  //  Pagina nu pastra nimic despre celule in afara de min, max si dispersie.
  //  Aici tinem, rar si compact, cate un esantion cu abaterea fiecarei celule,
  //  plus curentul, tensiunea si temperaturile din clipa aceea. Cu ele se poate
  //  raspunde la intrebarea care conteaza: celula asta a fost mereu asa, sau a
  //  inceput sa se departeze?
  //
  //  De ce abateri in milivolti, si nu tensiuni: un intreg mic ocupa de trei
  //  ori mai putin decat "3.2417", iar abaterea e chiar lucrul urmarit. Media
  //  o pastram o data pe esantion, deci tensiunea se reface exact.
  // ===========================================================
  var IST = (function(){
    var CHEIE="avatrCelIst", PAS=60000, MAXIM=720;   // 12 ore la un esantion pe minut
    var date={v:1, e:[]}, ultim=0;

    try{
      var brut=localStorage.getItem(CHEIE);
      if(brut){ var d=JSON.parse(brut); if(d&&d.e) date=d; }
    }catch(e){}

    function salveaza(){
      try{ localStorage.setItem(CHEIE,JSON.stringify(date)); }
      catch(e){
        // spatiul s-a umplut: aruncam jumatatea veche si reincercam o data
        date.e=date.e.slice(Math.floor(date.e.length/2));
        try{ localStorage.setItem(CHEIE,JSON.stringify(date)); }catch(x){}
      }
    }

    function rareste(){
      // pastram tot ce e recent si raresc trecutul, ca istoricul lung sa
      // incapa fara sa pierdem detaliul de azi
      if(date.e.length<=MAXIM) return;
      var pastrate=[], jumatate=Math.floor(date.e.length/2);
      for(var i=0;i<date.e.length;i++){
        if(i>=jumatate || i%2===0) pastrate.push(date.e[i]);
      }
      date.e=pastrate;
    }

    function adauga(){
      if(!V.celule || V.celule.length<20) return;
      var t=Date.now();
      if(t-ultim<PAS) return;
      ultim=t;
      var c=V.celule, n=c.length;
      var med=c.reduce(function(a,b){return a+b;},0)/n;
      var ab=new Array(n);
      for(var i=0;i<n;i++) ab[i]=Math.round((c[i]-med)*1000);
      date.e.push({
        t:t, n:n, med:Math.round(med*10000)/10000, ab:ab,
        soc:(V.soc!==undefined&&V.soc!==null)?Math.round(V.soc*10)/10:null,
        cur:(V.curent!==undefined&&V.curent!==null)?Math.round(V.curent*10)/10:null,
        ten:(V.tensiune!==undefined&&V.tensiune!==null)?Math.round(V.tensiune*10)/10:null,
        tmin:(V.tempMin!==undefined&&V.tempMin!==null)?V.tempMin:null,
        tmax:(V.tempMax!==undefined&&V.tempMax!==null)?V.tempMax:null
      });
      rareste(); salveaza();
    }

    function pentru(idx){
      var r=[];
      for(var i=0;i<date.e.length;i++){
        var s=date.e[i];
        if(idx<s.n) r.push({t:s.t, ab:s.ab[idx], v:s.med+s.ab[idx]/1000,
                            soc:s.soc, cur:s.cur, ten:s.ten,
                            tmin:s.tmin, tmax:s.tmax});
      }
      return r;
    }

    return {adauga:adauga, pentru:pentru, cate:function(){return date.e.length;},
            sterge:function(){ date={v:1,e:[]}; salveaza(); }};
  })();

  // ---------- fereastra cu istoricul unei celule ----------
  function aratsIstoricCelula(idx){
    var p=G("celFereastra"); if(!p) return;
    var ist=IST.pentru(idx);
    G("celTitlu").textContent="Celula "+(idx+1);
    var g=G("celGrafic"); g.innerHTML="";

    if(ist.length<2){
      G("celRezumat").textContent=
        "Încă nu sunt destule măsurători. Se adaugă una pe minut cât timp aplicația citește ("
        +IST.cate()+" până acum).";
      p.style.display="block"; return;
    }

    var abs=ist.map(function(x){return x.ab;});
    var jos=Math.min.apply(null,abs), sus=Math.max.apply(null,abs);
    var mrj=Math.max(3,(sus-jos)*0.25); jos-=mrj; sus+=mrj;
    var W=600, H=150, st=34;
    function X(i){ return st+(W-st-8)*(i/(ist.length-1)); }
    function Y(a){ return 12+(H-30)*(1-(a-jos)/(sus-jos)); }

    // linia lui zero, ca ochiul sa vada imediat de ce parte sta celula
    if(jos<0 && sus>0){
      g.appendChild(el("line",{x1:st,y1:Y(0).toFixed(1),x2:W-8,y2:Y(0).toFixed(1),
        stroke:"#9AA7B4","stroke-width":1,"stroke-dasharray":"3 3"}));
    }
    var d="";
    for(var i=0;i<ist.length;i++) d+=(i?"L":"M")+X(i).toFixed(1)+" "+Y(ist[i].ab).toFixed(1)+" ";
    g.appendChild(el("path",{d:d,fill:"none",stroke:"#0071C5","stroke-width":2.4,
      "stroke-linejoin":"round"}));
    g.appendChild(el("text",{x:4,y:16,fill:"#5C6B7A","font-size":12},sus.toFixed(0)+" mV"));
    g.appendChild(el("text",{x:4,y:H-16,fill:"#5C6B7A","font-size":12},jos.toFixed(0)+" mV"));

    var u=ist[ist.length-1], v0=ist[0];
    var ore=Math.max(1,Math.round((u.t-v0.t)/3600000));
    function ora(t){ var z=new Date(t);
      return ("0"+z.getHours()).slice(-2)+":"+("0"+z.getMinutes()).slice(-2); }
    var deriva=u.ab-v0.ab;
    // formatare ca in restul paginii: virgula zecimala, si "—" pentru ce lipseste
    function nr(x,z){ return (x===null||x===undefined||isNaN(x))
      ? "—" : x.toFixed(z).replace(".",","); }
    function temp(a,b){ return (a===null||a===undefined)
      ? "— °C" : (nr(a,0)+"–"+((b===null||b===undefined)?"—":nr(b,0))+" °C"); }
    G("celRezumat").innerHTML=
      "<b>"+nr(u.v,3)+" V</b> acum · abatere <b>"+(u.ab>0?"+":"")+u.ab+" mV</b><br>"+
      ist.length+" măsurători pe "+ore+" h, între "+ora(v0.t)+" și "+ora(u.t)+"<br>"+
      "s-a mutat cu "+(deriva>0?"+":"")+deriva+" mV față de prima măsurătoare<br>"+
      "atunci: "+nr(v0.cur,1)+" A · "+temp(v0.tmin,v0.tmax)+" · SOC "+nr(v0.soc,1)+" %<br>"+
      "acum: "+nr(u.cur,1)+" A · "+temp(u.tmin,u.tmax)+" · SOC "+nr(u.soc,1)+" %";
    p.style.display="block";
  }
"""

FEREASTRA_HTML = """      <div id="celFereastra" style="display:none;margin-top:10px;padding:10px;
        border:1px solid var(--linie);border-radius:12px;background:var(--card)">
        <div class="rand" style="justify-content:space-between;align-items:center">
          <p class="lbl" id="celTitlu" style="margin:0">Celula</p>
          <button class="mm" id="celInchide">Închide</button>
        </div>
        <svg width="100%" viewBox="0 0 600 150" style="margin-top:6px"
          aria-label="Abaterea celulei în timp"><g id="celGrafic"></g></svg>
        <p class="mic" id="celRezumat" style="margin-top:4px">—</p>
      </div>
"""


def petic(h):
    schimbari = []

    # ---- 1. harta: doua urmariri in loc de una
    if "urmarestePozitia" not in h:
        h = h.replace(
            "var mapa=null, straturi=[], mapaPornita=false, urmareste=true;",
            "var mapa=null, straturi=[], mapaPornita=false;\n"
            "  // doua urmariri diferite, care inainte erau una singura: cand apasai\n"
            "  // \"Reda traseul\", fixul GPS muta harta la pozitia ta de acum\n"
            "  var urmarestePozitia=true, urmaresteRedarea=false;", 1)
        h = h.replace('mapa.on("dragstart",function(){ urmareste=false; });',
                      'mapa.on("dragstart",function(){ urmarestePozitia=false; urmaresteRedarea=false; });', 1)
        h = h.replace("if(urmareste) mapa.panTo([poz.lat,poz.lon],{animate:false});",
                      "if(urmarestePozitia && !revizuire) mapa.panTo([poz.lat,poz.lon],{animate:false});", 1)
        h = h.replace("if(urmareste&&mapa) mapa.panTo([g.p2.lat,g.p2.lon],{animate:false});",
                      "if(urmaresteRedarea&&mapa) mapa.panTo([g.p2.lat,g.p2.lon],{animate:false});", 1)
        # ce ramane: pornirea hartii, butonul de centrare, revenirea si redarea
        h = h.replace("mapaPornita=true; urmareste=true;", "mapaPornita=true; urmarestePozitia=true;", 1)
        h = h.replace("urmareste=false;", "urmarestePozitia=false; urmaresteRedarea=false;", 1)  # arataDrum
        h = re.sub(r"(\n\s*)urmareste=true;(\s*\n\s*pasRedare\(\);)",
                   r"\1urmaresteRedarea=true;\2", h)
        h = h.replace("urmareste=true;", "urmarestePozitia=true; urmaresteRedarea=false;")
        if "urmareste=" in h.replace("urmarestePozitia=", "").replace("urmaresteRedarea=", ""):
            print("ATENTIE: a ramas un 'urmareste=' neinlocuit")
        schimbari.append("harta nu mai fuge la pozitia curenta in revizuire")

    # ---- 2. istoricul celulelor
    # De la v25, pagina are istoric propriu pe IndexedDB si celulele se pot
    # apasa deja. A-l mai adauga ar insemna doua istorice paralele care nu se
    # potrivesc intre ele. Deci il punem numai daca lipseste.
    are_deja = ("avatrCelule" in h) or ('G("cBare").parentNode.addEventListener' in h)
    if are_deja:
        print("  · pagina are istoric propriu de celule; sar peste al meu")
    if not are_deja and "avatrCelIst" not in h:
        ancora = "  // ---------- navigare ----------"
        if ancora not in h:
            print("EROARE: nu gasesc unde sa pun modulul"); sys.exit(1)
        h = h.replace(ancora, ISTORIC_JS + "\n" + ancora, 1)
        schimbari.append("modulul de istoric")

        # Esantionam din "actualizeaza", nu de pe calea Bluetooth.
        # Pe calea aceea trece doar cititul real; simularea pune celulele
        # direct in V, si atunci nu s-ar strange nimic la probe. "actualizeaza"
        # e locul prin care trec toate sursele, iar modulul isi tine singur
        # pasul de un minut, deci apelul des nu costa nimic.
        h = h.replace("  function actualizeaza(){\n    esteParcat();",
                      "  function actualizeaza(){\n    try{ IST.adauga(); }catch(e){}\n    esteParcat();", 1)
        schimbari.append("esantionare din actualizeaza, orice sursa")

        # barele devin apasabile
        h = h.replace(
            '        g.appendChild(el("rect",{x:(20+i*lat).toFixed(1),y:y.toFixed(1),\n'
            '          width:Math.max(lat-0.6,0.8).toFixed(1),height:Math.max(h,1).toFixed(1),\n'
            '          rx:0.6,fill:cul}));',
            '        var bara=el("rect",{x:(20+i*lat).toFixed(1),y:y.toFixed(1),\n'
            '          width:Math.max(lat-0.6,0.8).toFixed(1),height:Math.max(h,1).toFixed(1),\n'
            '          rx:0.6,fill:cul});\n'
            '        // Zona de apasare e mai lata decat bara: la 120 de celule, o bara\n'
            '        // are sub 5 px si nu poate fi nimerita cu degetul. Sta intr-un\n'
            '        // strat separat, nu in "cBare": altfel numaratoarea barelor iese\n'
            '        // dubla, si chiar asa a picat proba care verifica cele 120.\n'
            '        var ga=G("cAtins");\n'
            '        if(ga){\n'
            '          if(i===0) ga.innerHTML="";\n'
            '          var atins=el("rect",{x:(20+i*lat-1.5).toFixed(1),y:"6",\n'
            '            width:Math.max(lat+3,7).toFixed(1),height:"104",\n'
            '            fill:"transparent",style:"cursor:pointer"});\n'
            '          (function(k){ atins.addEventListener("click",function(){\n'
            '            try{ aratsIstoricCelula(k); }catch(e){} }); })(i);\n'
            '          ga.appendChild(atins);\n'
            '        }\n'
            '        g.appendChild(bara);', 1)
        # stratul de apasare, frate cu barele
        h = h.replace('<g id="cBare"></g>', '<g id="cBare"></g><g id="cAtins"></g>', 1)
        schimbari.append("barele de celule se pot apasa")

        # fereastra, imediat sub graficul de celule
        h = h.replace('      <p class="asteapta" id="c-astept">',
                      FEREASTRA_HTML + '      <p class="asteapta" id="c-astept">', 1)
        h = h.replace('  // ---------- navigare ----------',
                      '  (function(){ var b=G("celInchide");\n'
                      '    if(b) b.addEventListener("click",function(){\n'
                      '      G("celFereastra").style.display="none"; }); })();\n\n'
                      '  // ---------- navigare ----------', 1)
        schimbari.append("fereastra cu istoricul unei celule")

    # ---- 3. simularea nu mai otraveste citirile adevarate
    #
    # "V.putere" are prioritate absoluta asupra calculului din tensiune x curent,
    # si singurul loc care o scrie e simularea. La oprire nu se stergea nimic,
    # deci pagina credea la nesfarsit ca masina consuma constant cat consuma
    # ultima clipa de simulare. Urmarea, vazuta pe drumul din 7 septembrie:
    # bara de consum inghetata la aceeasi cifra la orice curent, recuperarea
    # exact zero, si autonomia luata razna, fiindcă media lunga se hranea din
    # valoarea inventata. Reprodus la banc: fara simulare 0,2 kWh recuperati,
    # dupa simulare 0,0.
    if "sterg urmele simularii" not in h:
        vechi = ('      clearInterval(simInterval); simInterval=null;\n'
                 '      PAS_SEG=0.4;\n')
        nou = ('      clearInterval(simInterval); simInterval=null;\n'
               '      PAS_SEG=0.4;\n'
               '      // sterg urmele simularii: altfel citirile adevarate de dupa\n'
               '      // sunt calculate din valorile inventate\n'
               '      delete V.putere; delete V.tensiune; delete V.curent;\n'
               '      delete V["010D"]; delete V["010C"]; delete V["0104"];\n'
               '      delete V["0105"]; delete V["010F"]; delete V["0142"];\n'
               '      delete V["012F"]; delete V.celule;\n')
        if vechi in h:
            h = h.replace(vechi, nou, 1)
            schimbari.append("simularea isi sterge urmele la oprire")
        else:
            print("ATENTIE: nu gasesc oprirea simularii — verifica manual")

    # ---- 4. mai puțin trafic pe radio
    #
    # Masurat pe drumul din 7 septembrie: 270 de comenzi pe minut, 4,5 pe
    # secunda, si 25% din ele numai ATSH — schimbari de modul, pura risipa.
    # Cand Android Auto merge fara fir, foloseste Wi-Fi Direct pe 5 GHz plus
    # Bluetooth, iar telefonul le trece pe aceeasi antena. La ritmul acela,
    # BLE-ul e infometat si adaptorul pare ca amuteste: 57 de comenzi AT fara
    # raspuns, la care raspunde adaptorul singur, nu masina.
    #
    # Rărim tot, in afara de tensiune si curent — acelea doua mișca bara de
    # consum si trebuie sa rămână iuti.
    if "rarit pentru radio" not in h:
        rariri = [
            ('{c:"010C",per:400',  '{c:"010C",per:1500'),   # turatia, nu se citeste din mers
            ('{c:"010D",per:700',  '{c:"010D",per:900'),    # viteza
            ('{c:"0104",per:2000', '{c:"0104",per:6000'),   # sarcina
            ('{c:"0105",per:6000', '{c:"0105",per:20000'),  # lichid
            ('{c:"010F",per:6000', '{c:"010F",per:20000'),  # admisie
            ('{c:"0142",per:9000', '{c:"0142",per:30000'),  # tensiunea de bord
            ('comanda:"22F250", formula:"AB1000",  per:6000',
             'comanda:"22F250", formula:"AB1000",  per:12000'),
            ('comanda:"22F251", formula:"AB1000",  per:6000',
             'comanda:"22F251", formula:"AB1000",  per:12000'),
            ('comanda:"22F252", formula:"A",       per:12000',
             'comanda:"22F252", formula:"A",       per:40000'),
            ('comanda:"22F253", formula:"A",       per:12000',
             'comanda:"22F253", formula:"A",       per:40000'),
        ]
        n = 0
        for vechi, nou in rariri:
            if vechi in h:
                h = h.replace(vechi, nou, 1); n += 1
        # Un sfert din trafic era ATSH, pura comutare de modul. Preferinta de
        # 0.4 nu ajuta: dupa citire parametrul nu mai e scadent, deci cel de pe
        # celalalt modul castiga oricum. Masurat la banc, ridicarea preferintei
        # la 1.6, 4 sau 8 nu schimba nimic — 29% ATSH la toate trei.
        #
        # Ce functioneaza e alta regula: nu comutam modulul pentru un parametru
        # abia scadent. Il lasam sa se coaca, si intre timp se aduna citirile de
        # pe modulul curent. La banc: 417 -> 245 comenzi pe minut, iar ATSH de
        # la 120 la 36. Bara de consum rămâne la 1,4 citiri pe secunda.
        if 'if((p.adresa||"7E0")===adr) scor+=0.4;' in h:
            h = h.replace('if((p.adresa||"7E0")===adr) scor+=0.4;',
                          'if((p.adresa||"7E0")===adr) scor+=1.6;\n'
                          '        // rarit pentru radio: nu schimbam modulul pentru un parametru\n'
                          '        // abia scadent — comutarea costa mai mult decat asteptarea\n'
                          '        else if(scor<2.5) continue;', 1)
            n += 1
        if n: schimbari.append(f"trafic radio rarit ({n} reglaje)")
        else: print("ATENTIE: nu am gasit ritmurile de interogare — verifica manual")

    # ---- 5. bucla poate fi batuta si din afara, cand telefonul adoarme
    #
    # Cand ecranul se stinge, Chromium incetineste ceasurile din JavaScript
    # pana aproape de oprire, iar bucla de interogare tace. Masurat pe drumul
    # din 7 septembrie: doua gauri, de 74 si de 34 de secunde, in care nu s-a
    # trimis nicio comanda. Waze nu pateste asta fiindca e scris nativ, fara
    # pagina web inauntru.
    #
    # Aici expunem functia buclei ca "window.__tic". Partea Kotlin, care nu e
    # incetinita fiindca serviciul de prim-plan ii tine procesul treaz, o bate
    # singura cand vede ca pagina a amutit. Nimic nu se muta si nu se
    # rescrie: aceeasi bucla, doar cu un al doilea ceas, de rezerva.
    if "window.__tic" not in h:
        vechi = "    bucla=setInterval(function(){\n      if(ocupat) return;"
        nou = ("    // expusa ca sa poata fi batuta si din Kotlin cand ecranul e stins\n"
               "    window.__tic=function(){\n      if(ocupat) return;")
        if vechi in h:
            h = h.replace(vechi, nou, 1)
            # inchidem functia si pornim intervalul pe ea
            vechi2 = "    },160);\n  }\n  function stopBucla()"
            if vechi2 in h:
                h = h.replace(vechi2,
                    "    };\n    bucla=setInterval(window.__tic,160);\n  }\n"
                    "  function stopBucla()", 1)
                schimbari.append("bucla poate fi batuta din afara (window.__tic)")
            else:
                print("ATENTIE: nu gasesc sfarsitul buclei — verifica manual")
        else:
            print("ATENTIE: nu gasesc inceputul buclei — verifica manual")

    return h, schimbari


def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(1)
    sursa = sys.argv[1]
    h = open(sursa, encoding="utf-8").read()
    orig = len(h)
    h, schimbari = petic(h)
    tinta = os.path.splitext(sursa)[0] + "_plus.html"
    open(tinta, "w", encoding="utf-8").write(h)
    print(f"{os.path.basename(sursa)} -> {os.path.basename(tinta)}")
    print(f"  {orig} -> {len(h)} octeti")
    for s in schimbari:
        print(f"  · {s}")


if __name__ == "__main__":
    main()
