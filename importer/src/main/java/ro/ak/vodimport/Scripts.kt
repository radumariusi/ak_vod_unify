package ro.ak.vodimport

/**
 * Scripturile rulate în pagina serviciului, după ce te-ai conectat. Folosesc sesiunea ta din
 * browserul aplicației (cookie-urile), exact ca extensiile de browser pentru istoric, și trimit
 * rezultatul înapoi prin puntea `AK` (progress / done / fail).
 */
object Scripts {

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
            if (!base) { AK.fail("Netflix a răspuns cu " + codes.join("/") + ". Alege întâi profilul (pagina „Cine se uită?”), apoi apasă din nou."); return; }
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
