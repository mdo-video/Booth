# Galleria Booth (Cloudflare)

- `public/`  la pagina per gli ospiti (`index.html`) e l'evento di prova `demo`
- `src/worker.js`  riceve le dediche dal booth e le serve da R2
- `wrangler.jsonc`  nome Worker `mdo-booth`, bucket R2 `booth-media`

Segreto da impostare sul Worker: `UPLOAD_TOKEN` (la stessa chiave si scrive nell'app camera).

