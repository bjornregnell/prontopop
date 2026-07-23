#!/usr/bin/env -S scala-cli shebang

//> using scala 3.8.4

// Deploys the prontopop app to https://bjornregnell.se/pp/
//   1. builds main.js via build.scala
//   2. on success uploads index.html + main.js over SFTP with lftp
// Same credential scheme as genscalator/deploy/deployblog.sc: NO host, username or
// password in this file - lftp reads them from ~/.netrc (machine <...>.service.one,
// chmod 600), so no secret ever reaches a command line or the process list.
// Usage from the project root:
//   ./deploy.scala --dry-run    # build + show what WOULD upload, change nothing
//   ./deploy.scala              # build + upload

import java.nio.file.{Files, Paths, StandardCopyOption}
import scala.collection.mutable

@main def deployPP(args: String*): Unit =
  def die(msg: String): Nothing = { System.err.println(s"deploy: $msg"); sys.exit(2) }

  val dryRun      = args.contains("--dry-run")
  val deployFiles = Seq("index.html", "main.js")
  val remoteDir   = "webroots/www/pp"

  val root = Paths.get("").toAbsolutePath
  if !Files.isRegularFile(root.resolve("index.html")) then
    die(s"run from the prontopop root (no index.html in $root)")

  def onPath(exe: String): Boolean =
    Option(System.getenv("PATH")).exists(_.split(java.io.File.pathSeparator)
      .exists(d => Files.isExecutable(Paths.get(d, exe))))
  if !onPath("lftp") then die("`lftp` not found. Install it:  sudo apt install lftp")

  // ---- step 1: build ----
  println("deploy: building via build.scala")
  val buildRc = new ProcessBuilder("scala-cli", "run", "build.scala").inheritIO().start().waitFor()
  if buildRc != 0 then die(s"build failed (exit $buildRc) - nothing deployed")

  // ---- step 2: stage exactly the deployable files, so the upload can never sweep in sources or .git ----
  val staging = Files.createTempDirectory("pp-deploy")
  for f <- deployFiles do
    val src = root.resolve(f)
    if !Files.isRegularFile(src) then die(s"missing $f after build")
    Files.copy(src, staging.resolve(f), StandardCopyOption.REPLACE_EXISTING)

  // ---- step 3: host + login from ~/.netrc (the password stays there; lftp reads it itself) ----
  val netrc = Paths.get(System.getProperty("user.home"), ".netrc")
  if !Files.isRegularFile(netrc) then
    die("no ~/.netrc - create it (chmod 600) with the one.com SFTP machine/login/password, see genscalator/deploy/deployblog.sc")
  val toks = Files.readString(netrc).split("\\s+").filter(_.nonEmpty).iterator
  val entries = mutable.Map[String, (String, String)]()
  var m, l, p = ""
  def flush(): Unit = if m.nonEmpty then entries(m) = (l, p)
  while toks.hasNext do
    toks.next() match
      case "machine"  => flush(); m = if toks.hasNext then toks.next() else ""; l = ""; p = ""
      case "default"  => flush(); m = ""; l = ""; p = ""
      case "login"    => if toks.hasNext then l = toks.next()
      case "password" => if toks.hasNext then p = toks.next()
      case "account" | "macdef" => if toks.hasNext then toks.next()
      case _ => ()
  flush()
  val (host, login) = entries.collectFirst {
    case (h, (u, _)) if h.endsWith(".service.one") && u.nonEmpty => (h, u)
  }.getOrElse(die("no `machine <...>.service.one` entry (with a login) found in ~/.netrc."))

  // ---- step 4: mirror the staging dir up, lftp contained (output captured, shown only on failure) ----
  def q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  val mirrorOpts = (Seq("-R", "--verbose") ++ (if dryRun then Seq("--dry-run") else Nil)).mkString(" ")
  val lftpScript =
    s"""|set sftp:connect-program "ssh -a -x -oStrictHostKeyChecking=accept-new -oPubkeyAuthentication=no -oPreferredAuthentications=password"
        |open sftp://$login@$host
        |mirror $mirrorOpts ${q(staging.toString)} ${q(remoteDir)}
        |bye
        |""".stripMargin

  println(s"deploy: ${if dryRun then "DRY-RUN " else ""}upload ${deployFiles.mkString(", ")}  ->  $host:$remoteDir")
  val pb = new ProcessBuilder("lftp")
  pb.redirectErrorStream(true)
  val proc = pb.start()
  proc.getOutputStream.write(lftpScript.getBytes("UTF-8"))
  proc.getOutputStream.close()
  val captured = new String(proc.getInputStream.readAllBytes(), "UTF-8")
  val code = proc.waitFor()

  for f <- deployFiles do Files.deleteIfExists(staging.resolve(f))
  Files.deleteIfExists(staging)

  if code == 0 then
    println(s"deploy: done${if dryRun then " (dry-run: nothing changed)" else " - live at https://bjornregnell.se/pp/"}")
  else
    System.err.println(captured)
    die(s"lftp exited $code - check ~/.netrc credentials, that SFTP is enabled, and the remote path")
