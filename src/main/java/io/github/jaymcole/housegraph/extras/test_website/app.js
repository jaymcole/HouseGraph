// Proves JavaScript assets are served and executed, and demonstrates the shared,
// server-side data store via the web server's /api/data endpoint.
(function () {
    // --- Live facts -----------------------------------------------------------
    document.getElementById("url").textContent = window.location.href;
    document.getElementById("host").textContent = window.location.host || "(unknown)";
    document.getElementById("time").textContent = new Date().toLocaleString();

    // --- Shared notes, backed by /api/data ------------------------------------
    const notes = document.getElementById("notes");
    const saveButton = document.getElementById("save");
    const status = document.getElementById("notes-status");

    // The document is a single JSON blob; we keep our text under a "notes" key.
    async function load() {
        try {
            const res = await fetch("/api/data");
            if (res.status === 503) {
                status.textContent = "No data store configured on this server.";
                notes.placeholder = "Wire a Data Store node into the Web Server node to enable this.";
                return;
            }
            if (!res.ok) throw new Error("HTTP " + res.status);
            const doc = await res.json();
            notes.value = (doc && typeof doc.notes === "string") ? doc.notes : "";
            notes.disabled = false;
            saveButton.disabled = false;
            notes.placeholder = "Type something…";
            status.textContent = "Loaded.";
        } catch (err) {
            status.textContent = "Couldn't load: " + err.message;
        }
    }

    async function save() {
        saveButton.disabled = true;
        status.textContent = "Saving…";
        try {
            const res = await fetch("/api/data", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ notes: notes.value, savedAt: new Date().toISOString() }),
            });
            if (!res.ok) throw new Error("HTTP " + res.status);
            status.textContent = "Saved at " + new Date().toLocaleTimeString();
        } catch (err) {
            status.textContent = "Couldn't save: " + err.message;
        } finally {
            saveButton.disabled = false;
        }
    }

    saveButton.addEventListener("click", save);
    load();
})();
