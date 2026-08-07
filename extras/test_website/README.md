# test_website — a sample site for the Web Server node

A tiny static site (`index.html` + `style.css` + `app.js`) for exercising the
**Web Server** node (`graph/nodes/web/WebServerNode`). It's plain files, not Java —
Gradle doesn't compile it, exactly like the `squirrel_status/` firmware next door.

## How to use

1. Add a **Web Server** node to the canvas.
2. Click **Browse…** and point it at this folder.
3. Give it a name (e.g. `mysite`) and a port (default `8080`), then click **Start**.
4. Open `http://<name>.local:<port>/` (e.g. `http://mysite.local:8080/`).
   If `.local` doesn't resolve on your machine, use `http://localhost:8080/` or the
   host's LAN IP.

The page shows the URL it was served from and the load time, and it loads a
stylesheet and a script — so a correct render confirms HTML, CSS, and JS all serve
with the right content types. Edit any file and refresh to see live changes.

## Trying the shared data store

The page also has a **Shared notes** box backed by the web server's
`/api/data` endpoint. To enable it:

1. Add a **Data Store** node (it's under the loader category).
2. **Wire its `Store` output into the Web Server node's `Store` input**, then Start.
3. Reload the page — type something, Save, and open the page on another device: the
   same text is there. It's persisted on the host (under HouseGraph's app data),
   so it also survives restarts.

If nothing is wired into the `Store` input, the API returns `503` and the notes box
shows a hint instead — the rest of the page still works.
