#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0-RC4

// Builds the prontopop app: runs the munit tests, then links the app's *.scala files into main.js
// next to index.html. Run from the project root:
//
//   ./build.scala              test, then package
//   ./build.scala --no-test    package only, for quick iteration
//   ./build.scala --test-only   run the tests and stop
//
// The app sources are passed explicitly (this file and deploy.scala excluded) so the build never
// sweeps itself in. The tests run on the JVM over the platform-neutral sources only — the rest of
// the app needs a browser, and that part is covered by the headless-Chrome check in TEST.md.

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Sources with no browser dependency, so they compile and test on the JVM. */
val testable = Seq("Model.scala", "ModelOps.scala", "SongRow.scala", "Concerts.scala", "Timing.scala")

def run(cmd: Seq[String]): Int =
  println(s"build: ${cmd.mkString(" ")}")
  new ProcessBuilder(cmd*).inheritIO().start().waitFor()

def die(msg: String): Nothing =
  System.err.println(s"build: $msg")
  sys.exit(2)

@main def buildMainJS(args: String*): Unit =
  val skipTests = args.contains("--no-test")
  val testOnly  = args.contains("--test-only")

  val root = Paths.get("").toAbsolutePath
  if !Files.isRegularFile(root.resolve("index.html")) then
    die(s"run from the prontopop root (no index.html in $root)")

  val sources = Files.list(root).iterator.asScala
    .map(_.getFileName.toString)
    .filter(n => n.endsWith(".scala") && n != "build.scala" && n != "deploy.scala")
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
