# Design

The approved UI direction, and the notes from porting it to Compose.

`index.html` is a self-contained, clickable prototype of the shipped app: the
four-state connect control, the four-tab layout, both themes, the endpoint
scanner and the diagnostics report. Open it directly in a browser -- the fonts
are inlined, so nothing is fetched.

It is the design reference, not a spec. Where the prototype and the app differ,
the app is right: it only shows values the engine actually produces, so the
transport race and the latency sparkline in early drafts were dropped rather
than fabricated. `PORT-STATUS.md` records those decisions along with what was
verified in the Rust engine and what is still outstanding.

## Working on it

```bash
npm install
node build.mjs      # inline the fonts -> index.html
node capture.mjs    # render frames/*.png at 390x844
node measure.mjs    # verify tab-bar centring against a real render
```

`make-android-fonts.py` regenerates `app/src/main/res/font/`. Inter ships as a
variable woff2 and Android res/font wants plain TTF, so each weight is
instantiated as a static instance. Needs `fonttools` and `brotli`.

## src.html is deliberately pure ASCII

The published page cannot declare its own charset, and without one the bytes
get decoded as single-byte and every multi-byte character becomes mojibake.
Each of `-`, `.` and `...` uses the escape its context understands: HTML
entities in markup, `\uXXXX` in script, CSS escapes in stylesheets. This must
print `0`:

```bash
node -e "console.log([...require('fs').readFileSync('design/src.html','utf8')].filter(c=>c.codePointAt(0)>127).length)"
```

## Preview parameters

`index.html` accepts query parameters so any frame can be reproduced exactly:

- `?state=idle|work|live|fail`
- `?screen=home|routes|endpoint|traffic|settings|diagnostics`
- `?ep=custom|scanning|scanned`
- `?ui=system|light|dark`
- `?face=inter|jakarta|geist`
- `?adv=1` -- advanced controls expanded
- `?scroll=N`
- `?frame=1` -- device only at 390x844, animations frozen for capture

All connection and network values in the prototype are simulated.
