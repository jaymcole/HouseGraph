// Proves JavaScript assets are served and executed. Fills in the live facts on the page.
(function () {
    document.getElementById("url").textContent = window.location.href;
    document.getElementById("host").textContent = window.location.host || "(unknown)";
    document.getElementById("time").textContent = new Date().toLocaleString();
})();
