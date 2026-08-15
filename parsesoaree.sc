#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0-RC4

// Keeps a concert in Concerts.scala in step with the songbook's metadata file.
//
//   ./parsesoaree.sc                  fetch fresh, show what WOULD change (default: no writes)
//   ./parsesoaree.sc --apply          fetch fresh and write the change into Concerts.scala
//   ./parsesoaree.sc --print          just print the block, change nothing
//   ./parsesoaree.sc --file F         parse a local .tex instead of downloading (offline)
//   ./parsesoaree.sc --target Name    which generated block to fill (default Soaree01)
//   ./parsesoaree.sc --concerts F     path to Concerts.scala (default ./Concerts.scala)
//
// Preview by default, write only with --apply: this edits hand-written source, so it follows the
// same convention as `tt sub` and `deploy.sc --dry-run`. Nothing is written when the download
// fails, so a network problem can never leave a half-updated file.
//
// A PAUSE in the concert is written in the songbook as a Titel macro whose value is three or more
// dashes, and nothing else:
//
//     \newcommand{\PausEttTitel}{---}
//
// It needs no Bpm and no Sig, and it becomes a `Pause,` line in the generated block. The songbook
// is the only place the concert's ORDER exists, so a break in that order has to be expressible
// there; a pause written into Concerts.scala by hand lives inside the generated markers and is
// overwritten by the next sync.
//
// The block it owns is delimited by BEGIN/END GENERATED markers rather than found by parsing Scala.
// Balancing parentheses from `Seq(` would be the obvious alternative and is a trap: song titles
// contain parentheses, so a balancer would have to track string literals to avoid miscounting.
//
// The songbook repo is PRIVATE, and `tt forge` has no verb for repo file contents (only release
// assets), so the fetch goes through `gh api`, which carries the human's own GitHub auth.
//
// NB kept as .sc deliberately: build.sc sweeps every root-level *.scala into the app bundle, and
// a .sc file slips past that filter. The cost is Metals warning about `args$opt0` and friends --
// identifiers scala-cli generates around a script, harmless and not ours.

import java.nio.file.{Files, Path, Paths}

val Owner  = "bjornregnell"
val Repo   = "songbook-bjornregnell"
val InRepo = "songs/1-namn.tex"

def die(msg: String): Nothing =
  System.err.println(s"parsesoaree: $msg")
  sys.exit(2)

def note(msg: String): Unit = System.err.println(s"parsesoaree: $msg")

def optVal(name: String): Option[String] =
  val i = args.indexOf(name)
  if i >= 0 && i + 1 < args.length then Some(args(i + 1)) else None

val target      = optVal("--target").getOrElse("Soaree01")
val concertsAt  = Paths.get(optVal("--concerts").getOrElse("Concerts.scala")).toAbsolutePath
val doApply     = args.contains("--apply")
val printOnly   = args.contains("--print")

// ---- getting the source ----------------------------------------------------------------

/** Ask gh for the raw file. stderr passes through, so an auth failure is visible rather than
  * arriving as empty content. */
def download(): String =
  val cmd = Seq("gh", "api", s"repos/$Owner/$Repo/contents/$InRepo",
    "-H", "Accept: application/vnd.github.raw")
  note(s"fetching ${cmd.drop(2).head}")
  val pb = new ProcessBuilder(cmd*)
  pb.redirectError(ProcessBuilder.Redirect.INHERIT)
  val proc = pb.start()
  val text = String(proc.getInputStream.readAllBytes, "UTF-8")
  if proc.waitFor() != 0 then die("gh could not fetch the file — is `gh auth login` done?")
  if text.isBlank then die("gh returned nothing")
  text

val tex = optVal("--file") match
  case Some(path) =>
    val p = Paths.get(path)
    if !Files.isRegularFile(p) then die(s"no such file: $path")
    note(s"reading $path (no download)")
    Files.readString(p)
  case None => download()

// ---- reading LaTeX ---------------------------------------------------------------------

/** Drop each line's comment. TeX comments run from an unescaped % to end of line. */
def stripComments(s: String): String =
  s.linesIterator.map: line =>
    var cut = -1
    var i = 0
    while i < line.length && cut < 0 do
      if line(i) == '%' && (i == 0 || line(i - 1) != '\\') then cut = i
      i += 1
    if cut >= 0 then line.substring(0, cut) else line
  .mkString("\n")

/** The brace-balanced group starting at `open`, plus the index just past its closing brace.
  * Balanced rather than regex because a value may nest: {82~ {\small\it stick:123}}. */
def group(s: String, open: Int): Option[(String, Int)] =
  if open < 0 || open >= s.length || s(open) != '{' then None
  else
    var depth = 0
    var i = open
    var out = Option.empty[(String, Int)]
    while i < s.length && out.isEmpty do
      s(i) match
        case '{' => depth += 1
        case '}' =>
          depth -= 1
          if depth == 0 then out = Some((s.substring(open + 1, i), i + 1))
        case _ => ()
      i += 1
    out

case class Cmd(name: String, value: String)

/** Every \newcommand{\Name}{value} in order of appearance. */
def commands(s: String): Vector[Cmd] =
  val Marker = "\\newcommand"
  val out = Vector.newBuilder[Cmd]
  var i = s.indexOf(Marker)
  while i >= 0 do
    val found =
      for
        (rawName, afterName) <- group(s, s.indexOf('{', i + Marker.length))
        valueOpen             = s.indexOf('{', afterName)
        // only whitespace may sit between the two groups, else this is not one command
        if valueOpen >= 0 && s.substring(afterName, valueOpen).forall(_.isWhitespace)
        (value, afterValue)  <- group(s, valueOpen)
      yield (rawName.trim.stripPrefix("\\"), value.trim, afterValue)
    found match
      case Some((name, value, after)) =>
        out += Cmd(name, value)
        i = s.indexOf(Marker, after)
      case None => i = s.indexOf(Marker, i + Marker.length)
  out.result()

val cmds = commands(stripComments(tex))
if cmds.isEmpty then die("no \\newcommand definitions found — did the file change shape?")

// ---- the pieces we want ----------------------------------------------------------------

/** Signature macros, read from the file rather than hardcoded, so \Rakt and \Vals can change and a
  * new one just works: \newcommand{\Vals}{$\frac{3}{4}$} */
val fracOf = raw"\\frac\{(\d+)\}\{(\d+)\}".r
val signatures: Map[String, (Int, Int)] =
  cmds.flatMap: c =>
    fracOf.findFirstMatchIn(c.value).map(m => c.name -> (m.group(1).toInt, m.group(2).toInt))
  .toMap
if signatures.isEmpty then die("found no \\frac signature macros such as \\Rakt or \\Vals")

val byName = cmds.map(c => c.name -> c.value).toMap

/** Song keys in file order. `FullTitel` also ends in `Titel`, so it must be excluded or every song
  * would appear twice, once under a bogus key like `RymdresanFull`. */
val keys: Vector[String] =
  cmds.map(_.name)
    .filter(n => n.endsWith("Titel") && !n.endsWith("FullTitel"))
    .map(_.dropRight("Titel".length))
    .distinct

val firstNumber = raw"\d+".r

case class Song(key: String, title: String, bpm: Int, num: Int, den: Int):
  /** One bar, accent on the downbeat: the bang counts as a beat, so the dots are numerator - 1. */
  def pattern: String = "||:!" + "." * (num - 1) + ":||"

/** One entry of the concert, in songbook order. A pause is not a song with parts missing — it has
  * no tempo to leave out — so the two are kept apart all the way to the generated line. */
enum Entry:
  case Break
  case Tune(song: Song)

/** A title of nothing but dashes asks for a pause. Three at least, so a title that happens to be a
  * dash or an en-dash is not mistaken for one. */
def isPauseTitle(s: String): Boolean =
  val t = s.trim
  t.length >= 3 && t.forall(_ == '-')

val entries: Vector[Entry] = keys.flatMap: key =>
  def field(suffix: String): Option[String] = byName.get(key + suffix).filter(_.nonEmpty)

  def tune: Option[Entry] =
    for
      title <- field("Titel").orElse { note(s"$key: no Titel — skipped"); None }
      rawBpm <- field("Bpm").orElse { note(s"$key: no Bpm — skipped"); None }
      bpm <- firstNumber.findFirstIn(rawBpm).map(_.toInt)
               .orElse { note(s"$key: Bpm '$rawBpm' holds no number — skipped"); None }
      rawSig <- field("Sig").orElse { note(s"$key: no Sig — skipped"); None }
      sig <- signatures.get(rawSig.trim.stripPrefix("\\"))
               .orElse { note(s"$key: unknown signature macro '$rawSig' — skipped"); None }
    yield
      // A second number is the stick tempo for a later section; the concert wants the tempo you
      // COUNT IN, so the first number is the right one and nothing is being lost here.
      val all = firstNumber.findAllIn(rawBpm).toVector
      if all.size > 1 then
        note(s"$key: taking $bpm for verse and chorus; ${all.tail.mkString(", ")} is the later stick tempo")
      if title.contains("\\") then note(s"$key: Titel '$title' still contains LaTeX")
      Entry.Tune(Song(key, title, bpm, sig._1, sig._2))

  if field("Titel").exists(isPauseTitle) then
    note(s"$key: a pause")
    Some(Entry.Break)
  else tune

val songs  = entries.collect { case Entry.Tune(s) => s }
val breaks = entries.count(_ == Entry.Break)
if songs.isEmpty then die("no complete songs found")

def tally: String =
  s"${songs.size} of ${keys.size - breaks} songs" + (if breaks > 0 then s", $breaks pause(s)" else "")

// ---- the generated block ---------------------------------------------------------------

def quoted(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val block: Vector[String] =
  (s"  val $target = ${quoted(target)} -> Seq(" +:
    entries.map:
      case Entry.Break => "    Pause,"
      case Entry.Tune(s) =>
        s"    Song(title = ${quoted(s.title)}, ${s.bpm}, Signature(${s.num}, ${s.den}), Pattern(${quoted(s.pattern)})),"
  ) :+ "  )"

if printOnly then
  block.foreach(println)
  note(s"$tally; nothing written (--print)")
  sys.exit(0)

// ---- injection -------------------------------------------------------------------------

if !Files.isRegularFile(concertsAt) then die(s"no such file: $concertsAt")

val Begin = s"// BEGIN GENERATED $target"
val End   = s"// END GENERATED $target"

val lines = Files.readString(concertsAt).split("\n", -1).toVector
val beginAt = lines.indexWhere(_.trim.startsWith(Begin))
val endAt   = lines.indexWhere(_.trim.startsWith(End))
if beginAt < 0 || endAt < 0 then
  die(s"markers not found in $concertsAt — expected a line '$Begin' and one '$End'")
if endAt <= beginAt then die(s"'$End' comes before '$Begin' in $concertsAt")

val current = lines.slice(beginAt + 1, endAt)

if current == block then
  note(s"no change: $target already matches the songbook (${songs.size} songs)")
  sys.exit(0)

/** What is in `a` and not left over in `b`, counting duplicates: every Song line is distinct, but
  * "Pause," is not, and plain membership would hide a pause being added or dropped. */
def minus(a: Vector[String], b: Vector[String]): Vector[String] =
  var rest = b.toList
  a.filter: line =>
    val i = rest.indexOf(line)
    if i >= 0 then
      rest = rest.patch(i, Nil, 1)
      false
    else true

val gone = minus(current, block)
val came = minus(block, current)
println(s"$target would change in ${concertsAt.getFileName}:")
gone.foreach(l => println(s"  - $l"))
came.foreach(l => println(s"  + $l"))
note(s"${current.size} lines -> ${block.size} lines ($tally)")

if !doApply then
  note("nothing written — re-run with --apply to write it")
  sys.exit(0)

val updated = lines.take(beginAt + 1) ++ block ++ lines.drop(endAt)
Files.writeString(concertsAt, updated.mkString("\n"))
note(s"wrote $concertsAt — now run ./build.sc, the Concerts tests check every song plays")
