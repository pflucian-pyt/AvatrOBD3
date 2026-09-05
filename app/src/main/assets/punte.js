/*
 * punte.js — imitatie de Web Bluetooth peste Bluetooth-ul nativ Android.
 *
 * De ce exista fisierul acesta: WebView-ul de pe Android NU are Web Bluetooth.
 * Interfata Avatr Monitor atinge Bluetooth-ul in exact opt locuri, toate prin
 * "navigator.bluetooth". Aici reconstruim doar acele opt lucruri, sprijinite pe
 * Kotlin. Rezultatul: index.html ramane neschimbat, byte cu byte, deci tot ce a
 * fost testat in masina ramane valabil.
 *
 * Se incarca INAINTE de scriptul paginii. Nu se atinge de nimic altceva.
 */
(function () {
  "use strict";

  if (!window.Punte) {
    console.warn("punte.js: interfata Kotlin lipseste, las Web Bluetooth cum e");
    return;
  }

  var N = window.Punte;          // obiectul injectat din Kotlin
  var urmatorId = 1;
  var inAsteptare = {};          // idCerere -> {rezolva, respinge}

  function cerere(pornire) {
    return new Promise(function (rezolva, respinge) {
      var id = urmatorId++;
      inAsteptare[id] = { rezolva: rezolva, respinge: respinge };
      try {
        pornire(id);
      } catch (e) {
        delete inAsteptare[id];
        respinge(e);
      }
    });
  }

  // ---------- ce apeleaza Kotlin la noi ----------

  var punteInterna = {
    ok: function (id, text) {
      var c = inAsteptare[id];
      if (!c) return;
      delete inAsteptare[id];
      var d = null;
      if (text) { try { d = JSON.parse(text); } catch (e) { d = text; } }
      c.rezolva(d);
    },
    eroare: function (id, mesaj) {
      var c = inAsteptare[id];
      if (!c) return;
      delete inAsteptare[id];
      c.respinge(new Error(mesaj || "eroare necunoscuta"));
    },
    notificare: function (uuid, base64) {
      var c = caracteristici[uuid];
      if (!c) return;
      c._trimite(base64);
    },
    deconectat: function () {
      conectat = false;
      if (dispozitiv) dispozitiv._emite("gattserverdisconnected");
    }
  };
  window.__punte = punteInterna;

  // ---------- ascultatori de evenimente, minim ----------

  function CuEvenimente() { this._asc = {}; }
  CuEvenimente.prototype.addEventListener = function (nume, fn) {
    (this._asc[nume] = this._asc[nume] || []).push(fn);
  };
  CuEvenimente.prototype.removeEventListener = function (nume, fn) {
    var l = this._asc[nume]; if (!l) return;
    var i = l.indexOf(fn); if (i >= 0) l.splice(i, 1);
  };
  CuEvenimente.prototype._emite = function (nume, ev) {
    var l = this._asc[nume]; if (!l) return;
    ev = ev || {};
    ev.type = nume;
    if (!ev.target) ev.target = this;
    for (var i = 0; i < l.length; i++) {
      try { l[i].call(this, ev); } catch (e) { console.error(e); }
    }
  };

  // ---------- caracteristica ----------

  var caracteristici = {};   // uuid -> Caracteristica
  var conectat = false;
  var dispozitiv = null;
  var serviciiCerute = [];   // ce a cerut pagina prin optionalServices/filters

  // Servicii pe care Web Bluetooth nu le arata NICIODATA unei pagini, oricat ar
  // cere: accesul generic si atributul generic. Chrome le tine ascunse prin
  // lista lui de interdictii. Le scoatem si noi mereu, nu doar cand stim
  // filtrul paginii — altfel, pe calea de reconectare automata (unde
  // requestDevice nu se mai apeleaza, deci filtrul e gol) serviciul 1800 ar
  // reveni in fata cu "Device Name" scriibila, si comenzile OBD ar pleca iar in gol.
  var interzise = [
    "00001800-0000-1000-8000-00805f9b34fb",
    "00001801-0000-1000-8000-00805f9b34fb"
  ];

  function base64Bytes(s) {
    var brut = atob(s);
    var b = new Uint8Array(brut.length);
    for (var i = 0; i < brut.length; i++) b[i] = brut.charCodeAt(i);
    return b;
  }

  function bytesBase64(u8) {
    var s = "";
    for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
    return btoa(s);
  }

  function Caracteristica(d) {
    CuEvenimente.call(this);
    this.uuid = d.uuid;
    this.properties = {
      read: !!d.read,
      write: !!d.write,
      writeWithoutResponse: !!d.writeNoResp,
      notify: !!d.notify,
      indicate: !!d.indicate
    };
    this.value = null;
    caracteristici[this.uuid] = this;
  }
  Caracteristica.prototype = Object.create(CuEvenimente.prototype);

  Caracteristica.prototype._trimite = function (base64) {
    var b = base64Bytes(base64);
    // pagina citeste ev.target.value ca DataView: .byteLength si .getUint8()
    this.value = new DataView(b.buffer);
    this._emite("characteristicvaluechanged", { target: this });
  };

  Caracteristica.prototype.startNotifications = function () {
    var self = this;
    return cerere(function (id) { N.porneste_notificari(id, self.uuid); })
      .then(function () { return self; });
  };

  Caracteristica.prototype.stopNotifications = function () {
    var self = this;
    return cerere(function (id) { N.opreste_notificari(id, self.uuid); })
      .then(function () { return self; });
  };

  function scrieBytes(self, date, cuRaspuns) {
    var u8 = date instanceof Uint8Array ? date
           : date && date.buffer ? new Uint8Array(date.buffer)
           : new Uint8Array(date);
    var b64 = bytesBase64(u8);
    return cerere(function (id) { N.scrie(id, self.uuid, b64, cuRaspuns); });
  }

  Caracteristica.prototype.writeValue = function (d) { return scrieBytes(this, d, true); };
  Caracteristica.prototype.writeValueWithResponse = function (d) { return scrieBytes(this, d, true); };
  Caracteristica.prototype.writeValueWithoutResponse = function (d) { return scrieBytes(this, d, false); };

  // ---------- serviciu ----------

  function Serviciu(d) {
    this.uuid = d.uuid;
    this.isPrimary = true;
    this._c = (d.caracteristici || []).map(function (x) { return new Caracteristica(x); });
  }
  Serviciu.prototype.getCharacteristics = function () {
    var c = this._c;
    return Promise.resolve(c);
  };
  Serviciu.prototype.getCharacteristic = function (uuid) {
    var g = caracteristici[String(uuid).toLowerCase()];
    return g ? Promise.resolve(g) : Promise.reject(new Error("caracteristica lipseste"));
  };

  // ---------- serverul GATT ----------

  function Server(disp) {
    this.device = disp;
    this._servicii = null;
  }
  Object.defineProperty(Server.prototype, "connected", {
    get: function () { return conectat; }
  });
  Server.prototype.connect = function () {
    var self = this;
    var adresa = self.device.id || "";
    return cerere(function (id) { N.conecteaza(id, adresa); }).then(function () {
      conectat = true;
      // dispozitivul activ poate veni din getDevices, nu doar din requestDevice
      dispozitiv = self.device;
      self._servicii = null;
      caracteristici = {};
      return self;
    });
  };
  Server.prototype.disconnect = function () {
    conectat = false;
    N.deconecteaza();
  };
  Server.prototype.getPrimaryServices = function () {
    var self = this;
    if (self._servicii) return Promise.resolve(self._servicii);
    return cerere(function (id) { N.descopera_servicii(id); }).then(function (lista) {
      var tot = lista || [];
      tot.forEach(function (s) { console.log("serviciu vazut: " + s.uuid); });

      // Aici era greseala care facea aplicatia sa se conecteze si sa nu citeasca
      // nimic. Web Bluetooth nu arata paginii decat serviciile cerute prin
      // "optionalServices". Android insa raporteaza tot, inclusiv serviciile
      // standard 1800, 1801 si 180a, iar 1800 vine de obicei primul si are
      // caracteristica "Device Name", care pe multe adaptoare se poate scrie.
      // Pagina lua prima caracteristica scriibila din lista, deci comenzile OBD
      // plecau spre numele dispozitivului: scrise cu succes, fara nici un
      // raspuns. Filtram exact cum ar filtra Chrome.
      var pastrate = tot.filter(function (s) {
        var scos = interzise.indexOf(s.uuid) >= 0;
        if (scos) console.log("sar peste serviciul standard " + s.uuid);
        return !scos;
      });
      tot = pastrate;

      if (serviciiCerute.length) {
        var doarCerute = tot.filter(function (s) {
          return serviciiCerute.indexOf(s.uuid) >= 0;
        });
        if (doarCerute.length) {
          pastrate = doarCerute;
          console.log("pastrez " + doarCerute.length + " din " + tot.length +
                      " servicii, dupa filtrul paginii");
        } else {
          // niciunul dintre cele cerute nu exista: mai bine tot decat nimic,
          // dar spunem limpede, ca sa se vada in jurnal
          console.warn("niciun serviciu cerut nu a fost gasit; las lista intreaga");
        }
      }

      caracteristici = {};
      self._servicii = pastrate.map(function (s) { return new Serviciu(s); });
      return self._servicii;
    });
  };
  Server.prototype.getPrimaryService = function (uuid) {
    return this.getPrimaryServices().then(function (sv) {
      var t = String(uuid).toLowerCase();
      for (var i = 0; i < sv.length; i++) if (sv[i].uuid === t) return sv[i];
      throw new Error("serviciul " + uuid + " lipseste");
    });
  };

  // ---------- dispozitivul ----------

  function Dispozitiv(d) {
    CuEvenimente.call(this);
    this.name = d.nume || null;
    this.id = d.id || null;
    this.gatt = new Server(this);
  }
  Dispozitiv.prototype = Object.create(CuEvenimente.prototype);

  // ---------- navigator.bluetooth ----------

  navigator.bluetooth = {
    getAvailability: function () { return Promise.resolve(true); },
    requestDevice: function (opt) {
      // Alegerea dispozitivului o face omul, dintr-o lista afisata de Kotlin,
      // deci "acceptAllDevices" si "filters" nu ne spun nimic acolo. Dar lista
      // de servicii cerute conteaza mult mai tarziu, la descoperire: pagina se
      // sprijina pe ea ca sa nu vada serviciile standard ale dispozitivului.
      serviciiCerute = [];
      var adauga = function (u) {
        if (typeof u === "string") serviciiCerute.push(u.toLowerCase());
      };
      if (opt) {
        (opt.optionalServices || []).forEach(adauga);
        (opt.filters || []).forEach(function (f) { (f.services || []).forEach(adauga); });
      }
      if (serviciiCerute.length)
        console.log("servicii cerute de pagina: " + serviciiCerute.join(", "));

      return cerere(function (id) { N.cere_dispozitiv(id); }).then(function (d) {
        conectat = false;
        caracteristici = {};
        dispozitiv = new Dispozitiv(d);
        return dispozitiv;
      });
    },
    getDevices: function () {
      // Pagina se foloseste de asta ca sa se relege singura la pornire, fara
      // buton. In Chrome lista vine din dispozitivele carora li s-a dat voie
      // odata; aici vine din cele imperecheate in telefon plus ultimul folosit.
      if (!N.dispozitive_cunoscute) {
        return Promise.resolve(dispozitiv ? [dispozitiv] : []);
      }
      return cerere(function (id) { N.dispozitive_cunoscute(id); })
        .then(function (lista) {
          return (lista || []).map(function (d) { return new Dispozitiv(d); });
        })
        .catch(function () { return dispozitiv ? [dispozitiv] : []; });
    },
    addEventListener: function () {},
    removeEventListener: function () {}
  };

  // ---------- descarcarea fisierelor ----------
  //
  // Pagina exporta sesiunea asa: face un blob, creeaza o legatura invizibila cu
  // atributul "download" si o apasa. Intr-un browser iese un fisier in
  // Descarcari. In WebView nu se intampla nimic, si nici o eroare nu apare —
  // exact felul de defect care se observa abia cand ai nevoie de date.
  //
  // Prindem apasarea, citim blobul si dam octetii la Kotlin, care scrie
  // fisierul adevarat. Pagina nu se schimba cu nimic.

  if (window.Fisiere) {
    var apasareOriginala = HTMLAnchorElement.prototype.click;

    HTMLAnchorElement.prototype.click = function () {
      var nume = this.getAttribute("download");
      var adresa = this.getAttribute("href") || "";
      var alBlobului = adresa.indexOf("blob:") === 0 || adresa.indexOf("data:") === 0;

      if (!nume || !alBlobului) {
        return apasareOriginala.apply(this, arguments);
      }

      fetch(adresa)
        .then(function (r) { return r.blob(); })
        .then(function (b) {
          return new Promise(function (rezolva, respinge) {
            var c = new FileReader();
            c.onload = function () {
              // rezultatul e "data:tip;base64,XXXX" — luam doar partea de dupa virgula
              var s = String(c.result);
              rezolva({ b64: s.slice(s.indexOf(",") + 1), tip: b.type || "" });
            };
            c.onerror = function () { respinge(new Error("nu pot citi blobul")); };
            c.readAsDataURL(b);
          });
        })
        .then(function (d) { window.Fisiere.salveaza(nume, d.b64, d.tip); })
        .catch(function (e) {
          console.error("descarcare esuata: " + e.message);
          // lasam browserul sa incerce oricum, ca sa nu pierdem exportul
          try { apasareOriginala.call(this); } catch (x) {}
        }.bind(this));
    };
  }

  console.log("punte.js: Web Bluetooth imitat peste Bluetooth-ul Android");
})();
