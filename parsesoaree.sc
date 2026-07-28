#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0-RC4

// Extracts a ProntoPop concert from the songbook's metadata file.
//
//   ./parsesoaree.sc                 fetch a fresh copy from GitHub and print Scala source
//   ./parsesoaree.sc --file F        parse a local file instead (offline, for iterating)
//   ./parsesoaree.sc --name Soaree   name the emitted concert (default: Soaree)
//
// The songbook repo is PRIVATE, and `tt forge` has no verb for repo file contents (only release
// assets), so the fetch goes through `gh api`, which carries the human's own GitHub auth.
//
// stdout is Scala source and nothing else, so it can be read or pasted straight into Concerts.scala.
// Anything questionable in the source file is reported on stderr instead.
//
// NB kept as .sc deliberately: build.scala sweeps every root-level *.scala into the app bundle, and
// a .sc file slips past that filter. The cost is Metals warning about `args$opt0` and friends —
// identifiers scala-cli generates around a script, harmless and not ours.

val Owner  = "bjornregnell"
val Repo   = "songbook-bjornregnell"
val InRepo = "songs/1-namn.tex"

def die(msg: String): Nothing =
  System.err.println(s"parsesoaree: $msg")
  sys.exit(2)

def warn(msg: String): Unit = System.err.println(s"parsesoaree: $msg")

def optVal(name: String): Option[String] =
  val i = args.indexOf(name)
  if i >= 0 && i + 1 < args.length then Some(args(i + 1)) else None

// ---- getting the source ----------------------------------------------------------------

/** Ask gh for the raw file. stderr passes through, so an auth failure is visible rather than
  * arriving as empty content. */
def download(): String =
  val cmd = Seq("gh", "api", s"repos/$Owner/$Repo/contents/$InRepo",
    "-H", "Accept: application/vnd.github.raw")
  warn(s"fetching ${cmd.drop(2).head}")
  val pb = new ProcessBuilder(cmd*)
  pb.redirectError(ProcessBuilder.Redirect.INHERIT)
  val proc = pb.start()
  val text = String(proc.getInputStream.readAllBytes, "UTF-8")
  if proc.waitFor() != 0 then die("gh could not fetch the file — is `gh auth login` done?")
  if text.isBlank then die("gh returned nothing")
  text

val tex = optVal("--file") match
  case Some(path) =>
    val p = java.nio.file.Paths.get(path)
    if !java.nio.file.Files.isRegularFile(p) then die(s"no such file: $path")
    warn(s"reading $path (no download)")
    java.nio.file.Files.readString(p)
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

val songs: Vector[Song] = keys.flatMap: key =>
  def field(suffix: String): Option[String] = byName.get(key + suffix).filter(_.nonEmpty)

  val parsed =
    for
      title <- field("Titel").orElse { warn(s"$key: no Titel — skipped"); None }
      rawBpm <- field("Bpm").orElse { warn(s"$key: no Bpm — skipped"); None }
      bpm <- firstNumber.findFirstIn(rawBpm).map(_.toInt)
               .orElse { warn(s"$key: Bpm '$rawBpm' holds no number — skipped"); None }
      rawSig <- field("Sig").orElse { warn(s"$key: no Sig — skipped"); None }
      sig <- signatures.get(rawSig.trim.stripPrefix("\\"))
               .orElse { warn(s"$key: unknown signature macro '$rawSig' — skipped"); None }
    yield
      val all = firstNumber.findAllIn(rawBpm).toVector
      if all.size > 1 then warn(s"$key: Bpm '$rawBpm' holds ${all.size} numbers, using ${all.head}")
      if title.contains("\\") then warn(s"$key: Titel '$title' still contains LaTeX")
      Song(key, title, bpm, sig._1, sig._2)

  parsed

if songs.isEmpty then die("no complete songs found")

// ---- emit ------------------------------------------------------------------------------

def quoted(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val name = optVal("--name").getOrElse("Soaree")

println(s"  val $name = ${quoted(name)} -> Seq(")
songs.foreach: s =>
  println(s"    Song(title = ${quoted(s.title)}, ${s.bpm}, Signature(${s.num}, ${s.den}), Pattern(${quoted(s.pattern)})),")
println("  )")

warn(s"${songs.size} of ${keys.size} songs emitted")
