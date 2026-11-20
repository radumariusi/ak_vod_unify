# AK VOD Unify

Hub VOD pentru **Android TV** (funcționează și pe telefon, cu touch): adună istoricul și progresul de vizionare din **Trakt**, **SIMKL** și **WeTrakr**, afișează catalogul din **TMDb** și te trimite, prin deep link, în aplicația în care vrei să vezi (Netflix, Nuvio, Stremio, DebridStream).

## Planul pe etape

| Etapă | Conținut | Stare |
|---|---|---|
| 1 | Fundație: proiect Kotlin + Compose, arhitectură modulară, Room, Hilt, CI, release manual, self-update | ✅ |
| 2 | Catalog TMDb: Acasă (hero + rânduri), detalii filme/seriale (sezoane, episoade), persoane, studiouri/rețele, căutare cu filtre, limbă RO/original, disponibilitate US/RO (JustWatch), cache Room | ✅ |
| 3 | Trakt (autentificare device code, istoric, progres) | ⏳ |
| 4 | „Alege unde vezi” + deep links (Netflix, Nuvio, Stremio, DebridStream) | ⏳ |
| 5 | SIMKL + WeTrakr + reconciliere între surse | ⏳ |
| 6 | Publicare progres înapoi în surse + ecran diagnostic | ⏳ |
| 7 | Opțional: backend, Younify, import CSV; integrare AK VOD Calendar | ⏳ |

## Arhitectură

```
app/src/main/java/ro/ak/vodunify/
├── core/connector   Connector + Capability (HISTORY_READ, RESUME_READ, RESUME_WRITE, DEEP_LINK), ConnectorRegistry
├── core/db          Room: media, watch_events, resume_points, tmdb_cache, availability
├── core/tmdb        Client TMDb v3 (Bearer v4 sau api_key v3), DTO-uri, imagini, cache cu TTL + mod offline
├── core/catalog     CatalogRepository + modele de domeniu (titluri, persoane, studiouri, disponibilitate)
├── core/prefs       Setări catalog (DataStore): limbă RO/Original, piață RO/US
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
| `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` | Trakt | etapa 3 |
| `SIMKL_CLIENT_ID`, `SIMKL_CLIENT_SECRET` | SIMKL | etapa 5 |
| `WETRAKR_API_KEY` | WeTrakr | etapa 5 |

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

## Semnare și release

Release-ul se face **doar manual**: *Actions → Release APK → Run workflow*, cu versiunea (ex. `0.1.0`). Workflow-ul construiește APK-ul semnat `ak-vod-unify-<versiune>-vc<cod>.apk` și publică un GitHub Release `v<versiune>`. Aplicația verifică acest release din *Setări → Verifică actualizări*.

Secrete necesare pentru semnare (o singură dată):

| Nume | Valoare |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | keystore-ul codat base64 |
| `SIGNING_STORE_PASSWORD` | parola keystore-ului |
| `SIGNING_KEY_ALIAS` | alias-ul cheii (ex. `akvod`) |
| `SIGNING_KEY_PASSWORD` | parola cheii |

Generare keystore (o dată, păstrează fișierul și parolele în siguranță: fără aceeași cheie, actualizările nu se mai pot instala peste versiunea existentă):
```bash
keytool -genkeypair -v -keystore akvod-release.jks -alias akvod \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 akvod-release.jks   # pe macOS: base64 -i akvod-release.jks
```

## Build local

```bash
./gradlew assembleDebug        # APK debug: app/build/outputs/apk/debug/
./gradlew lintDebug testDebugUnitTest
```

CI rulează build + lint + teste la fiecare PR și publică APK-ul de debug ca artefact (Actions → rularea → Artifacts), util pentru test rapid pe telefon.
