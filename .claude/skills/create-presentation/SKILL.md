---
name: create-presentation
description: "Generate a PPTX presentation explaining code changes on the current branch, with matplotlib diagrams and dark theme slides."
argument-hint: "[base-branch]"
user-invocable: true
---

Generate a PPTX presentation explaining the changes on the current branch.

## Arguments

If "$ARGUMENTS" is non-empty, use it as the target branch to compare against.
Otherwise, default to `origin/develop`.

## Task

You are creating a technical presentation for a team of developers. The output
is a `.pptx` file in the project root that the user can open in Google Slides.

### Step 1 — Investigate the branch

1. Run `git log --oneline <base>..HEAD` to list all commits on this branch.
2. Run `git diff --name-only <base>..HEAD` to find changed source files.
3. Separate generated files (e.g. `sql/parser/YouTrackDBSql*.java`) from hand-written code.
4. Read every non-generated changed source file to understand the full picture.
5. Read commit messages for motivation and design rationale.

### Step 2 — Plan the slides

Create a slide outline (15–25 slides) covering:

- **Title slide** with branch name and issue ID (extracted from branch name).
- **Problem statement** — what motivated this change.
- **Solution overview** — high-level summary with a phase/flow diagram.
- **Architecture slides** — new files, data structures, on-disk formats.
- **Algorithm slides** — step-by-step flowcharts for key algorithms.
- **Code path slides** — how existing code is modified and where new code hooks in.
- **Configuration** — new parameters, defaults, tuning guidance.
- **Safety properties** — crash safety, concurrency, error handling.
- **Design decisions** — key trade-offs and rationale.
- **Scope/limitations** — what is NOT covered.
- **Q&A slide**.

### Step 3 — Set up Python environment

```bash
python3 -m venv .tmp/pptx-venv
.tmp/pptx-venv/bin/pip install python-pptx matplotlib
```

If the venv already exists, skip creation and just verify imports work.

### Step 4 — Generate diagrams with matplotlib

For every diagram (flowcharts, architecture, data flow, record layouts, before/after
comparisons), generate a **matplotlib figure** rendered to a PNG `BytesIO` stream.

Follow these conventions:

#### Color theme (dark, matches slide background)

```python
MPL_BG      = '#121224'   # figure/axes background
MPL_FG      = '#e0e0e0'   # default text
MPL_BLUE    = '#64B5F6'   # titles, primary boxes
MPL_LBLUE   = '#81D4FA'   # secondary highlights
MPL_GREEN   = '#A5D6A7'   # success/safe elements
MPL_ORANGE  = '#FFB74D'   # accent/new/warning
MPL_RED     = '#EF5350'   # delete/danger
MPL_BOX_BG  = '#1a2a44'   # box fill
MPL_BOX_BORDER = '#3a5a8a'  # box stroke
```

#### Diagram building blocks

Use `matplotlib.patches.FancyBboxPatch` for boxes and `ax.annotate` with
`arrowprops` for arrows. Helper pattern:

```python
def make_box(ax, x, y, w, h, text, facecolor, edgecolor, text_color,
             fontsize=11, weight='normal'):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.15",
                         facecolor=facecolor, edgecolor=edgecolor, linewidth=1.5)
    ax.add_patch(box)
    ax.text(x + w/2, y + h/2, text, ha='center', va='center',
            fontsize=fontsize, color=text_color, weight=weight, family='monospace', wrap=True)

def make_arrow(ax, x1, y1, x2, y2, color='#81D4FA', style='->', lw=2):
    ax.annotate('', xy=(x2, y2), xytext=(x1, y1),
                arrowprops=dict(arrowstyle=style, color=color, lw=lw))
```

#### Figure setup

- Always use `matplotlib.use('Agg')` before importing pyplot.
- Use `fig.savefig(buf, format='png', dpi=200, bbox_inches='tight', facecolor=fig.get_facecolor())`.
- Turn off axes: `ax.axis('off')`.
- Use `figsize` appropriate for widescreen (e.g. `(12, 5)` for wide diagrams, `(10, 7)` for tall ones).

#### What NOT to do for diagrams

- Do NOT use Pillow `ImageDraw.text()` to render ASCII art — the default bitmap
  font has broken glyph coverage for box-drawing characters (U+250x) and the
  output is unreadable in presentations.
- Do NOT use mermaid-py or mermaid-cli — they require network access or a
  headless browser, neither of which is reliably available.
- Do NOT use the graphviz Python package — it requires the `dot` binary which
  may not be installed.
- matplotlib is the ONLY reliable local renderer. Use it for ALL diagrams.

### Step 5 — Build the PPTX

Use `python-pptx` to assemble slides. Follow these conventions:

#### Slide dimensions

```python
prs.slide_width = Inches(13.333)   # widescreen 16:9
prs.slide_height = Inches(7.5)
```

#### PPTX color theme

```python
BG_COLOR        = RGBColor(0x1B, 0x1B, 0x2F)  # slide background
TITLE_COLOR     = RGBColor(0x64, 0xB5, 0xF6)  # slide titles
HEADING_COLOR   = RGBColor(0x81, 0xD4, 0xFA)  # subtitles
TEXT_COLOR      = RGBColor(0xE0, 0xE0, 0xE0)  # body text
ACCENT_COLOR    = RGBColor(0xFF, 0xB7, 0x4D)  # bold highlights
CODE_BG         = RGBColor(0x12, 0x12, 0x24)  # code block fill
TABLE_HEADER_BG = RGBColor(0x1A, 0x3A, 0x5C)  # table header row
TABLE_ROW_BG    = RGBColor(0x15, 0x15, 0x2A)  # odd table rows
TABLE_ALT_BG    = RGBColor(0x1D, 0x1D, 0x35)  # even table rows
BORDER_COLOR    = RGBColor(0x3A, 0x3A, 0x5C)  # code block border
```

#### Slide structure

- Use `prs.slide_layouts[6]` (blank layout) for all slides.
- Set background: `slide.background.fill.solid(); slide.background.fill.fore_color.rgb = BG_COLOR`.
- Title: 32pt, bold, TITLE_COLOR, positioned at `(0.5", 0.3")`.
- Body text: 17–18pt, TEXT_COLOR. Use `**bold**` parsing to apply ACCENT_COLOR.
- Code blocks: `ROUNDED_RECTANGLE` shape with CODE_BG fill, Consolas 13pt, green text.
- Tables: styled with alternating row colors, header row in TABLE_HEADER_BG.
- Diagrams: inserted as PNG images via `slide.shapes.add_picture(stream, left, top, width)`.

#### Code organization

Write the entire generator as a **single Python script** saved to `.tmp/gen-presentation.py`.
Structure it as:

1. Imports and theme constants.
2. matplotlib diagram generator functions (one per diagram).
3. PPTX helper functions (`set_slide_bg`, `add_title`, `add_bullet_text`, `add_code_block`, `add_table`, `add_image`).
4. Slide-by-slide assembly.
5. `prs.save(output_path)` at the end.

Run with: `.tmp/pptx-venv/bin/python .tmp/gen-presentation.py`

### Step 6 — Output

Save the PPTX to the project root as `<branch-name>-presentation.pptx`.
Tell the user the file path and total slide count.
Also leave the Python script at `.tmp/gen-presentation.py` so the user
can tweak and regenerate.

## Quality checklist

- [ ] Every diagram is a matplotlib-rendered PNG — no ASCII art in the final PPTX.
- [ ] Diagrams use the dark color theme and render at 200 DPI.
- [ ] All text is legible (minimum 10pt in diagrams, 13pt in code blocks, 17pt in body).
- [ ] Slide count is between 15 and 25.
- [ ] The presentation tells a coherent story: problem → solution → details → safety → decisions.
- [ ] No broken images or placeholder text.
