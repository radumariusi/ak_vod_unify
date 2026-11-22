# AK VOD Unify

Hub VOD pentru **Android TV** (funcționează și pe telefon, cu touch): adună istoricul și progresul de vizionare din **Trakt**, **SIMKL** și **WeTrakr**, afișează catalogul din **TMDb** și te trimite, prin deep link, în aplicația în care vrei să vezi (Netflix, Nuvio, Stremio, DebridStream).

> **Noiembrie 2026:** baza de date locală e sursa de adevăr. „Continuă vizionarea” și istoricul vin din **AK Scrobbler**; Trakt și SIMKL sunt **ascunși** (codul rămâne, comutator în `core/connector/ExternalTrackers.kt`). Interfața folosește identitatea vizuală **AK VOD Hub** (fundal bleumarin, accent roșu, focus albastru-deschis, fundal panoramic în Acasă).

## Planul pe etape

| Etapă | Conținut | Stare |
|---|---|---|
| 1 | Fundație: proiect Kotlin + Compose, arhitectură modulară, Room, Hilt, CI, release manual, self-update | ✅ |
| 2 | Catalog TMDb: Acasă (hero + rânduri), detalii filme/seriale (sezoane, episoade), persoane, studiouri/rețele, căutare cu filtre, limbă RO/original, disponibilitate US/RO (JustWatch), cache Room | ✅ |
| 3 | Trakt: login cu cod (device code + PKCE), istoric, „Continuă vizionarea”, „Următorul episod”, „De văzut”, marcare văzut | ✅ |
| 4 | „Alege unde vezi” + deep links (Netflix, Nuvio, Stremio, DebridStream) | ✅ |
| 5 | SIMKL + reconciliere între surse + Bibliotecă (WeTrakr pus deoparte până la API-ul public) | ✅ |
| 6 | Publicare progres înapoi în surse (profiluri: automat / la cerere / doar vizualizare) + Diagnostic rute | ✅ |
| 7 | Opțional: backend, Younify, import CSV | amânat |
| 8 | Integrare AK VOD Calendar: tab Calendar (seriale + filme), notificări, canalul „Azi apare” | ✅ |
| 9 | Istoric direct din servicii: **AK Scrobbler** (ce redă TV-ul, din orice aplicație), apoi login în servicii (AK Import) și import CSV | ✅ (A Scrobbler, B AK Import RO/US, C CSV) |

## Arhitectură

```
app/src/main/java/ro/ak/vodunify/
├── core/connector   Connector, DeviceAuthConnector + Capability (istoric, progres, watchlist, următorul episod, deep link), ConnectorRegistry
├── core/trakt       Client Trakt API v2 (device code + PKCE, refresh token), DTO-uri, mapări, TraktConnector
├── core/imports    Istoric importat (AK Import RO/US, CSV Netflix): coadă pe disc, potrivire TMDb în fundal, scriere ca sursă proprie
├── core/scrobble   Primește ce redă TV-ul de la AK Scrobbler (ContentProvider protejat), recunoaște titlul (TitleParser) și îl găsește în TMDb (ScrobbleMatcher)
├── core/publish    Publicare progres: profiluri, reguli (PublishPlanner), scriere + citire înapoi, Diagnostic rute
├── core/tracking    TrackingRepository: sincronizare din toți trackerii conectați în Room + acțiuni (văzut, De văzut)
├── core/db          Room: media, watch_events, resume_points, watchlist, up_next, watched_titles, sync_state, tmdb_cache, availability
├── core/tmdb        Client TMDb v3 (Bearer v4 sau api_key v3), DTO-uri, imagini, cache cu TTL + mod offline
├── core/catalog     CatalogRepository + modele de domeniu (titluri, persoane, studiouri, disponibilitate)
├── core/prefs       Setări catalog (DataStore): limbă RO/Original, piață RO/US
├── core/calendar   Calendarul din AK VOD Calendar: motor pur (engine, model, time, quality, filter, anticipated, omdb, tmdb peste TmdbClient) + android (setări, notificări, canal TV, WorkManager)
├── core/update      Self-update din GitHub Releases (verificare, descărcare, PackageInstaller)
├── core/device      Detecție TV vs. telefon
├── di               Module Hilt (rețea, DB, conectori prin @IntoSet)
└── ui               Compose: home, details, person, studio, search, settings, navigation, components, theme
```

- Un singur APK pentru TV și telefon: focus vizibil pentru D-pad, tap/scroll pentru touch, layout adaptiv (`LayoutSpec`).
- Fiecare sursă e un `Connector` care își declară capabilitățile. Un conector nou = o clasă + un `@Provides @IntoSet` în `di/ConnectorModule.kt`.
- Funcționalitățile noi (ex. Calendar) se adaugă ca destinație în `ui/navigation/Destinations.kt` și pachet propriu în `ui/`.

## Chei API (secrete)

Nu se commit-uiesc niciodată. Local se pun în `local.properties` (ignorat de git); pe GitHub ca **Settings → Secrets and variables → Actions → New repository secret**.

| Nume | La ce folosește | Necesar din |
|---|---|---|
| `TMDB_API_KEY` | Catalog TMDb | etapa 2 |
| `TRAKT_CLIENT_ID` (`TRAKT_CLIENT_SECRET` doar pentru aplicații Trakt vechi, opțional) | Trakt | etapa 3 |
| `SIMKL_CLIENT_ID` | SIMKL (aplicație OAuth 2.0, tip „TV, devices & command line”, fără secret) | etapa 5 |
| `OMDB_API_KEY` (opțional) | Note IMDb (Calendar, postere, episoade), prin OMDb; se poate pune și din aplicație (Setări → Calendar) | etapa 8 |
| — | WeTrakr: nu are încă API public; conectorul (fără cheie) e în `core/wetrakr`, neactivat | — |

Pașii exacți pentru obținerea fiecărei chei vin în etapa respectivă.

### TMDb (etapa 2)

1. Cont gratuit pe [themoviedb.org](https://www.themoviedb.org/signup), confirmă emailul.
2. [Settings → API](https://www.themoviedb.org/settings/api) → *Create* → tip *Developer*, completează formularul.
3. Copiază **API Read Access Token** (începe cu `eyJ…`). Merge și „API Key” (32 caractere); aplicația detectează automat tipul.
4. GitHub → Settings → Secrets and variables → Actions → `TMDB_API_KEY` = tokenul.

Fără cheie, ecranele de catalog afișează pașii de mai sus în loc de conținut (fără crash). Fără chei aplicația se compilează și rulează, doar conectorul respectiv apare ca „Lipsesc cheile API”.

Exemplu `local.properties`:
```properties
sdk.dir=/cale/catre/Android/Sdk
TMDB_API_KEY=...
TRAKT_CLIENT_ID=...
TRAKT_CLIENT_SECRET=...
```

## Catalog (etapa 2)

- **Acasă**: hero rotativ din „În tendințe”, rânduri Filme/Seriale populare, Filme apreciate și câte un rând pentru primele 5 servicii de streaming din piața aleasă („Pe Netflix (RO)” etc.). Rândurile „Continuă vizionarea” și „Următorul episod” sunt rezervate pentru Trakt (etapa 3).
- **Detalii**: film sau serial, cu sezoane și episoade, distribuție, echipă, studiouri/rețele, recomandări, trailer.
- **Unde vezi**: TMDb Watch Providers pentru RO și US (abonament, gratuit, închiriere, cumpărare), cu atribuire JustWatch.
- **Căutare**: titlu sau persoană, filtre tip/gen/an; fără text, filtrele răsfoiesc catalogul (`/discover`).
- **Setări → Catalog**: limbă (Română / Original), piață (RO / US), golire cache.
- Cache: răspunsurile TMDb se salvează în Room cu cheie = cale + limbă + piață (6 h listele, 7 zile listele de genuri/servicii); fără internet se folosesc datele salvate. Titlurile deschise se salvează în `media`, iar disponibilitatea în `availability` (pe titlu și piață).

## Trakt (etapa 3)

Configurare (o singură dată): pe [trakt.tv/oauth/applications](https://trakt.tv/oauth/applications) → *New Application*, Redirect uri `urn:ietf:wg:oauth:2.0:oob`, apoi secretul GitHub `TRAKT_CLIENT_ID` = Client ID. Aplicațiile Trakt noi nu mai au Client Secret (PKCE) și nici nu e nevoie de el.

- **Conectare**: *Setări → Conturi → Trakt → Conectează*. Apare un cod (și un QR pe TV); îl introduci pe trakt.tv/activate de pe orice dispozitiv. Tokenurile stau în spațiul privat al aplicației, fără backup, și se reîmprospătează automat.
- **Acasă**: „Continuă vizionarea” (din Trakt playback, separat de istoric; dacă poziția lipsește, apare „Poziție necunoscută”, nu 0%), „Următorul episod” (progresul pe seriale), „De văzut” (watchlist).
- **Detalii**: „+ De văzut”, „Marchează văzut” pentru filme, „Văzut / Nevăzut” pe fiecare episod; progresul pe serial.
- Sincronizarea rulează la deschiderea aplicației (dacă au trecut 15 minute) și din *Setări → Sincronizează acum*. Istoricul se descarcă incremental.

## Alege unde vezi (etapa 4)

Din pagina unui film, a unui serial sau de pe orice episod se deschide dialogul **Alege unde vezi**:

- **Servicii oficiale** din piața conturilor tale (Setări → Redare): Netflix, Prime Video, Disney+, HBO Max, plus celelalte servicii raportate de TMDb/JustWatch (fără integrare încă: deschid pagina cu oferte). Dacă aplicația nu e instalată, se deschide magazinul.
- **Playere instalate**: Nuvio, Stremio, DebridStream (detectate după pachet sau, pentru build-uri neoficiale, după numele din launcher).
- Ținta se alege de la cea mai precisă la cea mai slabă și se folosește doar dacă aplicația instalată o acceptă (verificat cu PackageManager): **episod** (Stremio) → **pagina titlului/serialului** (Stremio, Nuvio) → **căutare** (Netflix, Prime Video, Disney+, HBO Max nu au id public în TMDb) → **doar deschide aplicația**.
- Eticheta de reluare: Poziție sincronizată / În așteptare / Poziție estimată / Reluare manuală. Cu Trakt conectat, poziția e estimată din procentul dat de Trakt („În așteptare” cât rulează sincronizarea); fără tracker e „Reluare manuală”. La o poziție nesigură apare „Deschide acum · mm:ss” sau „Așteaptă sincronizarea”.
- **Setări → Redare**: piața conturilor (RO/US), aplicația preferată pentru fiecare serviciu (oficială sau un player) și lista aplicațiilor detectate. Dacă titlul e doar în cealaltă piață, dialogul afișează o avertizare.

## Publicare progres (etapa 6)

- **Setează minutul**: în „Alege unde vezi”, pe un film sau episod. Poziția se salvează local (sursa „Local”) și intră în reconciliere ca orice altă sursă: câștigă cea mai nouă.
- **Publică acum**: trimite poziția cea mai nouă (a ta sau din alt tracker) în destinațiile profilului activ, prin metoda documentată de pauză: Trakt `POST /scrobble/pause`, SIMKL `POST /scrobble/pause`. Nu se trimit `start`/`stop`, deci nu se pornesc sesiuni false și nu se marchează nimic ca văzut.
- **Publicat** apare doar după ce aplicația citește înapoi punctele de reluare din tracker și găsește poziția trimisă. Altfel: „Trimis, neconfirmat” (se reverifică la fiecare sincronizare) sau „Eroare”.
- Reguli: doar poziții cunoscute între 1% și 80% (lipsă ≠ 0%; peste 80% Trakt/SIMKL le-ar putea trece ca văzute, iar „văzut” se marchează doar cu „Marchează văzut”); nu se scrie înapoi în sursa poziției și nici peste o poziție mai nouă; aceeași poziție nu se retrimite (idempotent); fiecare scriere e înregistrată ca scriere proprie, ca ecoul ei să nu devină o poziție nouă.
- **Setări → Publicare progres**: 3 profiluri (Principal, Profil 2, Profil 3), fiecare cu modul lui (**Automat**: după „Setează minutul” și după fiecare sincronizare, maxim 10 scrieri/rulare, doar poziții din ultimele 14 zile; **La cerere**; **Doar vizualizare**) și destinațiile lui (Trakt, SIMKL).
- **Diagnostic rute** (din Setări): pe fiecare film/episod început, poziția în Local / Trakt / SIMKL cu ora actualizării, care câștigă (★), starea publicării pe fiecare destinație (cu progresul citit înapoi) și ce primește fiecare player: Nuvio citește reluarea din Trakt, Stremio și serviciile oficiale au progres propriu.

## AK Scrobbler (etapa 9A)

Aplicație separată, foarte mică (`scrobbler/`, pachet `ro.ak.vodscrobbler`), fără permisiunea de internet. Se înregistrează ca „ascultător de notificări”: așa Android îi dă voie să citească **sesiunile media** active (titlu, poziție, durată, redare/pauză) ale oricărei aplicații — Netflix, Prime Video, SkyShowtime, HBO Max, Disney+, Peacock, Stremio etc. Notificările în sine nu le citește. Odată ce are acces, Android o ține pornită singur și o repornește după restart (plus `BOOT_COMPLETED` ca plasă de siguranță); nu are nevoie de un serviciu în prim-plan.

- **Când trimite**: la pauză, la oprire / închiderea aplicației / începutul altui titlu și o dată pe minut în timpul redării. Ignoră primele 30 de secunde. Dacă AK VOD Unify nu răspunde, evenimentele stau într-o coadă și se retrimit.
- **Cum ajung în AK VOD Unify**: printr-un `ContentProvider` (`<pachet>.scrobble`) protejat de o permisiune `signature`, deci doar aplicațiile semnate cu aceeași cheie pot scrie. Ambele APK-uri trebuie să vină din același release (sau din același build CI pentru debug).
- **Recunoașterea titlului**: `TitleParser` caută „S1:E3”, „S01E03”, „Sezonul 1 Episodul 3”, „1x03”, „Episodul 3” în oricare câmp și încearcă toate combinațiile serial / nume episod / film; `ScrobbleMatcher` acceptă doar **potriviri exacte** de nume în TMDb (română sau engleză, ori titlul original), ca progresul să nu ajungă niciodată pe alt titlu. YouTube, muzica și orice sub 10 minute sunt ignorate.
- **Ce se salvează**: poziția exactă, ca sursă „Local” (la fel ca „Setează minutul”), deci apare în „Continuă vizionarea” și „Alege unde vezi”; la pauză/oprire se publică în Trakt/SIMKL după profilul activ. Peste 90% la pauză/oprire = văzut, doar local.
- **Setări → AK Scrobbler**: pornit/oprit și jurnalul cu ce s-a primit (ce a citit, ce titlu a găsit, minutul, rezultatul). În AK Scrobbler, ecranul **„Ce văd acum”** arată exact câmpurile brute pe care le dă fiecare aplicație.
- **VPN cu tunel pe aplicații**: AK Scrobbler nu iese pe internet, deci nu se pune în niciun tunel. AK VOD Unify merge direct.

### Activare pe TV

1. Instalează din același release `ak-vod-unify-….apk` și `ak-scrobbler-….apk`.
2. Deschide **AK Scrobbler** → **Dă acces** → pornește „AK Scrobbler” în *Acces la notificări*.
3. Dacă televizorul nu are acest ecran (multe Google TV îl ascund), accesul se dă o singură dată prin ADB, de pe telefon:
   - pe TV: *Setări → Sistem → Despre* → apasă de 7 ori pe *Versiune build Android TV OS* → *Opțiuni dezvoltator* → pornește *Depanare USB* / *Depanare wireless*;
   - pe telefon, o aplicație ADB prin Wi-Fi (ex. „Bugjaeger” sau „Remote ADB Shell”) → conectare la IP-ul TV-ului (portul 5555, sau codul de asociere de la *Depanare wireless*);
   - comanda (o vezi și în AK Scrobbler): `adb shell cmd notification allow_listener ro.ak.vodscrobbler/ro.ak.vodscrobbler.MediaListenerService`
4. Opțional, pe TV-urile care închid aplicațiile (ex. TCL): **Permite bulina** (sau `adb shell appops set ro.ak.vodscrobbler SYSTEM_ALERT_WINDOW allow`) și scutirea de baterie (`adb shell dumpsys deviceidle whitelist +ro.ak.vodscrobbler`). Scrobblerul rulează și ca serviciu în prim-plan; bulina din dreapta sus arată starea: verde = activ, roșu = urmărește o redare, gri = fără acces.
5. Pornește ceva în Netflix, pune pauză și verifică în AK Scrobbler → „Ce văd acum” și în AK VOD Unify → Setări → AK Scrobbler.

## AK Import RO / US și CSV Netflix (etapa 9B + 9C)

Modulul `importer/` produce **două aplicații** cu același cod: **AK Import RO** (`ro.ak.vodimport.ro`) și **AK Import US** (`ro.ak.vodimport.us`). Te conectezi în contul serviciului într-un browser din aplicație (parola o introduci doar pe site-ul serviciului), apoi „Importă istoricul” citește istoricul cu sesiunea ta, ca o extensie de browser, și îl trimite în AK VOD Unify (`/history` pe același ContentProvider protejat).

- **VPN cu tunel pe aplicații**: pui **doar AK Import US** în tunelul US, o singură dată. AK Import RO, AK VOD Unify și AK Scrobbler merg direct. Fiecare AK Import îți arată de unde iese pe internet (țara IP-ului), ca să verifici tunelul.
- **Netflix**: tot istoricul profilului ales (API-ul intern al paginii *Activitate de vizionare*), cu **minutul** unde ai rămas: sub 90% devine punct de reluare, restul istoric.
- **Prime Video** (experimental): pagina *Istoric vizionări* (primevideo.com pe RO, amazon.com pe US); filmele, fără minut.
- **Disney+, HBO Max, SkyShowtime, Peacock**: site-urile nu au o pagină de istoric de citit; ce vezi de acum încolo prinde AK Scrobbler.
- **CSV Netflix** (*Setări → Istoric din servicii → Importă CSV Netflix*): `NetflixViewingHistory.csv` (Title, Date) sau `ViewingActivity.csv` din exportul de date (cu poziție și profiluri; alegi profilul).
- În AK VOD Unify istoricul importat e o sursă proprie (`netflix`, `primevideo`): apare în Bibliotecă și în „Continuă vizionarea”; potrivirea în TMDb se face în fundal (WorkManager), doar cu nume exacte. Reimportul nu face dubluri. Din Setări vezi câte s-au găsit / negăsit și poți șterge istoricul importat.

## Calendar (etapa 8)

Funcțiile din [AK VOD Calendar](https://github.com/radumariusi/ak_vod_calendar), mutate în tabul **Calendar**. Folosesc clientul TMDb al aplicației (aceeași cheie și același cache), conturile Trakt / SIMKL deja conectate și „Alege unde vezi”.

- **Seriale** și **Filme**, pe **Zi / Săptămână / Lună**, cu ◀ ▶ și „Azi”. Orele sunt în ora României și sunt estimate („≈”): originalele de streaming apar la 00:00 ora Pacificului, serialele TV la 21:00 în țara lor. Pe o aplicație care doar licențiază serialul, episodul apare mai târziu: a doua zi dimineața pe US, la 12:00 pe RO.
- **Filme**: Streaming, Digital (închiriere / cumpărare), Cinema SUA, Cinema România, fiecare cu buton de pornit/oprit.
- **Ce intră**: serialele și filmele trebuie să treacă pragurile de notă (IMDb dacă există cheie OMDb, altfel TMDb). Cele noi intră dacă sunt pe lista „mult așteptate” (cele mai populare premiere din TMDb). Categoriile ascunse (anime, turcești, reality etc.) nu apar. Ce urmărești apare mereu.
- **Ce urmărești**: „De văzut” și serialele în curs din Trakt / SIMKL, plus butonul „Urmăresc” din calendar. „Ascunde” scoate un titlu din calendar. Episoadele și filmele văzute apar cu ✓, iar „Marchează văzut” trimite în trackere.
- **Apăsare** pe un titlu deschide pagina lui. **Apăsare lungă** (sau OK ținut apăsat pe telecomandă) deschide „Alege unde vezi”.
- **Filtre**: „Doar aplicațiile mele” și „Arată și ce nu trece pragurile”, care arată estompat ce s-a filtrat și de ce.
- **Notificări**: pornești din *Setări → Calendar* fie „Doar ce urmăresc”, fie „Ce urmăresc + rezumatul zilei”, și alegi ora. Canalul **„Azi apare”** de pe ecranul principal Android TV se reface la 3 ore.
- **Setări → Calendar**: aplicațiile tale și țara fiecăreia (detectate automat la prima pornire), praguri, cheia OMDb, categorii, notificări, liste de urmărite / ascunse.

## Actualizări

*Setări → Aplicație*: „Verifică actualizări” pentru AK VOD Unify și „Verifică aplicațiile AK” pentru AK Scrobbler, AK Import RO și AK Import US. Acestea se instalează sau se actualizează din același release (AK VOD Unify descarcă APK-ul și sistemul cere confirmarea). Aplicațiile mici nu au propriul updater: AK Scrobbler nici nu are acces la internet.

## Semnare și release

Release-ul se face **doar manual**: *Actions → Release APK → Run workflow*, cu versiunea (ex. `0.1.0`). Workflow-ul construiește APK-urile semnate `ak-vod-unify-<versiune>-vc<cod>.apk` `ak-scrobbler-…`, `ak-import-ro-…` și `ak-import-us-<versiune>-vc<cod>.apk` și publică un GitHub Release `v<versiune>`. Aplicația verifică acest release din *Setări → Verifică actualizări*.

### Varianta A (recomandată, merge doar de pe telefon)

Ai nevoie de **un singur secret**: `SIGNING_STORE_PASSWORD`, o parolă inventată de tine (minim 12 caractere, recomandat 20+, fără spații). Keystore-ul e generat pe serverele GitHub și salvat în repo **doar criptat** (`signing/release.jks.enc`, AES-256). Repo-ul e public, așa că parola lungă e cea care protejează cheia.

1. În browser, pe telefon: github.com → repo-ul `ak_vod_unify` → meniul ⋯ / **Settings** (pe mobil poate fi nevoie de „Desktop site”).
2. **Secrets and variables → Actions → New repository secret**.
3. Name: `SIGNING_STORE_PASSWORD`, Secret: parola ta → **Add secret**. Salveaz-o și într-un manager de parole: fără ea nu mai poți semna actualizări.
4. **Actions → Generate signing key → Run workflow** (o singură dată). Workflow-ul refuză să suprascrie o cheie existentă.
5. De acum, release-urile se fac din **Actions → Release APK → Run workflow**.

Alias-ul cheii este `akvod`, iar parola cheii e aceeași cu `SIGNING_STORE_PASSWORD`.

### Varianta B (alternativă, cu keystore propriu)

Dacă ai deja un keystore, pune-l în secrete; are prioritate față de fișierul criptat:

| Nume | Valoare |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | keystore-ul codat base64 |
| `SIGNING_STORE_PASSWORD` | parola keystore-ului |
| `SIGNING_KEY_ALIAS` | alias-ul cheii (implicit `akvod`) |
| `SIGNING_KEY_PASSWORD` | parola cheii (implicit = parola keystore-ului) |

```bash
keytool -genkeypair -v -keystore akvod-release.jks -alias akvod \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 akvod-release.jks   # pe macOS: base64 -i akvod-release.jks
```

Indiferent de variantă: păstrează aceeași cheie pentru totdeauna. Cu altă cheie, actualizările nu se mai instalează peste versiunea existentă.

## Build local

```bash
./gradlew assembleDebug        # APK debug: app/build/outputs/apk/debug/
./gradlew lintDebug testDebugUnitTest
```

CI rulează build + lint + teste la fiecare PR și publică APK-ul de debug ca artefact (Actions → rularea → Artifacts), util pentru test rapid pe telefon.
