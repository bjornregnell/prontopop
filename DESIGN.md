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
  (currently 3.9.0-RC4) everywhere: the app, `build.scala`, `deploy.scala` and the README. scala-cli
  1.15.0 cannot post-process TASTY from an RC and prints a notice saying so on every script compile.
  That notice is ACCEPTED, not a defect to chase: it only cleans up source paths inside TASTY files,
  which cannot affect a JS bundle, and scala-cli will catch up. Do not "fix" it by pinning back to a
  stable Scala. (BR, 2026-07-25.)
- **Scratch files live in the repo's gitignored `tmp/`**, never `/tmp`, which a reboot clears —
  learned the hard way when a verification script vanished mid-task. `build.scala` reads only
  root-level `*.scala` and `deploy.scala` stages an explicit list, so `tmp/` reaches neither the
  bundle nor the server.

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
  lints clean, and `deploy.scala` runs on RC4 (dry run).
- **The deprecation warning is fixed**, not merely diagnosed: it was `cls.toggle("playing")` in
  `View.scala`, deprecated in Laminar 17.0.0-M1 in favour of plain `cls("playing")`.
- **NOT verified by machine:** everything about how it actually behaves in a browser. Loading a
  built-in, the `(built-in)` labels, and saving over a built-in have not been clicked through — the
  checks above are JVM-level, and the app itself only ever runs as Scala.js in a browser. There are
  no browser tests at all in this project.

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
