# parallax-background 2.1.0, patched for GWT — TEMPORARY

**Delete this directory when parallax-background 2.2.0 is on Maven Central** — that publish is
**parallax:r44** on the parallax board. Then put `io.github.javakhanstudio:parallax-background:2.2.0`
and its `:sources` jar in `html/build.gradle`'s `gwt` configuration, bump `parallaxVersion` in
`gradle.properties`, and delete this copy.

Why it is here (Simon on r46, 2026-09-25, question 2 → B): 2.1.0 as published cannot be translated
to JavaScript, and the html module had to exist before 2.2.0 does. These are the sources of
`parallax-background-2.1.0-sources.jar`, with exactly the three changes the r20 spike made
(`tools/browser-spike/build.sh`, docs/browser-target.md §10) — the same three 2.2.0 makes:

1. Kryo and Jackson are gone: the five `*_Serializer` classes, `GVars_Serialization` and
   `Utils_Page` are deleted, and their annotations stripped from `WholePage_Model`,
   `Parallax_Model`, `Page_Model` and `ParallaxLayer`.
2. `Parallax_Heart(String)`, the only Kryo entry point, is removed. The game builds its pages in
   Java (`ColdNightModel`) and never called it.
3. `ParallaxLayer` no longer `implements Cloneable`; `clone()` copies field by field, because GWT
   has neither `Cloneable` nor `Object.clone()`.

Only the GWT compiler reads this directory. `core` and `desktop` still use the published 2.1.0 jar.
