#!/usr/bin/env -S scala-cli shebang

//> using scala 3.8.4

// Builds the prontopop app: links the app's *.scala files into main.js next to index.html.
// Run from the project root: ./build.scala
// The app sources are passed explicitly (this file excluded) so the build never sweeps itself in.

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

@main def buildMainJS(): Unit =
  val root = Paths.get("").toAbsolutePath
  if !Files.isRegularFile(root.resolve("index.html")) then
    System.err.println(s"build: run from the prontopop root (no index.html in $root)")
    sys.exit(2)

  val sources = Files.list(root).iterator.asScala
    .map(_.getFileName.toString)
    .filter(n => n.endsWith(".scala") && n != "build.scala" && n != "deploy.scala")
    .toSeq.sorted

  if sources.isEmpty then
    System.err.println(s"build: no app .scala files found in $root")
    sys.exit(2)

  val out = root.resolve("main.js").toString
  val cmd = Seq("scala-cli", "--power", "package", "--js") ++ sources ++ Seq("-o", out, "--force")
  println(s"build: ${cmd.mkString(" ")}")
  val rc = new ProcessBuilder(cmd*).inheritIO().start().waitFor()
  if rc == 0 then println(s"build: OK - wrote $out; serve the app with: tt serv .")
  else System.err.println(s"build: FAILED with exit code $rc")
  sys.exit(rc)
