#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0-RC4

// Builds the prontopop app: runs the munit tests, then links the app's *.scala files into main.js
// next to index.html. Run from the project root:
//
//   ./build.sc              test, then package
//   ./build.sc --no-test    package only, for quick iteration
//   ./build.sc --test-only  run the tests and stop
//   ./build.sc --sync       first pull the concert from the songbook, then test and package
//
// --sync is deliberately OPT-IN rather than part of every build. deploy.sc calls this script, so an
// automatic sync would let a deploy fetch whatever is in the songbook at that moment and publish
// song data nobody had looked at; it would also make every build need the network, on a train or at
// a venue, and let a build rewrite source you never touched.
//
// The extension carries the rule: *.scala is APP source and gets swept into the bundle, *.sc is
// tooling and never does. That is why the scripts are .sc — an exclusion list would have to be
// remembered every time a new helper appeared, and forgetting would put JVM code into main.js.
//
// The tests run on the JVM over the platform-neutral sources only — the rest of the app needs a
// browser, and that part is covered by the headless-Chrome check in TEST.md.

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** Sources with no browser dependency, so they compile and test on the JVM. */
val testable = Seq("Model.scala", "ModelOps.scala", "SongRow.scala", "Concerts.scala", "Timing.scala")

def run(cmd: Seq[String]): Int =
  println(s"build: ${cmd.mkString(" ")}")
  new ProcessBuilder(cmd*).inheritIO().start().waitFor()

def die(msg: String): Nothing =
  System.err.println(s"build: $msg")
  sys.exit(2)

val skipTests = args.contains("--no-test")
val testOnly  = args.contains("--test-only")
val doSync    = args.contains("--sync")

val root = Paths.get("").toAbsolutePath
if !Files.isRegularFile(root.resolve("index.html")) then
  die(s"run from the prontopop root (no index.html in $root)")

// Asked for explicitly, so a failure to reach the songbook is an error rather than a shrug: better
// to stop than to build quietly against stale songs when the point was to refresh them.
if doSync then
  // the `--` matters: without it scala-cli claims --apply as its own flag instead of passing it on
  val rc = run(Seq("scala-cli", "run", "parsesoaree.sc", "--", "--apply"))
  if rc != 0 then die(s"sync FAILED with exit code $rc - nothing built")

val sources = Files.list(root).iterator.asScala
  .map(_.getFileName.toString)
  .filter(_.endsWith(".scala"))
  .toSeq.sorted
if sources.isEmpty then die(s"no app .scala files found in $root")

if !skipTests then
  val suites = Files.list(root.resolve("tests")).iterator.asScala
    .map(p => s"tests/${p.getFileName}")
    .filter(_.endsWith(".test.scala"))
    .toSeq.sorted
  if suites.isEmpty then die("no *.test.scala suites found in tests/")
  val rc = run(Seq("scala-cli", "test") ++ testable ++ suites)
  if rc != 0 then die(s"tests FAILED with exit code $rc - nothing packaged")
  println("build: tests OK")

if testOnly then sys.exit(0)

val out = root.resolve("main.js").toString
val rc = run(Seq("scala-cli", "--power", "package", "--js") ++ sources ++ Seq("-o", out, "--force"))
if rc == 0 then println(s"build: OK - wrote $out; serve the app with: tt serv .")
else System.err.println(s"build: FAILED with exit code $rc")
sys.exit(rc)
