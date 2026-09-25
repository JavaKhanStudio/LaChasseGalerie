#!/usr/bin/env bash
# build.sh — compile this game to JavaScript with libGDX's own GWT backend and serve it.
#
# SUPERSEDED by the html module (r81, docs/browser-target.md section 11): ./gradlew :html:war.
#
# This is the r20 spike, kept whole so the answer can be re-checked rather than believed. It is
# NOT part of the build: nothing here is in settings.gradle, and ./gradlew never runs it. It
# writes everything into a work directory outside the repo (default /tmp/browser-spike).
#
#   tools/browser-spike/build.sh            # compile and serve on http://localhost:8099
#   tools/browser-spike/build.sh --prod     # optimized compile (about 30 s instead of 6 s)
#   tools/browser-spike/build.sh --no-serve # just compile
#
# It patches a COPY of the parallax library's sources, because parallax-background 2.1.0 cannot
# be translated as published. Every patch it makes is a change that library needs (see
# docs/browser-target.md §10); none of them touches this repo or the published artifact.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="${BROWSER_SPIKE_WORK:-/tmp/browser-spike}"
port="${BROWSER_SPIKE_PORT:-8099}"
prod=0 serve=1
for arg in "$@"; do
	case "$arg" in
		--prod) prod=1 ;;
		--no-serve) serve=0 ;;
		*) echo "unknown option: $arg" >&2; exit 2 ;;
	esac
done

echo "== resolving the GWT dependency set (sources jars included: GWT eats Java source)"
mkdir -p "$work"
cat > "$work/settings.gradle" <<'EOF'
rootProject.name = 'browser-spike'
EOF
cat > "$work/build.gradle" <<'EOF'
plugins { id 'java' }
repositories { mavenCentral() }
configurations { gwt }
def gdx = '1.14.2', ctrl = '2.2.4', gwtv = '2.11.0'
dependencies {
    gwt "com.badlogicgames.gdx:gdx:$gdx"
    gwt "com.badlogicgames.gdx:gdx:$gdx:sources"
    gwt "com.badlogicgames.gdx:gdx-backend-gwt:$gdx"
    gwt "com.badlogicgames.gdx:gdx-backend-gwt:$gdx:sources"
    gwt "com.badlogicgames.gdx:gdx-box2d:$gdx"
    gwt "com.badlogicgames.gdx:gdx-box2d:$gdx:sources"
    gwt "com.badlogicgames.gdx:gdx-box2d-gwt:$gdx"
    gwt "com.badlogicgames.gdx:gdx-box2d-gwt:$gdx:sources"
    gwt "com.badlogicgames.gdx-controllers:gdx-controllers-core:$ctrl"
    gwt "com.badlogicgames.gdx-controllers:gdx-controllers-core:$ctrl:sources"
    gwt "com.badlogicgames.gdx-controllers:gdx-controllers-gwt:$ctrl"
    gwt "com.badlogicgames.gdx-controllers:gdx-controllers-gwt:$ctrl:sources"
    gwt "io.github.javakhanstudio:parallax-background:2.1.0"
    gwt "io.github.javakhanstudio:parallax-background:2.1.0:sources"
    gwt "org.gwtproject:gwt-user:$gwtv"
    gwt "org.gwtproject:gwt-dev:$gwtv"
}
tasks.register('copyDeps', Copy) { from configurations.gwt; into 'libs' }
EOF
"$root/gradlew" -p "$work" copyDeps -q --no-configuration-cache

echo "== assembling the source tree: the game, then the parallax library under the same jks package"
rm -rf "$work/src"
mkdir -p "$work/src/jks/html"
cp -r "$root/core/src/jks" "$work/src/"
(cd "$work/src" && unzip -oq ../libs/parallax-background-2.1.0-sources.jar 'jks/*' && rm -rf META-INF)

echo "== patching the copy of parallax-background 2.1.0 (this is what the library itself needs)"
par="$work/src/jks/tools2d/parallax"
# 1. Kryo and Jackson have no browser story. The serializers are unreachable from the game, which
#    builds its pages in Java (ColdNightModel), so they go, and the annotations come off the models.
rm -f "$par/heart/GVars_Serialization.java" "$par/pages/Color_Serializer.java" \
	"$par/pages/Page_Model_Serializer.java" "$par/pages/Parallax_Model_Serializer.java" \
	"$par/pages/WholePage_Model_Serializer.java" "$par/pages/Utils_Page.java"
for f in $(grep -rl "kryo\|jackson" --include=*.java "$par"); do
	sed -i -e '/import com.esotericsoftware.kryo/d' -e '/import com.fasterxml.jackson/d' \
		-e '/^@JsonIgnore/d' -e '/^@JsonIgnoreType/d' -e '/^@JsonIgnoreProperties/d' \
		-e '/^@DefaultSerializer/d' -e 's/@JsonIgnore//g' "$f"
done
# 2. Parallax_Heart(String) is the library's only Kryo entry point, and the game never calls it.
python3 - "$par/heart/Parallax_Heart.java" <<'EOF'
import re, sys
p = sys.argv[1]
s = open(p).read()
s = s.replace("import jks.tools2d.parallax.pages.Utils_Page;\n", "")
s = re.sub(r"\tpublic Parallax_Heart\(String internalPath\)\s*\{\s*this\(\);\s*setPage\(Utils_Page\.loadPage\(internalPath\)\);\s*\}",
           "\t// removed by the spike: the library's only Kryo entry point, which the game never calls",
           s, flags=re.S)
open(p, "w").write(s)
EOF
# 3. GWT emulates no Cloneable and no Object.clone(). Field by field instead.
python3 - "$par/ParallaxLayer.java" <<'EOF'
import re, sys
p = sys.argv[1]
s = open(p).read()
s = s.replace("public class ParallaxLayer implements Cloneable", "public class ParallaxLayer")
s = re.sub(r"\t@Override\n\tpublic ParallaxLayer clone\s*\(\)\s*\{.*?\n\t\}",
"""\tpublic ParallaxLayer clone()
\t{
\t\tParallaxLayer copy = new ParallaxLayer(new ArrayList<>(texRegion), isWidth, worldDimension,
\t\t\t\tparallaxSpeedRatioX, parallaxSpeedRatioY, sizeRatio);
\t\tcopy.decalPercentX = decalPercentX;
\t\tcopy.decalPercentY = decalPercentY;
\t\tcopy.regionWidth = regionWidth;
\t\tcopy.regionHeight = regionHeight;
\t\tcopy.currentDistanceX = currentDistanceX;
\t\tcopy.currentDistanceY = currentDistanceY;
\t\tcopy.padX = padX;
\t\tcopy.padXFactor = padXFactor;
\t\tcopy.padY = padY;
\t\tcopy.padYFactor = padYFactor;
\t\tcopy.speedXAtRest = speedXAtRest;
\t\tcopy.flipX = flipX;
\t\tcopy.flipY = flipY;
\t\tcopy.isMirror = isMirror;
\t\treturn copy;
\t}""", s, flags=re.S)
open(p, "w").write(s)
EOF

echo "== the browser launcher and the GWT module"
cp "$root/tools/browser-spike/HtmlLauncher.java" "$work/src/jks/html/"
sed "s#@ASSETS@#$root/desktop/assets#" "$root/tools/browser-spike/GdxDefinition.gwt.xml" > "$work/src/jks/GdxDefinition.gwt.xml"

war="$work/war"
flags=(-draftCompile -style PRETTY)
[ "$prod" = 1 ] && { war="$work/war-prod"; flags=(-optimize 9); }

echo "== compiling to JavaScript"
rm -rf "$war/html"
# The 32 MB of assets are copied into the war by a GWT GENERATOR, and a generator does not run
# again while its output is in the unit cache — which is how a second compile silently produces a
# page with no assets. One cache per work directory keeps that honest.
# ... and the generator writes its asset copy to a path RELATIVE TO THE WORKING DIRECTORY, not to
# -war, so the compiler is run from inside the work directory or it drops 32 MB wherever you stood.
(cd "$work" && java -Xmx3g -Dgwt.persistentunitcachedir="$work/unitCache" \
	-cp "$work/src:$work/libs/*" com.google.gwt.dev.Compiler -war "$war" "${flags[@]}" jks.GdxDefinition)
if [ ! -d "$war/assets" ]; then
	echo "!! the asset generator did not run: $war has no assets and the page will hang on the preloader" >&2
	exit 1
fi

cat > "$war/index.html" <<'EOF'
<!DOCTYPE html>
<html>
<head>
	<meta charset="utf-8">
	<title>La chasse-galerie</title>
	<style>body { margin: 0; background: #101820; }</style>
	<script type="text/javascript" src="html/html.nocache.js"></script>
</head>
<body>
	<div id="embed-html"></div>
</body>
</html>
EOF

echo "== $(du -sh "$war/html" | cut -f1) of JavaScript in $war"
if [ "$serve" = 1 ]; then
	echo "== serving http://localhost:$port/index.html  (?mute&debug&menu work like the launcher flags)"
	cd "$war" && exec python3 -m http.server "$port"
fi
