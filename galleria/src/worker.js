// Galleria Booth: serve la pagina (cartella public/), riceve le dediche dal booth e le tiene in R2.
//
//  GET  /events/<evento>/manifest.json | media/<file> | thumbs/<file>   pubblico (da R2, poi dai file statici: evento "demo")
//  GET  /api/ping                                                       prova della chiave
//  PUT  /api/file/<evento>/<media|thumbs>/<file>                        carica un file (corpo = il file)
//  POST /api/item/<evento>                                              aggiunge la dedica all'elenco {event, item}
//
// Le chiamate /api/* vogliono l'intestazione  Authorization: Bearer <UPLOAD_TOKEN>  (segreto del Worker).

const CODE = "[A-Za-z0-9_-]{1,64}";
const FILE = "[A-Za-z0-9][A-Za-z0-9._-]{0,119}";
const RE_CODE = new RegExp("^" + CODE + "$");
const RE_FILE = new RegExp("^" + FILE + "$");
const RE_PUBLIC = new RegExp("^events/(" + CODE + ")/(manifest\\.json|(?:media|thumbs)/" + FILE + ")$");
const RE_MEDIA = new RegExp("^media/" + FILE + "$");
const RE_THUMB = new RegExp("^thumbs/" + FILE + "$");
const RE_ID = /^[A-Za-z0-9_-]{1,100}$/;
const RE_DAY = /^\d{4}-\d{2}-\d{2}$/;

const MAX_UPLOAD = 95 * 1024 * 1024; // il limite delle richieste del piano gratuito è 100 MB
const TYPES = { jpg: "image/jpeg", mp4: "video/mp4" };
const SAFE = {
  "x-robots-tag": "noindex, nofollow",
  "x-content-type-options": "nosniff",
  "referrer-policy": "same-origin",
};

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path.startsWith("/api/")) return api(req, env, url);
    if ((req.method === "GET" || req.method === "HEAD") && path.startsWith("/events/")) {
      const r = await fromBucket(req, env, path);
      if (r) return r;
    }
    return env.ASSETS.fetch(req);
  },
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...SAFE },
  });
}

// ---------------------------------------------------------------- lettura pubblica

async function fromBucket(req, env, path) {
  let key;
  try { key = decodeURIComponent(path.slice(1)); } catch (e) { return null; }
  if (!RE_PUBLIC.test(key)) return null;

  const obj = await env.BUCKET.get(key, { range: req.headers, onlyIf: req.headers });
  if (!obj) return null;

  const h = new Headers(SAFE);
  obj.writeHttpMetadata(h);
  h.set("etag", obj.httpEtag);
  h.set("accept-ranges", "bytes");
  h.set(
    "cache-control",
    key.endsWith("/manifest.json") ? "no-cache" : "public, max-age=31536000, immutable",
  );

  if (!("body" in obj) || !obj.body) return new Response(null, { status: 304, headers: h }); // già aggiornato

  let status = 200;
  if (obj.range && req.headers.get("range")) {
    const start = obj.range.offset ?? 0;
    const end = obj.range.end ?? obj.size - 1;
    h.set("content-range", `bytes ${start}-${end}/${obj.size}`);
    h.set("content-length", String(end - start + 1));
    status = 206;
  } else {
    h.set("content-length", String(obj.size));
  }
  return new Response(req.method === "HEAD" ? null : obj.body, { status, headers: h });
}

// ---------------------------------------------------------------- scrittura (solo con la chiave)

async function authorized(req, env) {
  const auth = req.headers.get("authorization") || "";
  const given = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!env.UPLOAD_TOKEN || !given) return false;
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(given)),
    crypto.subtle.digest("SHA-256", enc.encode(env.UPLOAD_TOKEN)),
  ]);
  const x = new Uint8Array(a);
  const y = new Uint8Array(b);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

async function api(req, env, url) {
  if (!(await authorized(req, env))) return json({ error: "chiave non valida" }, 401);
  const p = url.pathname.split("/").filter(Boolean); // ["api", ...]

  if (req.method === "GET" && p.length === 2 && p[1] === "ping") return json({ ok: true });

  if (req.method === "PUT" && p.length === 5 && p[1] === "file") return putFile(req, env, p[2], p[3], p[4]);

  if (req.method === "POST" && p.length === 3 && p[1] === "item") return postItem(req, env, p[2]);

  return json({ error: "non trovato" }, 404);
}

async function putFile(req, env, code, kind, file) {
  if (!RE_CODE.test(code) || !RE_FILE.test(file) || (kind !== "media" && kind !== "thumbs")) {
    return json({ error: "percorso non valido" }, 400);
  }
  const ext = file.split(".").pop().toLowerCase();
  if (!TYPES[ext] || (kind === "thumbs" && ext !== "jpg")) return json({ error: "tipo di file non ammesso" }, 400);

  const len = Number(req.headers.get("content-length"));
  if (!len || len < 1) return json({ error: "manca la lunghezza del file" }, 411);
  if (len > MAX_UPLOAD) return json({ error: "file troppo grande" }, 413);
  if (!req.body) return json({ error: "file vuoto" }, 400);

  await env.BUCKET.put(`events/${code}/${kind}/${file}`, req.body, {
    httpMetadata: { contentType: TYPES[ext] },
  });
  return json({ ok: true, size: len });
}

function clean(s, max) {
  return String(s == null ? "" : s).replace(/[\u0000-\u001f]/g, " ").trim().slice(0, max);
}
function whole(n, min, max, dflt) {
  n = Math.round(Number(n));
  return Number.isFinite(n) && n >= min && n <= max ? n : dflt;
}

async function postItem(req, env, code) {
  if (!RE_CODE.test(code)) return json({ error: "evento non valido" }, 400);
  let body;
  try {
    const text = await req.text();
    if (text.length > 20000) return json({ error: "richiesta troppo grande" }, 413);
    body = JSON.parse(text);
  } catch (e) {
    return json({ error: "JSON non valido" }, 400);
  }
  const it = body && body.item;
  const ev = (body && body.event) || {};
  if (!it || !RE_ID.test(String(it.id)) || (it.type !== "photo" && it.type !== "video")) {
    return json({ error: "dedica non valida" }, 400);
  }
  if (!RE_MEDIA.test(String(it.src))) return json({ error: "src non valido" }, 400);
  if (it.thumb && !RE_THUMB.test(String(it.thumb))) return json({ error: "thumb non valida" }, 400);

  // la dedica entra nell'elenco solo se il file c'è davvero
  const base = `events/${code}/`;
  const media = await env.BUCKET.head(base + it.src);
  if (!media) return json({ error: "file non ancora caricato" }, 409);
  const thumb = it.thumb && (await env.BUCKET.head(base + it.thumb)) ? String(it.thumb) : null;

  const item = {
    id: String(it.id),
    type: it.type,
    name: clean(it.name, 80) || (it.type === "video" ? "Dedica" : "Foto"),
    time: Number.isNaN(Date.parse(it.time)) ? new Date().toISOString() : String(it.time),
    w: whole(it.w, 1, 20000, it.type === "video" ? 360 : 1280),
    h: whole(it.h, 1, 20000, it.type === "video" ? 640 : 720),
    src: String(it.src),
    size: media.size,
  };
  if (thumb) item.thumb = thumb;
  if (it.type === "video") item.duration = whole(it.duration, 0, 86400, 0);

  const key = base + "manifest.json";
  const cur = await env.BUCKET.get(key);
  let m = null;
  if (cur) { try { m = await cur.json(); } catch (e) { m = null; } }
  const day = RE_DAY.test(String(ev.date)) ? String(ev.date) : new Date().toISOString().slice(0, 10);
  if (!m || !Array.isArray(m.items)) {
    m = {
      version: 1,
      event: {
        code,
        title: clean(ev.title, 80) || "Booth",
        subtitle: clean(ev.subtitle, 80) || "Le dediche del booth",
        date: day,
      },
      base: "",
      items: [],
    };
    if (RE_DAY.test(String(ev.expires))) m.event.expires = String(ev.expires);
  } else if (m.event && RE_DAY.test(String(m.event.date)) && day < m.event.date) {
    m.event.date = day; // la data dell'evento è quella della prima dedica
  }
  m.items = m.items.filter((x) => x && x.id !== item.id);
  m.items.push(item);

  await env.BUCKET.put(key, JSON.stringify(m), {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
  });
  return json({ ok: true, count: m.items.length, link: `/?e=${code}` });
}
