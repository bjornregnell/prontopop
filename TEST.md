# Testing ProntoPop

Two layers, because the app is split that way: the logic is platform-neutral Scala and gets munit
suites; everything else only exists inside a browser and gets a headless-Chrome check.

## Layer 1: munit suites over the logic

```bash
./build.sc              # tests first, then package — the tests gate the bundle
./build.sc --test-only  # just the suites
./build.sc --no-test    # skip them, for quick iteration
```

Or drive scala-cli directly, which is what `build.sc` does:

```bash
scala-cli test Model.scala ModelOps.scala SongRow.scala Concerts.scala tests/*.test.scala
```

**Suites live in `tests/` and MUST be named `*.test.scala`** — that suffix is how scala-cli tells a
test source from an ordinary one. A file named `ModelOpsTests.scala` compiles as a normal source and
its tests silently never run.

They run on the **JVM**, over only the four browser-free sources. `View`, `Sound`, `Styles` and
`Theme` all depend on scalajs-dom and cannot compile for the JVM at all, so they are deliberately
out of scope here. munit is declared as a `test.dep`, so it never reaches `main.js`.

What is covered:

- `tests/ModelOps.test.scala` — the pattern DSL: one bar per separator, the three hit velocities and
  their ordering, `_` as true silence, beat positions, the signature carried on each bar, optional
  but paired loop markers, ignored whitespace, the smart-punctuation ellipsis, and each parse error
  with the position it points at.
- `tests/SongRow.test.scala` — the string row and the trip to the model and back: a whole bpm showing
  as `120` and not `120.0`, signature formatting and parsing, round-tripping a song, and every way a
  typed row can be rejected (bad bpm, non-positive bpm, bad signature, pattern disagreeing with the
  signature). Also that the default row from **Add song** plays as-is.
- `tests/Concerts.test.scala` — the built-in concerts, including a **regression guard**: `all` once
  threw a `NullPointerException` at initialization because it was declared above the vals it names,
  and object vals initialize in source order. Also that the declared order survives (a `Map` would
  lose it past four entries), that titles are unique, and — the useful one — that **every built-in
  song actually plays**, so a broken example cannot ship.

## Layer 2: the app itself, in headless Chrome

The suites say nothing about whether the page renders. For that, serve the directory and drive
Chrome. Start the server in one shell:

```bash
tt serv .          # http://127.0.0.1:8000
```

**Dump the rendered DOM** — this runs the real Laminar app, so it proves the app mounts and shows
what it built:

```bash
google-chrome --headless=new --disable-gpu --virtual-time-budget=8000 \
  --dump-dom http://127.0.0.1:8000/
```

The `--virtual-time-budget` matters: without it Chrome may dump before the app has mounted.

Useful for checking structure — that the concerts dropdown really lists `Example01 (built-in)`, that
there is one `.songrow` per song, that the theme select has all five options.

**Screenshot it** — and then actually look at the image:

```bash
google-chrome --headless=new --disable-gpu --window-size=1200,800 --virtual-time-budget=8000 \
  --screenshot=tmp/shot.png http://127.0.0.1:8000/
```

This is not optional polish. A DOM dump **cannot** show field contents, because Laminar sets `value`
as a DOM property and properties never appear in serialized HTML — every input looks empty. Nor can
it show clipping, colour or layout. The screenshot is what caught the pattern column being too
narrow, silently truncating a 4/4 pattern's closing `:||` on screen.

**Drive it** — a probe page presses the app's own buttons and prints what happened, and
`--dump-dom` collects the printout. No DevTools protocol and no new dependency: the page loads
`main.js` into its own `#app`, waits for the mount, then calls `.click()` and dispatches real
events, exactly as a person's finger would.

```html
<!-- tmp/probe.html, served alongside the app -->
<div id="app"></div>
<script src="/main.js"></script>
<script>
  setTimeout(function () {
    document.querySelectorAll('button.cue')[5].click()
    setTimeout(function () {
      var out = document.createElement('pre')
      out.id = 'probe'
      out.textContent = 'cue now at: ' + /* read it back out of the DOM */ ''
      document.body.appendChild(out)
    }, 50)
  }, 500)
</script>
```

```bash
google-chrome --headless --disable-gpu --virtual-time-budget=6000 \
  --dump-dom http://127.0.0.1:8000/tmp/probe.html | grep 'pre id'
```

The two `setTimeout` waits are load-bearing: the first lets Laminar mount, and the second lets it
render the consequence of the click before it is read back. Add `--autoplay-policy=no-user-gesture-required`
when the probe presses Play, or the AudioContext stays suspended.

This is how the cue buttons, the transport, and the unsaved-changes guard were checked — each of
them a claim about behaviour that a screenshot cannot make. Examples live in `tmp/`.

## What is still not tested

Honestly, so nobody assumes otherwise:

- **Interaction is exercised only where a probe was written.** The probes cover the cue column, the
  Play/Stop transport with Next and Prev, and choosing a concert while there are unsaved edits.
  Saving, removing, theme switching and the volume slider are still only ever loaded, never pressed.
- **A probe proves the wiring, not the feel.** It can show that pressing Play sets the button to
  Stop and names the song; it cannot show that the button is reachable with a thumb, or that the
  colour reads across a stage. Those still want the screenshot and a human.
- **No audio is verified.** WebAudio produces no output in headless Chrome, so scheduling accuracy —
  the thing a metronome lives or dies by — is only ever checked by ear.
- **Local storage behaviour is untested.** Saving a concert, and a saved concert shadowing a built-in
  of the same title, are reasoned about but never executed.
- **Only one theme is ever seen.** A screenshot captures whatever `prefers-color-scheme` gives
  headless Chrome; the other four themes go unlooked-at.
