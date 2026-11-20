# AK VOD Unify

Hub VOD pentru **Android TV** (funcționează și pe telefon, cu touch): adună istoricul și progresul de vizionare din **Trakt**, **SIMKL** și **WeTrakr**, afișează catalogul din **TMDb** și te trimite, prin deep link, în aplicația în care vrei să vezi (Netflix, Nuvio, Stremio, DebridStream).

## Planul pe etape

| Etapă | Conținut | Stare |
|---|---|---|
| 1 | Fundație: proiect Kotlin + Compose, arhitectură modulară, Room, Hilt, CI, release manual, self-update | ✅ |
| 2 | Catalog TMDb (rânduri reale, detalii, imagini) | ⏳ |
| 3 | Trakt (autentificare device code, istoric, progres) | ⏳ |
| 4 | „Alege unde vezi” + deep links (Netflix, Nuvio, Stremio, DebridStream) | ⏳ |
| 5 | SIMKL + WeTrakr + reconciliere între surse | ⏳ |
| 6 | Publicare progres înapoi în surse + ecran diagnostic | ⏳ |
| 7 | Opțional: backend, Younify, import CSV; integrare AK VOD Calendar | ⏳ |

## Arhitectură

```
app/src/main/java/ro/ak/vodunify/
├── core/connector   Connector + Capability (HISTORY_READ, RESUME_READ, RESUME_WRITE, DEEP_LINK), ConnectorRegistry
├── core/db          Room: media, watch_events, resume_points
├── core/update      Self-update din GitHub Releases (verificare, descărcare, PackageInstaller)
├── core/device      Detecție TV vs. telefon
├── di               Module Hilt (rețea, DB, conectori prin @IntoSet)
└── ui               Compose: home (hero + rânduri), settings, navigation, components, theme
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

Pașii exacți pentru obținerea fiecărei chei vin în etapa respectivă. Fără chei aplicația se compilează și rulează, doar conectorul respectiv apare ca „Lipsesc cheile API”.

Exemplu `local.properties`:
```properties
sdk.dir=/cale/catre/Android/Sdk
TMDB_API_KEY=...
TRAKT_CLIENT_ID=...
TRAKT_CLIENT_SECRET=...
```

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
