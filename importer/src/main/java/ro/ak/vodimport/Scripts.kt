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
          var note = function (url, status, body) {
            try {
              var b = String(body || "");
              if (b.indexOf("ProfileViewingHistoryRecord") >= 0 && window.__akHist.length < 30) window.__akHist.push(b);
              var i = b.search(/bookmark|watchedDate|viewedDate|dateStr|lastWatched|playbackPosition|progress/i);
              var keys = (b.match(/"(bookmark\w*|watched\w*|viewed\w*|date\w*|progress\w*|runtime|duration|title|seriesTitle|episodeTitle|lastModified\w*|timestamp)"/gi) || [])
                .filter(function (v, k, a) { return a.indexOf(v) === k; }).slice(0, 15).join("");
              window.__akCap.push({ u: String(url).replace(/^https:\/\/[^\/]+/, "").slice(0, 80), s: status, n: b.length, k: keys,
                x: i >= 0 ? b.slice(Math.max(0, i - 80), i + 160) : b.slice(0, 200) });
            } catch (e) {}
          };
          var of = window.fetch;
          if (of) {
            window.fetch = function (input, init) {
              var url = (input && input.url) || input;
              return of.apply(this, arguments).then(function (r) {
                if (keep(url)) { try { r.clone().text().then(function (t) { note(url, r.status, t); }); } catch (e) {} }
                return r;
              });
            };
          }
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function (m, u) { this.__akU = u; return oo.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function () {
            var x = this;
            x.addEventListener("load", function () { if (keep(x.__akU)) note(x.__akU, x.status, x.responseText); });
            return os.apply(this, arguments);
          };
        })();
    """.trimIndent()

    /**
     * Netflix „Watched” (/settings/viewed): din răspunsurile graphql prinse de [NETFLIX_CAPTURE_HOOK]
     * scoate fiecare ProfileViewingHistoryRecord: ora exactă (lastWatchedTimeMs) și titlul.
     * Nu știm forma exactă a obiectului video, deci îl citim generic: titlul lui, serialul
     * (obiect „Show”), sezonul (obiect „Season”) și numărul episodului. Sincron; JSON {items, diag}.
     */
    val NETFLIX_HISTORY_COLLECT = """
        (function () {
          var out = { items: [], diag: "" }, seen = {}, sample = "";
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
          var add = function (rec) {
            var at = Number(rec.lastWatchedTimeMs || rec.lastWatchedTime || rec.watchedTimeMs || 0);
            var v = rec.video || find(rec, /video|episode|movie/i, 0) || {};
            if (!sample) sample = JSON.stringify(v).slice(0, 300);
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
            var season = sTitle || (sNum ? "Season " + sNum : "");
            out.items.push({
              series: isEp && show ? show : null,
              season: isEp && season ? season : null,
              episode: isEp && show ? name : null,
              title: isEp && show ? show + (season ? ": " + season : "") + ": " + name : name,
              date: at, watchedAt: at,
              seasonNumber: sNum || null, episodeNumber: eNum || null,
              id: id
            });
          };
          var walk = function (o, depth) {
            if (!o || typeof o !== "object" || depth > 12) return;
            if (o.__typename === "ProfileViewingHistoryRecord") { add(o); return; }
            for (var k in o) walk(o[k], depth + 1);
          };
          var bodies = window.__akHist || [], bad = 0;
          for (var i = 0; i < bodies.length; i++) {
            try { walk(JSON.parse(bodies[i]), 0); } catch (e) { bad++; }
          }
          out.items.sort(function (a, b) { return b.watchedAt - a.watchedAt; });
          out.diag = "raspunsuri " + bodies.length + (bad ? " (" + bad + " ilizibile)" : "") + " · inregistrari " + out.items.length +
            (out.items.length ? "" : " · video: " + sample);
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /**
     * Netflix: ce e în pagina deschisă (/browse) — „Continuă vizionarea” are, pentru fiecare
     * titlu început, minutul unde ai rămas (falcorCache). Sincron (îl rulăm și cu
     * evaluateJavascript, care nu așteaptă promisiuni). Întoarce JSON {items, diag}.
     */
    val NETFLIX_PAGE_SCAN = """
        (function () {
          var out = { items: [], diag: "" };
          try {
            var n = window.netflix || {};
            var fc = n.falcorCache || null;
            var vids = fc && fc.videos ? fc.videos : null;
            var val = function (x) { return (x && typeof x === "object" && "value" in x) ? x.value : x; };
            var showOf = {};
            if (vids) {
              for (var sid in vids) {
                var sv = vids[sid];
                var cur = sv && val(sv.current);
                if (Array.isArray(cur) && cur.length > 1) showOf[String(cur[cur.length - 1])] = sid;
              }
              for (var id in vids) {
                var v = vids[id];
                if (!v) continue;
                var bm = val(v.bookmarkPosition);
                if (typeof bm !== "number" || bm <= 0) continue;
                var sum = val(v.summary) || {};
                var title = val(v.title) || "";
                var showId = showOf[String(id)];
                var series = null, season = null, episode = null;
                if (sum.type === "episode" || showId) {
                  series = showId && vids[showId] ? (val(vids[showId].title) || null) : null;
                  episode = title || null;
                  if (typeof sum.season === "number") season = "Season " + sum.season;
                  if (typeof sum.episode === "number" && !episode) episode = "Episode " + sum.episode;
                  if (!series) { series = null; }
                }
                if (!title && !series) continue;
                out.items.push({ series: series, season: season, episode: series ? episode : null, title: title || series || "",
                  date: Date.now(), bookmark: bm, duration: val(v.runtime) || null, id: String(id) });
              }
            }
            var wk = Object.keys(window).filter(function (k) { return /netflix|apollo|cache|state|falcor/i.test(k); }).slice(0, 12).join(",");
            out.diag = "chei netflix: " + Object.keys(n).slice(0, 12).join(",") + " · falcor: " + (fc ? "da" : "nu") +
              " · titluri: " + (vids ? Object.keys(vids).length : 0) + " · cu minut: " + out.items.length + " · window: " + wk;
          } catch (e) { out.diag = "eroare: " + e; }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /** Netflix: API-ul intern al paginii „Activitate de vizionare” (viewingactivity), pagină cu pagină. */
    val NETFLIX = """
        (async function () {
          try {
            var profile = "";
            var ctx = (window.netflix && (window.netflix.reactContext || (window.netflix.appContext && window.netflix.appContext.state && window.netflix.appContext.state.model))) || null;
            var build = null;
            try { build = ctx.models.serverDefs.data.BUILD_IDENTIFIER; } catch (e) {}
            AK.progress(0);
            if (!build) {
              var m = document.documentElement.innerHTML.match(/"BUILD_IDENTIFIER":"([^"]+)"/);
              if (m) build = m[1];
            }
            if (!build) {
              // Paginile noi (ex. /settings/viewed/…) nu au datele vechi: le luăm din /browse.
              try {
                var html = await (await fetch("/browse", { credentials: "include" })).text();
                var mb = html.match(/"BUILD_IDENTIFIER":"([^"]+)"/);
                if (mb) build = mb[1];
                if (!profile) {
                  var mp = html.match(/"userInfo":\{"data":\{"name":"([^"]*)"/);
                  if (mp) profile = mp[1];
                }
              } catch (e) {}
            }
            var auth = null;
            try { auth = ctx.models.userInfo.data.authURL; } catch (e) {}
            if (!auth) {
              try {
                var h2 = await (await fetch("/browse", { credentials: "include" })).text();
                var ma = h2.match(/"authURL":"([^"]+)"/);
                if (ma) auth = ma[1].replace(/\\x([0-9A-Fa-f]{2})/g, function (_, x) { return String.fromCharCode(parseInt(x, 16)); });
                if (!build) { var mb2 = h2.match(/"BUILD_IDENTIFIER":"([^"]+)"/); if (mb2) build = mb2[1]; }
              } catch (e) {}
            }
            if (!build && !auth) { AK.fail("Nu găsesc sesiunea Netflix. Conectează-te, alege profilul, apoi apasă din nou „Importă istoricul”."); return; }
            // Netflix a mutat API-ul: încercăm forma nouă („mre” + authURL), apoi cea veche.
            var bases = [];
            if (auth) bases.push("/api/shakti/mre/viewingactivity?languages=en-US&authURL=" + encodeURIComponent(auth) + "&");
            bases.push("/api/shakti/mre/viewingactivity?");
            if (build) bases.push("/api/shakti/" + build + "/viewingactivity?");
            var base = null, codes = [];
            for (var b = 0; b < bases.length && !base; b++) {
              try {
                var t = await fetch(bases[b] + "pg=0&pgSize=1", { credentials: "include" });
                codes.push(t.status);
                if (t.ok) { var tj = await t.json(); if (tj && tj.viewedItems) base = bases[b]; }
              } catch (e) { codes.push("x"); }
            }
            if (!base) {
              // API-ul e închis (403/404): arătăm ce găsim în pagină (minutul din „Continuă vizionarea”).
              var scan = {};
              try { scan = JSON.parse(${NETFLIX_PAGE_SCAN}); } catch (e) { scan = { items: [], diag: "scan: " + e }; }
              var sample = (scan.items || []).slice(0, 3).map(function (it) { return (it.series ? it.series + " · " : "") + it.title + " @" + Math.round(it.bookmark / 60) + "min"; }).join(" | ");
              // Și pagina vizibilă „Activitate de vizionare”: are datele în HTML?
              var page = "-";
              try {
                var pr = await fetch("/viewingactivity", { credentials: "include" });
                var ph = await pr.text();
                page = pr.status + " " + (pr.url || "").replace("https://www.netflix.com", "") + " " + Math.round(ph.length / 1024) + "KB" +
                  (ph.indexOf("viewedItems") >= 0 ? " viewedItems" : "") + (ph.indexOf("dateStr") >= 0 ? " dateStr" : "") +
                  (ph.indexOf("bookmark") >= 0 ? " bookmark" : "") + (ph.indexOf("falcorCache") >= 0 ? " falcor" : "");
              } catch (e) { page = "eroare " + e; }
              AK.fail("TEST Netflix · API " + codes.join("/") + " · " + scan.diag + (sample ? " · ex: " + sample : "") + " · pagina activitate: " + page);
              return;
            }
            try { if (!profile) profile = ctx.models.userInfo.data.name || ""; } catch (e) {}
            var all = [];
            for (var pg = 0; pg < 300; pg++) {
              var r = await fetch(base + "pg=" + pg + "&pgSize=100", { credentials: "include" });
              if (!r.ok) { AK.fail("Netflix a răspuns cu " + r.status + (r.status === 401 || r.status === 403 ? ". Alege întâi profilul (pagina „Cine se uită?”), apoi apasă din nou." : ". Reîncearcă după ce reîncarci pagina.")); return; }
              var j = await r.json();
              var items = j.viewedItems || [];
              for (var i = 0; i < items.length; i++) {
                var it = items[i];
                all.push({
                  series: it.seriesTitle || null,
                  season: it.seasonDescriptor || null,
                  episode: it.episodeTitle || null,
                  title: it.title || it.videoTitle || "",
                  date: it.date || 0,
                  bookmark: (typeof it.bookmark === "number" && it.bookmark > 0) ? it.bookmark : null,
                  duration: (typeof it.duration === "number" && it.duration > 0) ? it.duration : null,
                  id: String(it.movieID || "")
                });
              }
              AK.progress(all.length);
              if (items.length < 100) break;
            }
            AK.done(JSON.stringify({ profile: profile, items: all }));
          } catch (e) { AK.fail("Eroare: " + e); }
        })();
    """.trimIndent()


    /**
     * Prime Video (experimental): derulăm pagina „Istoric vizionări” ca să se încarce tot, apoi
     * strângem legăturile spre titluri și data de deasupra lor.
     */
    val PRIME = """
        (async function () {
          try {
            var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
            var last = 0;
            for (var i = 0; i < 80; i++) {
              window.scrollTo(0, document.body.scrollHeight);
              await sleep(800);
              var h = document.body.scrollHeight;
              if (h === last) break;
              last = h;
              AK.progress(i + 1);
            }
            var parseDate = function (t) {
              if (!t) return 0;
              var v = Date.parse(t);
              return isNaN(v) ? 0 : v;
            };
            var out = [], seen = {};
            var links = document.querySelectorAll('a[href*="/detail/"], a[href*="/dp/"]');
            for (var k = 0; k < links.length; k++) {
              var a = links[k];
              var img = a.querySelector("img");
              var title = (a.innerText || "").trim() || (img && img.alt) || a.getAttribute("aria-label") || "";
              title = title.split("\n")[0].trim();
              if (title.length < 2) continue;
              var date = 0, el = a;
              for (var d = 0; d < 10 && el && !date; d++) {
                el = el.parentElement;
                if (!el) break;
                var node = el.querySelector("time, [data-automation-id*='date'], h2, h3");
                if (node) date = parseDate(node.getAttribute("datetime") || node.innerText);
              }
              var key = title + "|" + date;
              if (seen[key]) continue;
              seen[key] = true;
              out.push({ series: null, season: null, episode: null, title: title, date: date, bookmark: null, duration: null, id: a.getAttribute("href") || "" });
            }
            if (!out.length) { AK.fail("Nu am găsit titluri. Ești conectat și se vede pagina „Istoric vizionări”?"); return; }
            AK.done(JSON.stringify({ profile: "", items: out }));
          } catch (e) { AK.fail("Eroare: " + e); }
        })();
    """.trimIndent()
}
