package ro.ak.vodimport

/**
 * Scripturile rulate în pagina serviciului, după ce te-ai conectat. Folosesc sesiunea ta din
 * browserul aplicației (cookie-urile), exact ca extensiile de browser pentru istoric, și trimit
 * rezultatul înapoi prin puntea `AK` (progress / done / fail).
 */
object Scripts {

    /** Test Netflix: notează ce primește pagina (fetch/XHR) cât se încarcă „Activitate de vizionare”. */
    val NETFLIX_CAPTURE_HOOK = """
        (function () {
          if (window.__akHook) return;
          window.__akHook = true;
          window.__akCap = [];
          var keep = function (url) { return /graphql|viewed|viewing|activity|shakti|pathEvaluator|bookmark/i.test(String(url || "")); };
          window.__akHist = [];
          // Cererile care au adus istoric: le repetăm noi, cu alt cursor, ca să luăm tot istoricul.
          window.__akHistReqs = [];
          var note = function (url, status, body, req) {
            try {
              var b = String(body || "");
              if (b.indexOf("ProfileViewingHistoryRecord") >= 0) {
                if (window.__akHist.length < (window.__akHistMax || 30)) window.__akHist.push(b);
                if (req && window.__akHistReqs.length < 5) window.__akHistReqs.push(req);
              }
              var i = b.search(/bookmark|watchedDate|viewedDate|dateStr|lastWatched|playbackPosition|progress/i);
              var keys = (b.match(/"(bookmark\w*|watched\w*|viewed\w*|date\w*|progress\w*|runtime|duration|title|seriesTitle|episodeTitle|lastModified\w*|timestamp)"/gi) || [])
                .filter(function (v, k, a) { return a.indexOf(v) === k; }).slice(0, 15).join("");
              window.__akCap.push({ u: String(url).replace(/^https:\/\/[^\/]+/, "").slice(0, 80), s: status, n: b.length, k: keys,
                x: i >= 0 ? b.slice(Math.max(0, i - 80), i + 160) : b.slice(0, 200) });
            } catch (e) {}
          };
          var of = window.fetch;
          if (of) {
            var headersOf = function (h) {
              var o = {};
              try {
                if (!h) return o;
                if (typeof h.forEach === "function" && !Array.isArray(h)) { h.forEach(function (v, k) { o[k] = v; }); return o; }
                if (Array.isArray(h)) { h.forEach(function (p) { o[p[0]] = p[1]; }); return o; }
                for (var k in h) o[k] = h[k];
              } catch (e) {}
              return o;
            };
            window.fetch = function (input, init) {
              var url = (input && input.url) || input;
              var req = null;
              if (keep(url)) {
                try {
                  var isReq = typeof Request !== "undefined" && input instanceof Request;
                  req = {
                    url: String(url),
                    method: (init && init.method) || (isReq ? input.method : "GET"),
                    headers: headersOf((init && init.headers) || (isReq ? input.headers : null)),
                    body: (init && typeof init.body === "string") ? init.body : null
                  };
                  if (isReq && !req.body && req.method !== "GET") input.clone().text().then(function (t) { req.body = t; });
                } catch (e) {}
              }
              return of.apply(this, arguments).then(function (r) {
                if (keep(url)) { try { r.clone().text().then(function (t) { note(url, r.status, t, req); }); } catch (e) {} }
                return r;
              });
            };
          }
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
          var osh = XMLHttpRequest.prototype.setRequestHeader;
          XMLHttpRequest.prototype.open = function (m, u) { this.__akU = u; this.__akM = m; this.__akH = {}; return oo.apply(this, arguments); };
          XMLHttpRequest.prototype.setRequestHeader = function (k, v) { try { this.__akH[k] = v; } catch (e) {} return osh.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function () {
            var x = this;
            var body = arguments[0];
            x.addEventListener("load", function () {
              if (keep(x.__akU)) note(x.__akU, x.status, x.responseText, { url: String(x.__akU), method: x.__akM || "GET", headers: x.__akH || {}, body: typeof body === "string" ? body : null, xhr: true });
            });
            return os.apply(this, arguments);
          };
        })();
    """.trimIndent()

    /**
     * Cititorul comun al înregistrărilor Netflix „Watched” (ProfileViewingHistoryRecord): ora exactă
     * (lastWatchedTimeMs), titlul, serialul / sezonul / episodul și, dacă le dă, minutul și durata.
     * Nu știm forma exactă a obiectului video, deci îl citim generic. Definește `akNfRecords()`.
     */
    private val NETFLIX_RECORDS = """
        function akNfRecords() {
          var items = [], seen = {}, info = { sample: "", recKeys: "" };
          var str = function (v) {
            if (typeof v === "string") return v;
            if (v && typeof v === "object") { if (typeof v.value === "string") return v.value; if (typeof v.text === "string") return v.text; }
            return "";
          };
          var num = function (o, keys) {
            for (var i = 0; i < keys.length; i++) { var n = Number(o && o[keys[i]]); if (n > 0 && n < 1000) return n; }
            return 0;
          };
          var find = function (o, re, depth) {
            if (!o || typeof o !== "object" || depth > 4) return null;
            for (var k in o) {
              var c = o[k];
              if (!c || typeof c !== "object") continue;
              if (re.test(String(c.__typename || "")) || (!c.__typename && re.test(k))) return c;
              var d = find(c, re, depth + 1);
              if (d) return d;
            }
            return null;
          };
          var pick = function (objs, re) {
            for (var i = 0; i < objs.length; i++) {
              var o = objs[i];
              if (!o || typeof o !== "object") continue;
              for (var k in o) {
                var v = o[k];
                if (v && typeof v === "object" && typeof v.value === "number") v = v.value;
                if (re.test(k) && (typeof v === "number" || (typeof v === "string" && /^\d+(\.\d+)?$/.test(v)))) return Number(v);
              }
            }
            return null;
          };
          // Secunde; valorile foarte mari sunt milisecunde.
          var secs = function (n) { if (!(n > 0)) return null; return n > 200000 ? Math.round(n / 1000) : Math.round(n); };
          var add = function (rec) {
            var at = Number(rec.lastWatchedTimeMs || rec.lastWatchedTime || rec.watchedTimeMs || 0);
            var v = rec.video || find(rec, /video|episode|movie/i, 0) || {};
            if (!info.sample) { info.sample = JSON.stringify(v).slice(0, 300); info.recKeys = Object.keys(rec).join(","); }
            var type = String(v.__typename || "");
            var name = str(v.title) || str(v.displayTitle) || str(v.name);
            var showObj = find(v, /^show$|parentshow|series/i, 0);
            var seasonObj = find(v, /^season$|parentseason/i, 0);
            var show = showObj ? (str(showObj.title) || str(showObj.displayTitle) || str(showObj.name)) : "";
            var sNum = seasonObj ? num(seasonObj, ["number", "seasonNumber", "seq", "seasonSeq"]) : 0;
            var sTitle = seasonObj ? (str(seasonObj.title) || str(seasonObj.shortName) || str(seasonObj.longName)) : "";
            var isEp = /episode/i.test(type) || !!show;
            var eNum = isEp ? num(v, ["number", "episodeNumber", "seq", "episodeSeq"]) : 0;
            var id = String(v.videoId || v.id || "");
            if (!at || !name) return;
            var key = at + "|" + id + "|" + name;
            if (seen[key]) return;
            seen[key] = 1;
            var bookmark = secs(pick([rec, v], /^(bookmark|bookmarkPosition|bookmarkSeconds|bookmarkPositionSeconds|playbackPosition|watchedSeconds)$/i));
            var duration = secs(pick([rec, v], /^(runtime|runtimeSeconds|runtimeSec|duration|durationSeconds|displayRuntime)$/i));
            if (!bookmark) {
              // Unele răspunsuri dau doar procentul văzut: îl trimitem ca poziție din 100.
              var pct = pick([rec], /percent|progress/i);
              if (pct != null && pct > 0 && pct <= 100) { bookmark = Math.round(pct <= 1 ? pct * 100 : pct); duration = 100; }
            }
            var season = sTitle || (sNum ? "Season " + sNum : "");
            items.push({
              series: isEp && show ? show : null,
              season: isEp && season ? season : null,
              episode: isEp && show ? name : null,
              title: isEp && show ? show + (season ? ": " + season : "") + ": " + name : name,
              date: at, watchedAt: at,
              bookmark: bookmark && duration ? bookmark : null, duration: bookmark && duration ? duration : null,
              seasonNumber: sNum || null, episodeNumber: eNum || null,
              id: id
            });
          };
          var walk = function (o, depth) {
            if (!o || typeof o !== "object" || (depth || 0) > 14) return;
            if (o.__typename === "ProfileViewingHistoryRecord") { add(o); return; }
            for (var k in o) walk(o[k], (depth || 0) + 1);
          };
          return { items: items, info: info, walk: walk };
        }
    """.trimIndent()

    /**
     * Netflix „Watched” (/settings/viewed): din răspunsurile prinse de [NETFLIX_CAPTURE_HOOK] scoate
     * vizionările recente (pentru potrivirea vizionărilor prinse de AK Scrobbler). Sincron; JSON {items, diag}.
     */
    val NETFLIX_HISTORY_COLLECT = """
        (function () {
          $NETFLIX_RECORDS
          var R = akNfRecords();
          var bodies = window.__akHist || [], bad = 0;
          for (var i = 0; i < bodies.length; i++) {
            try { R.walk(JSON.parse(bodies[i]), 0); } catch (e) { bad++; }
          }
          R.items.sort(function (a, b) { return b.watchedAt - a.watchedAt; });
          var diag = "raspunsuri " + bodies.length + (bad ? " (" + bad + " ilizibile)" : "") + " · inregistrari " + R.items.length +
            (R.items.length ? "" : " · video: " + R.info.sample);
          return JSON.stringify({ items: R.items, diag: diag });
        })()
    """.trimIndent()

    /**
     * Netflix, TOT istoricul profilului ales (importul inițial). Pagina „Watched” cere istoricul
     * pagină cu pagină (graphql, cu cursor); [NETFLIX_CAPTURE_HOOK] a reținut prima cerere, iar noi o
     * repetăm cu cursorul paginii următoare până la capăt. Plasă de siguranță: apăsăm „Show more” în
     * pagină cât timp apare. Trimite prin puntea `AK` (progress / done / fail).
     */
    val NETFLIX = """
        (async function () {
          try {
            $NETFLIX_RECORDS
            var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
            AK.progress(0);
            window.__akHistMax = 100000;
            var R = akNfRecords();
            var readBodies = function () {
              var bodies = window.__akHist || [];
              for (var i = 0; i < bodies.length; i++) { try { R.walk(JSON.parse(bodies[i]), 0); } catch (e) {} }
            };
            for (var w = 0; w < 30 && !R.items.length; w++) { readBodies(); if (!R.items.length) await sleep(1000); }
            if (!R.items.length) {
              AK.fail("Nu văd istoricul în pagina „Watched”. Alege profilul (ex. Main), apoi apasă din nou „Importă istoricul”. " +
                "(răspunsuri prinse: " + (window.__akHist || []).length + ")");
              return;
            }
            AK.progress(R.items.length);

            // Cursorul paginii următoare: pageInfo.endCursor, altfel ultimul „cursor”, altfel base64("<ms>:<videoId>").
            var cursorOf = function (j) {
              var res = { cursor: null, hasNext: null, last: null };
              var walk = function (o, d) {
                if (!o || typeof o !== "object" || d > 14) return;
                if (typeof o.endCursor === "string") { res.cursor = o.endCursor; if (typeof o.hasNextPage === "boolean") res.hasNext = o.hasNextPage; }
                if (typeof o.cursor === "string") res.last = o.cursor;
                for (var k in o) walk(o[k], d + 1);
              };
              walk(j, 0);
              return res;
            };
            var oldest = function () {
              var o = null;
              for (var i = 0; i < R.items.length; i++) if (!o || R.items[i].watchedAt < o.watchedAt) o = R.items[i];
              return o;
            };
            var CURSOR_KEY = /^(after|cursor|endCursor|pageCursor|nextCursor|startCursor)$/i;
            var isCursorValue = function (v) {
              if (typeof v !== "string") return false;
              try { return /^\d{10,}:\w+$/.test(atob(v)); } catch (e) { return false; }
            };
            // Căile (lista de chei) din variabile unde stă cursorul.
            var cursorPaths = function (vars) {
              var paths = [];
              var walk = function (o, path, d) {
                if (!o || typeof o !== "object" || d > 6) return;
                for (var k in o) {
                  var v = o[k];
                  if ((CURSOR_KEY.test(k) && (v === null || typeof v === "string")) || isCursorValue(v)) paths.push(path.concat([k]));
                  else if (v && typeof v === "object") walk(v, path.concat([k]), d + 1);
                }
              };
              walk(vars, [], 0);
              if (!paths.length) { paths.push(["after"]); paths.push(["cursor"]); }
              return paths;
            };
            var setPath = function (o, path, value) {
              for (var i = 0; i < path.length - 1; i++) { if (!o[path[i]] || typeof o[path[i]] !== "object") o[path[i]] = {}; o = o[path[i]]; }
              o[path[path.length - 1]] = value;
            };

            var replay = async function (req) {
              var parsed = null;
              try { parsed = req.body ? JSON.parse(req.body) : null; } catch (e) {}
              var u = new URL(req.url, location.href);
              var urlVars = null;
              try { urlVars = JSON.parse(u.searchParams.get("variables") || "null"); } catch (e) {}
              var target = parsed ? (Array.isArray(parsed) ? parsed[0] : parsed) : null;
              var vars = target && target.variables ? target.variables : urlVars;
              if (!vars || typeof vars !== "object") return "fără variabile în cerere";
              var paths = cursorPaths(vars);
              var bodies = window.__akHist || [];
              var info = { cursor: null, hasNext: null, last: null };
              for (var b = bodies.length - 1; b >= 0 && !info.cursor && !info.last; b--) { try { info = cursorOf(JSON.parse(bodies[b])); } catch (e) {} }
              var pathIdx = 0, pages = 0;
              for (var pg = 0; pg < 500; pg++) {
                if (info.hasNext === false) return "ok";
                var o = oldest();
                var cursor = info.cursor || info.last || (o ? btoa(o.watchedAt + ":" + o.id) : null);
                if (!cursor) return "fără cursor";
                setPath(vars, paths[pathIdx], cursor);
                var url = req.url, body = req.body;
                if (target && target.variables) body = JSON.stringify(parsed);
                else { u.searchParams.set("variables", JSON.stringify(vars)); url = u.toString(); }
                var r = await fetch(url, { method: req.method || "POST", headers: req.headers || {}, body: (req.method || "POST") === "GET" ? undefined : body, credentials: "include" });
                if (!r.ok) return "răspuns " + r.status + " la pagina " + (pages + 2);
                var j = await r.json();
                var before = R.items.length;
                R.walk(j, 0);
                AK.progress(R.items.length);
                if (R.items.length === before) {
                  // Nimic nou: fie am ajuns la capăt, fie cursorul e în altă variabilă.
                  if (pages === 0 && pathIdx < paths.length - 1) { pathIdx++; continue; }
                  return pages > 0 ? "ok" : "nicio pagină nouă (" + paths.map(function (p) { return p.join("."); }).join("/") + ")";
                }
                pages++;
                info = cursorOf(j);
                await sleep(250);
              }
              return "ok";
            };

            var reqs = window.__akHistReqs || [];
            var how = "", replayResult = reqs.length ? "" : "nicio cerere prinsă";
            if (reqs.length) {
              try { replayResult = await replay(reqs[0]); } catch (e) { replayResult = "eroare " + e; }
            }
            if (replayResult === "ok") {
              how = "pagini cerute direct";
            } else {
              // Plasa de siguranță: butonul „Show more” din pagină.
              var MORE = /^(show more|load more|see more|more|mai mult|arată mai mult|afișează mai mult|mehr anzeigen|ver más|mostrar más|afficher plus)$/i;
              var clicks = 0;
              for (var c = 0; c < 400; c++) {
                var btn = Array.prototype.find.call(document.querySelectorAll("button, [role=button]"), function (el) {
                  return MORE.test((el.innerText || el.textContent || "").trim()) && !el.disabled;
                });
                if (!btn) break;
                var before = R.items.length;
                btn.click();
                clicks++;
                for (var t = 0; t < 16 && R.items.length === before; t++) { await sleep(500); readBodies(); }
                AK.progress(R.items.length);
                if (R.items.length === before) break;
              }
              how = "butonul „Show more” × " + clicks + " (cererea directă: " + replayResult + ")";
            }
            R.items.sort(function (a, b) { return b.watchedAt - a.watchedAt; });
            var withMinute = R.items.filter(function (it) { return it.bookmark; }).length;
            AK.done(JSON.stringify({ profile: "", items: R.items,
              diag: how + " · cu minut: " + withMinute + " · câmpuri: " + R.info.recKeys }));
          } catch (e) { AK.fail("Eroare: " + e); }
        })();
    """.trimIndent()

    /**
     * Prime Video: pagina „Istoric vizionări” are prima porție de istoric în pagină (JSON în
     * `script[type="text/template"]`), iar restul îl cere în timp ce derulezi (răspunsuri JSON).
     * Citim ambele: fiecare titlu are `gti`, `title.text`, `time` (ms), iar serialele au episoadele
     * în `children`. Metoda e cea a exportatorului open-source „watch-history-exporter-for-amazon-prime-video”.
     * Plasă de siguranță: legăturile din pagină (doar filme, fără oră).
     */
    val PRIME = """
        (async function () {
          try {
            var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
            AK.progress(0);
            var out = [], seen = {}, responses = 0;
            var clean = function (t) { var d = document.createElement("textarea"); d.innerHTML = String(t || ""); return d.value.replace(/\s+/g, " ").trim(); };
            var ms = function (t) { var n = Number(t); if (!(n > 0)) return 0; return n < 100000000000 ? n * 1000 : n; };
            var SEASON = /^(.*?)\s*[-–—:]\s*((?:season|sezonul|sezon|staffel|temporada|saison|stagione|seizoen)\s*\d{1,3})\s*$/i;
            var push = function (it) {
              var key = it.id + "|" + it.title + "|" + it.date;
              if (seen[key]) return;
              seen[key] = 1;
              out.push(it);
            };
            var addItem = function (item) {
              var title = clean(item.title && item.title.text);
              if (!title) return;
              var kids = Array.isArray(item.children) ? item.children : [];
              if (kids.length) {
                var m = title.match(SEASON);
                var series = m ? m[1].trim() : title, season = m ? m[2].trim() : null;
                for (var i = 0; i < kids.length; i++) {
                  var ep = kids[i] || {};
                  var name = clean(ep.title && ep.title.text);
                  if (!name) continue;
                  push({ series: series, season: season, episode: name,
                    title: series + (season ? ": " + season : "") + ": " + name,
                    date: ms(ep.time) || ms(item.time), id: String(ep.gti || item.gti || "") });
                }
              } else {
                push({ series: null, season: null, episode: null, title: title, date: ms(item.time), id: String(item.gti || "") });
              }
            };
            // Un titlu din istoric: are gti + title.text și o oră (la el sau la episoade).
            var isItem = function (o) {
              return o && typeof o === "object" && typeof o.gti === "string" && o.title && typeof o.title.text === "string" &&
                (o.time != null || (Array.isArray(o.children) && o.children.length && o.children[0] && o.children[0].time != null));
            };
            var handle = function (o, d) {
              if (!o || typeof o !== "object" || (d || 0) > 20) return;
              if (isItem(o)) { addItem(o); return; }
              for (var k in o) handle(o[k], (d || 0) + 1);
            };
            var scripts = document.querySelectorAll('script[type="text/template"]');
            for (var s = 0; s < scripts.length; s++) { try { handle(JSON.parse(scripts[s].textContent.trim()), 0); } catch (e) {} }
            var of = window.fetch;
            window.fetch = function () {
              return of.apply(this, arguments).then(function (r) {
                try {
                  var ct = (r.headers && r.headers.get("content-type")) || "";
                  if (ct.indexOf("json") >= 0) r.clone().json().then(function (j) { responses++; handle(j, 0); }).catch(function () {});
                } catch (e) {}
                return r;
              });
            };
            var lastH = 0, lastN = -1, still = 0;
            for (var i = 0; i < 300 && still < 3; i++) {
              window.scrollTo(0, document.body.scrollHeight);
              await sleep(1200);
              var h = document.body.scrollHeight;
              if (h === lastH && out.length === lastN) still++; else still = 0;
              lastH = h;
              lastN = out.length;
              AK.progress(out.length);
            }
            window.fetch = of;
            var how = "date din pagină (" + responses + " răspunsuri)";
            if (!out.length) {
              how = "legături din pagină (fără oră)";
              var links = document.querySelectorAll('a[href*="/detail/"], a[href*="/dp/"]');
              for (var k = 0; k < links.length; k++) {
                var a = links[k];
                var img = a.querySelector("img");
                var t = ((a.innerText || "").trim() || (img && img.alt) || a.getAttribute("aria-label") || "").split("\n")[0].trim();
                if (t.length < 2) continue;
                push({ series: null, season: null, episode: null, title: t, date: 0, id: a.getAttribute("href") || "" });
              }
            }
            if (!out.length) { AK.fail("Nu am găsit titluri. Ești conectat și se vede pagina „Istoric vizionări”?"); return; }
            var eps = out.filter(function (x) { return x.series; }).length;
            AK.done(JSON.stringify({ profile: "", items: out, diag: how + " · episoade: " + eps + " · filme: " + (out.length - eps) }));
          } catch (e) { AK.fail("Eroare: " + e); }
        })();
    """.trimIndent()
}
