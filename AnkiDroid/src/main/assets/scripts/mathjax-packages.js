// SPDX-License-Identifier: GPL-3.0-or-later

// Enables MathJax TeX packages that are compiled into the bundled tex-chtml-full.js
// but not activated by the default backend config (backend/js/mathjax.js):
// * mhchem   - chemistry: \( \ce{ H2O } \)
// * braket   - Dirac/quantum notation: \( \langle \phi | \psi \rangle \)
// * physics  - physics/quantum operators: \( \dd{x} \), \( \comm{A}{B} \)
// * color    - colored math: \( \color{red} x \)
// The script must execute after mathjax.js (which creates window.MathJax) and
// before tex-chtml-full.js (which consumes the config), so it is injected between
// the two in every card rendering path.
(function () {
    if (typeof window.MathJax !== "object" || window.MathJax === null) return;
    var tex = window.MathJax.tex;
    if (typeof tex !== "object" || tex === null) return;
    var packages = tex.packages || (tex.packages = {});
    var extra = ["mhchem", "braket", "physics", "color"];
    var plus = packages["[+]"] || (packages["[+]"] = []);
    extra.forEach(function (name) {
        if (plus.indexOf(name) === -1) plus.push(name);
    });
    var load = window.MathJax.loader && window.MathJax.loader.load;
    if (Array.isArray(load)) {
        extra.forEach(function (name) {
            if (load.indexOf("[tex]/" + name) === -1) load.push("[tex]/" + name);
        });
    }
})();
