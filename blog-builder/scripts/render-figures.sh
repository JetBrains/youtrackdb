#!/usr/bin/env bash
#
# render-figures.sh — deterministically render a blog article's figures to PNG.
#
# Usage: render-figures.sh <article-dir>
#   <article-dir>  the article directory, e.g. docs/blog/articles/<slug>
#
# What it does, given <article-dir>:
#   1. For each  <article-dir>/diagrams/*.mmd  it renders  <article-dir>/images/<name>.png
#      with mermaid-cli (Mermaid -> PNG).
#   2. If        <article-dir>/hero.svg        exists, it rasterises it to
#      <article-dir>/images/hero.png  at exactly 1200x630 (Medium/OG cover ratio).
#
# It writes ONLY under <article-dir>/images/. Tools are fetched on demand with `npx -y`
# (cached in ~/.npm after the first run); no repo-level dependency install is required.
#
set -euo pipefail

usage() {
  echo "usage: $(basename "$0") <article-dir>" >&2
  echo "  <article-dir>  article directory, e.g. docs/blog/articles/<slug>" >&2
}

# --- Argument validation -----------------------------------------------------
if [ "$#" -ne 1 ]; then
  usage
  exit 2
fi

article_dir="$1"

if [ ! -d "$article_dir" ]; then
  echo "error: not a directory: $article_dir" >&2
  usage
  exit 2
fi

# --- Keep mermaid-cli lean and offline ---------------------------------------
# mermaid-cli drives a headless Chrome via Puppeteer. If the caller has not pinned a
# browser, reuse a system Chrome/Chromium so Puppeteer does not download its own
# ~651 MB copy on first run. We probe common binaries in preference order and use the
# first one found; if none is found we leave PUPPETEER_EXECUTABLE_PATH unset, so
# mermaid-cli falls back to its own bundled/cached Chrome (still graceful).
if [ -z "${PUPPETEER_EXECUTABLE_PATH:-}" ]; then
  for chrome_bin in \
    /usr/bin/google-chrome-stable \
    google-chrome-stable \
    google-chrome \
    chromium \
    chromium-browser; do
    # command -v resolves a bare name via PATH and validates an absolute path is
    # executable; `|| true` keeps `set -e` from aborting when a candidate is absent.
    resolved="$(command -v "$chrome_bin" 2>/dev/null || true)"
    if [ -n "$resolved" ]; then
      export PUPPETEER_EXECUTABLE_PATH="$resolved"
      break
    fi
  done
fi

# Create <article-dir>/images/ lazily: only when the first artifact is about to be
# written, so a bare article dir with no sources leaves no empty images/ behind.
images_dir="$article_dir/images"
images_dir_ready=0
ensure_images_dir() {
  if [ "$images_dir_ready" -eq 0 ]; then
    mkdir -p "$images_dir"
    images_dir_ready=1
  fi
}

# --- 1. Mermaid diagrams: diagrams/*.mmd -> images/<name>.png ----------------
diagrams_dir="$article_dir/diagrams"
if [ -d "$diagrams_dir" ]; then
  # nullglob so an empty/absent match expands to nothing instead of a literal "*.mmd".
  shopt -s nullglob
  for mmd in "$diagrams_dir"/*.mmd; do
    base="$(basename "$mmd" .mmd)"
    png="$images_dir/$base.png"
    echo "rendering diagram: $mmd -> $png"
    ensure_images_dir
    # The package bin already IS mmdc; do not pass "mmdc" as a positional argument.
    npx -y @mermaid-js/mermaid-cli -i "$mmd" -o "$png"
    echo "artifact: $png"
  done
  shopt -u nullglob
else
  echo "no diagrams directory ($diagrams_dir); skipping Mermaid render"
fi

# --- 2. Hero / title card: hero.svg -> images/hero.png (1200x630) ------------
hero_svg="$article_dir/hero.svg"
if [ -f "$hero_svg" ]; then
  hero_png="$images_dir/hero.png"
  echo "rendering hero: $hero_svg -> $hero_png (1200x630)"
  ensure_images_dir
  # sharp-cli rasterises the SVG with system fonts. `--fit fill` forces exactly
  # 1200x630 (no crop, no letterbox); for a template authored at 1200x630 it is a
  # 1:1 pass-through. Deterministic, no headless browser involved.
  npx -y sharp-cli --input "$hero_svg" --output "$hero_png" resize 1200 630 --fit fill
  echo "artifact: $hero_png"
else
  echo "no hero.svg ($hero_svg); skipping hero render"
fi

echo "done."
