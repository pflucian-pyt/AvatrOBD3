# Serverul de stare

Un singur fisier Python, numai biblioteca standard. Fara pachete, fara baza de
date. Datele stau in `date/`, langa el.

## Pornire pe VPS

    mkdir -p /opt/avatr && cd /opt/avatr
    # copiaza aici avatr_server.py
    export AVATR_CHEIE="o-parola-lunga-inventata-de-tine"
    python3 avatr_server.py 8077

Ca sa porneasca singur la fiecare repornire, pune in
`/etc/systemd/system/avatr.service`:

    [Unit]
    Description=Avatr stare
    After=network.target

    [Service]
    WorkingDirectory=/opt/avatr
    Environment=AVATR_CHEIE=parola-ta
    ExecStart=/usr/bin/python3 /opt/avatr/avatr_server.py 8077
    Restart=always

    [Install]
    WantedBy=multi-user.target

Apoi `systemctl enable --now avatr`.

## HTTPS

Nu lasa serverul direct pe internet fara TLS: cheia ar calatori in clar. Cu
nginx in fata:

    location /avatr/ {
        proxy_pass http://127.0.0.1:8077/;
        proxy_set_header Host $host;
    }

Adresa pe care o pui in aplicatie devine `https://domeniul-tau/avatr/stare`,
iar pagina de citit `https://domeniul-tau/avatr/?cheie=parola-ta`.

## Ce raspunde

| Cale | Ce face |
|---|---|
| `POST /stare` | primeste starea; cheia in antetul `X-Cheie` |
| `GET /stare` | ultima stare stiuta |
| `GET /istoric?cate=200` | ultimele inregistrari |
| `GET /?cheie=...` | pagina de citit pe telefon |
| `POST /grupare` | primeste fisierul de grupare a celulelor, o data pe cursa |
| `GET /grupare?cheie=...` | lista fisierelor de grupare |
| `GET /grupare?cheie=...&nume=...` | chiar fisierul, ca sa-l descarci |

Fisierele de grupare stau in `date/grupare/`, unul pe cursa, cu numele sursei
in fata. Nu se suprascriu si nu se taie singure: sunt cateva sute de kilobiti
pe zi de mers. Cand nu-ti mai trebuie, se sterg cu mana.

## In aplicatie

Dupa instalare apar doua pictograme: **Avatr Monitor** si **Server Avatr**. In a
doua pui adresa, cheia si un nume pentru aparat — de pilda "telefon" si
"unitatea din masina". Butonul *Trimite o proba* spune pe loc daca merge.

Aceeasi aplicatie se pune si pe unitatea din masina, cu alt nume. Amandoua
trimit, serverul pastreaza ultima sosita.

## Ultima suflare

Starea pleaca din minut in minut, si inca o data IMEDIAT cand se pierde
legatura cu OBD-ul sau masina se parcheaza. Fara trimiterea aceea, ultima stare
de pe server ar fi cea de acum cateva minute si n-ai sti unde s-a oprit masina.

Pagina scrie cat de veche e informatia si o coloreaza cand trece de o ora, ca
sa nu iei drept "acum" ceva de asta-noapte.
