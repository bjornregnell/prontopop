# ProntoPop design notes

Working notes for the built-in-concerts work, written 2026-07-25. Not a spec — `PRD.md` is the
spec; this is the how and why.

## Status

Built-in concerts are IMPLEMENTED and deployed (`a34c11a`). The two actionable TODOs are done and
the initialization bug below is fixed; the reasoning is kept because it explains why the code looks
the way it does. `PRD.md` carries `Goal: easyStart` and `Feature: builtInConcerts`, parsed and
linted clean.

## Standing decisions

- **Bleeding-edge Scala, deliberately.** The project tracks Scala 3.9.0 release candidates
  (currently 3.9.0-RC4) everywhere: the app, `build.sc`, `deploy.sc` and the README. scala-cli
  1.15.0 cannot post-process TASTY from an RC and prints a notice saying so on every script compile.
  That notice is ACCEPTED, not a defect to chase: it only cleans up source paths inside TASTY files,
  which cannot affect a JS bundle, and scala-cli will catch up. Do not "fix" it by pinning back to a
  stable Scala. (BR, 2026-07-25.)
- **Scratch files live in the repo's gitignored `tmp/`**, never `/tmp`, which a reboot clears —
  learned the hard way when a verification script vanished mid-task. `build.sc` reads only
  root-level `*.scala` and `deploy.sc` stages an explicit list, so `tmp/` reaches neither the
  bundle nor the server.

- **Syncing the concert from the songbook is opt-in (`./build.sc --sync`), never automatic.**
  `parsesoaree.sc` pulls `songs/1-namn.tex` from the private songbook repo and rewrites the
  generated `Soaree01` block in `Concerts.scala`. It is not part of an ordinary build, because
  `deploy.sc` calls `build.sc`: an automatic sync would let a deploy publish whatever the songbook
  happened to say at that moment, unreviewed. It would also make every build need the network — a
  poor trade for an app whose point is working offline on a stage — and let a build rewrite source
  nobody touched. On its own, `parsesoaree.sc` previews and writes only with `--apply`.

- **The file extension carries the rule: `*.scala` is APP source, `*.sc` is tooling.** `build.sc`
  sweeps every root-level `*.scala` into the bundle, so the scripts are `.sc` and are excluded
  structurally rather than by an exclusion list somebody has to remember to extend — forgetting
  would put JVM-only code into `main.js`. The price is Metals warning about `args$opt0` and
  friends, identifiers scala-cli generates around a script; accepted, and not ours to fix.

- **Lean and mean on dependencies; handroll when handrolling is reasonable.** What ships is Laminar
  and scalajs-dom, and nothing else. munit is declared a `test.dep`, so it is absent from `main.js`.
  A new dependency has to earn its place against the effort of writing the small thing ourselves,
  and so far writing it ourselves has kept winning:

  - the click is **synthesized** with `AudioContext` oscillators rather than pulling in an audio
    library or shipping sound files;
  - the theme dropdown is **~40 lines of Scala** in `Theme.scala` rather than vendoring
    genscalator's `design.js`, which also keeps it in one language;
  - the design-language CSS is **emitted from Scala** in `Styles.scala`, so there is no separate
    stylesheet asset to serve or drift;
  - the fonts are **copied into `fonts/`**, so the app depends on no sibling deploy;
  - persistence is **tab-separated lines in local storage**, not a JSON library.

  The rule of thumb: if the handrolled version is small enough to read in one sitting and we
  understand every line, prefer it. Reach for a dependency when the alternative is re-implementing
  something genuinely hard — a parser generator, a crypto primitive, a layout engine — not merely
  tedious. If a browser API is missing from scalajs-dom, handroll the small facade.

- **The cue is what plays.** One rule for the whole transport: `Play` starts the cued song and turns
  into a red `Stop` while it runs, `Next` and `Prev` only walk the cue, and the arrow keys and the
  cue buttons in the table do the same walking. This replaced a `Play next` button that meant "the
  song after the cue" except before anything had played, when it meant "the first song" — a rule
  nobody should have to remember while counting in a band. When adding a control, ask what it does
  to the cue; if the answer is "it plays something else", it is probably the wrong control.
  (BR, 2026-08-15.)

- **Loading never silently discards edits.** Choosing from the dropdown loads at once — there is no
  `Load` button, since it only asked the same question twice — but any edit since the last load or
  save raises a flag, shown as `unsaved changes` beside `Save` so the warning is never the first
  news of it. Loading while that flag is up puts the question on screen instead: `Cancel` (focused,
  so the reflex answers keep the table) or `Discard and load`. The dropdown is `controlled`, which
  matters more than it looks: without it a refused choice would leave the dropdown naming one
  concert while the table held another. The flag is for the whole table, not per song, because what
  a load overwrites is all of it.

  This settles half of the old TODO-A gap below — what happens when someone edits a loaded built-in.
  Editing is now visibly unsaved work; saving under the same title still shadows the built-in, which
  remains a deliberate choice. (BR, 2026-08-15.)

## The bug that shaped `Concerts.scala` (fixed)

VERIFIED at the time, not suspected — running a main that touched `Concerts.all` on the JVM gave:

```
java.lang.NullPointerException: Cannot invoke "scala.Tuple2._1()" because "elem" is null
	at prontopop.Concerts$.<clinit>(Concerts.scala:6)
```

`val all` is declared ABOVE `Example01` and `Soaree01`. Object vals initialize in source order, so
both are still `null` when `Map(...)` runs, and `Map.apply` dereferences them. The app would show a
blank page the moment either TODO is implemented.

Three fixes, in preference order:

1. **Derive `all` from an ordered `Seq` declared last** — fixes the ordering problem below at the
   same time, so this is the one to take.
2. `lazy val all` — a one-word fix; correct because nothing touches `all` during initialization.
   (A scratchpad check of this was written but never run; the box seized first.)
3. Move `val all` below the two vals — works, but silently breaks again if anyone reorders.

## Second issue: `Map` loses the concert order

`Map[Title, Concert]` preserves insertion order only up to four entries; a fifth turns it into a
hash map and the dropdown order starts scrambling. The concert list is presentation, so it wants a
deterministic order. Combined with fix 1 above:

```scala
object Concerts:
  import Model.*

  val Example01 = "Example01" -> Seq(...)
  val Soaree01  = "Soaree01"  -> Seq(...)

  /** Source of truth, in the order they should be offered. */
  val ordered: Seq[(Title, Concert)] = Seq(Example01, Soaree01)

  /** Lookup view; safe because `ordered` is already initialized above. */
  val all: Map[Title, Concert] = ordered.toMap

  /** What the app opens on, so no caller has to hardcode a key that might vanish. */
  val startup: Concert = Example01._2
```

`Concerts.all("Example01")` as written in the TODO throws on a missing key; `Concerts.startup`
removes both the string literal and the exception.

## The TODOs

### A. `listSaved` should also offer the built-in concerts (View.scala:19)

> also append for each Concerts.all if not already saved under those titles

Saved concerts come first, built-ins are appended, and a saved concert with the same title hides the
built-in. Precedence falls out naturally if `load` looks in local storage first:

```scala
def listConcerts(): Vector[String] =
  val saved = listSaved()                        // already sorted
  saved ++ Concerts.ordered.map(_._1).filterNot(saved.contains)

def concertNamed(name: String): Option[Vector[SongRow]] =
  savedConcert(name)                             // local storage wins
    .orElse(Concerts.all.get(name).map(_.map(toRow).toVector))
```

Open question for BR: should a built-in be LABELLED as such in the dropdown, e.g.
`Example01 (built-in)`? Argument for: a user cannot otherwise tell why it reappears after they
delete it, or why editing it does not stick until they Save. Argument against: noise. Cheap either
way — the option's value stays the bare title, only the label changes.

### B. Open on a built-in concert instead of hardcoded rows (View.scala:27)

> this should use Concerts.all("Example01")

```scala
val songsVar = Var(Concerts.startup.map(toRow).toVector)
```

Note this deliberately changes what a visitor first sees: today the app opens on BR's real songs
(Rymdresan, Hopp om en ofri, now also in `Soaree01`), and after this it opens on the neutral
`Example01`. That is the right call for a public URL — a stranger should not land in someone's
private setlist — and it is what `Goal: easyStart` in the PRD is about.

Second open question: should `concertNameVar` be pre-filled with the startup concert's name, so the
Concert Name field agrees with what is in the table? Probably yes.

### C. `Error.ParseError` — "add more errors when needed" (Model.scala:43)

Not actionable. It is a placeholder marking an extension point, not a task; leave it until a second
error kind actually shows up (a bad bpm and a bad signature are both parsed in `View` as plain
strings today, so they are the obvious candidates when the model absorbs them).

## The real work under A and B: `Song` ↔ `SongRow`

Both TODOs need one thing the codebase does not have yet: a conversion between the typed model and
the string-valued UI row. `Model.Song` holds `bpm: Double`, `signature: Signature`,
`pattern: Pattern`; `SongRow` holds four Strings, deliberately, so the user can type freely and get
a parse error rather than a rejected keystroke.

Two frictions to handle:

- **`Double` formatting.** `120.0.toString` is `"120.0"`, which would show up in the BPM field and
  look wrong. Format whole numbers without the decimal.
- **`SongRow` is currently declared INSIDE `createProntoPopLandingPage`**, so nothing outside can
  name the type. Lift it to top level (its own file, or the top of `View.scala`) before writing
  conversions.

Suggested shape, with the parsing direction reusing what `togglePlay` already does by hand:

```scala
case class SongRow(id: Int, title: String, bpm: String, sign: String, pattern: String)

def toRow(id: Int, s: Song): SongRow                  // total; formats for display
extension (r: SongRow) def toSong: Either[String, Song]  // partial; the existing bpm/sig/pattern parse
```

Extracting `toSong` is worth it on its own: `togglePlay` currently inlines that whole
`for`-comprehension, and `save` would want the same validation before writing a concert to storage.
Suggested home: `ModelOps.scala` for `toSong` (it is model logic), `View.scala` for `toRow` (it is
presentation).

## Naming collision worth fixing while nearby

`Model.Sound` (the new `trait Sound(val isOneOff: Boolean)`) and the top-level `Sound` object in
`Sound.scala` (the WebAudio player) now share a name. It compiles — the trait contributes only a
type name, so `Sound.initWebSound()` still resolves to the object — but it is a trap for a reader,
and it would break the day the trait gains a companion. Renaming one of them is cheap now and
awkward later; the player reads well as `Audio` or `Player`.

Also: `isOneOff` is not yet consulted anywhere. The player treats every event as a short decaying
blip, which is right for drums and wrong for a sustained instrument, so the flag is a marker for
note-off scheduling that does not exist yet.

## Are the TODOs unambiguous enough to build from?

A verdict per TODO, since that was the question asked.

**A (built-ins in the dropdown): yes, with two small gaps.** The rule "append, unless already saved
under that title" fixes both the order and the precedence. What it does not say is (i) whether a
built-in should be visibly marked as one, and (ii) what should happen when someone edits a loaded
built-in — today nothing persists until Save, and saving under the same title shadows the built-in
forever, which is coherent but should be a deliberate choice rather than an accident.

**B (open on a built-in): yes, with one gap and one hazard.** The gap is whether the Concert Name
field should pre-fill to match. The hazard is that `Concerts.all("Example01")`, written literally,
throws on a missing key — hence the `Concerts.startup` suggestion above.

**C (more error kinds): no, and that is fine.** "When needed" is a marker, not a task. Nothing to
decide until a second error kind exists.

## What has been verified, and what has not

- **VERIFIED** by re-running `tmp/CheckConcerts.scala` after the fix: `Concerts.all` initializes,
  `titles` keeps the declared order, `SongRow.from` renders a whole bpm as `120` rather than `120.0`,
  and the round trip through `toSongAndBars` yields playable bars.
- **VERIFIED**: the app builds clean on 3.9.0-RC4 after a `scala-cli clean`, `PRD.md` parses and
  lints clean, and `deploy.sc` runs on RC4 (dry run).
- **The deprecation warning is fixed**, not merely diagnosed: it was `cls.toggle("playing")` in
  `View.scala`, deprecated in Laminar 17.0.0-M1 in favour of plain `cls("playing")`.
- **NOT verified by machine:** everything about how it actually behaves in a browser. Loading a
  built-in, the `(built-in)` labels, and saving over a built-in have not been clicked through — the
  checks above are JVM-level, and the app itself only ever runs as Scala.js in a browser. There are
  no browser tests at all in this project.

## Pinned investigation: how to test the click's timing

Timing is the one thing a metronome exists to get right, and `Goal: metronomeLivePerformance` rests
entirely on it. WebAudio is silent in headless Chrome, so for a long while the only verification the
click had was BR listening to it — a drift or a stutter would have survived every test we owned and
shown up on a stage.

**Step 1 is DONE (2026-07-25): the arithmetic is extracted and tested.** `Timing.scala` holds the
whole schedule computation as pure functions of numbers — laying bars end to end, the cursor that
wraps into the next loop, the time a beat falls, and `due`, which answers what lies inside a
lookahead window. `WebAudioPlayer` keeps only the audio clock and the graph, and its `tick` is three
lines. `tests/Timing.test.scala` covers it on the JVM with no browser and no new dependency,
including the property the whole design rests on: **a beat's time is computed from its index, never
accumulated**, so rounding cannot pile up. The suite asserts the 10,000th beat at a deliberately
non-round 137 bpm lands exactly where multiplication says, and that consecutive gaps stay exactly one
beat across loop boundaries. It also pins two ways the old `tick` could have spun forever — an empty
schedule and a zero tempo — which the previous code would have hung on.

What is still NOT verified: that the audio graph turns those numbers into sound at those moments.
Two approaches remain, should a real timing complaint ever appear.

2. **Render offline and inspect the samples.** `OfflineAudioContext` renders an audio graph to a
   buffer deterministically and faster than real time, with no output device involved. Rendering a
   few seconds of a pattern and locating the click transients in the buffer would verify the actual
   audio graph, not just our arithmetic. Runs in headless Chrome, needs no new dependency, but does
   need a way to run assertions inside the page.

3. **Drive a real page and capture scheduled times.** Substitute a recording stand-in for
   `AudioContext` that logs every `osc.start(t)`, then compare the log against expectations. The
   most faithful, and the most machinery: it needs in-browser test execution (Scala.js munit on Node
   with a DOM shim, or the DevTools protocol via Puppeteer) — which means a new dependency, so it
   should wait until there is a real complaint to chase.

Given step 1, a timing bug now almost certainly means the browser's own scheduling, not ours.

## Project gotchas worth not rediscovering

- **The CSS now lives inside `main.js`**, so a cached script means cached styling. A plain refresh
  can show stale layout; hard-refresh before concluding a style change failed. This cost real
  confusion once already.
- **The song table is deliberately non-reflowing.** Fixed character widths mean a narrow window
  scrolls sideways instead of rearranging — on a phone the Pattern and Remove columns are off to
  the right. That is the intent, not a bug.
- **The design-language tokens in `Styles.scala` are vendored by hand**, and their upstream is
  GENERATED by genscalator's `DesignLang.scala`. They can drift silently; re-sync by copying.
- **The theme dropdown is `position: fixed`**, so on a narrow viewport it floats over content while
  the table scrolls under it.
- **The fonts travel with the app** (`fonts/`, 184K) rather than being fetched from the genscalator
  deploy. If text ever renders in plain system monospace, font loading is broken — the app should
  never depend on a sibling deploy again.
