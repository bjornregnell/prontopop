package prontopop

import com.raquo.laminar.api.L.*
import org.scalajs.dom

def createProntoPopLandingPage(): HtmlElement =
  import Model.*
  import ModelOps.*

  case class SongRow(id: Int, title: String = "", bpm: String = "120", sign: String = "4/4", pattern: String = "||:!...:||")

  val keyPrefix = "prontopop.concert."

  var lastId = 0
  def freshId(): Int =
    lastId += 1
    lastId

  def listSaved(): Vector[String] =
    val ls = dom.window.localStorage
    (0 until ls.length).toVector
      .flatMap(i => Option(ls.key(i)))
      .filter(_.startsWith(keyPrefix))
      .map(_.drop(keyPrefix.length))
      .sorted

  val songsVar = Var(Vector(
    SongRow(freshId(), "Rymdresan - vi kommer aldrig tillbaka", "120", "3/4", "||:!..|X..|X..|X..:||"),
    SongRow(freshId(), "Hopp om en ofri", "108", "3/4", "||:!..|X..|X..|X..:||"),
  ))
  val concertNameVar = Var("")
  val savedVar    = Var(listSaved())
  val selectedVar = Var("")
  val playingVar  = Var(Option.empty[Int])
  val statusVar   = Var("")
  val volumeVar   = Var("100")
  lazy val player = Sound.initWebSound()

  def stopPlaying(): Unit =
    player.stop()
    playingVar.set(None)

  def updateRow(id: Int)(f: SongRow => SongRow): Unit =
    songsVar.update(_.map(r => if r.id == id then f(r) else r))

  def removeRow(id: Int): Unit =
    if playingVar.now().contains(id) then stopPlaying()
    songsVar.update(_.filterNot(_.id == id))

  def addSong(): Unit = songsVar.update(_ :+ SongRow(freshId()))

  def parseSignature(s: String): Either[String, Signature] = s.trim.split("/") match
    case Array(n, d) if n.trim.toIntOption.exists(_ > 0) && d.trim.toIntOption.exists(_ > 0) =>
      Right(Signature(Frac(n.trim.toInt, d.trim.toInt)))
    case _ => Left(s"bad signature '$s', expected like 3/4")

  def togglePlay(row: SongRow): Unit =
    if playingVar.now().contains(row.id) then stopPlaying()
    else
      val parsed =
        for
          bpm  <- row.bpm.trim.toDoubleOption.filter(_ > 0).toRight(s"bad bpm '${row.bpm}'")
          sig  <- parseSignature(row.sign)
          bars <- Pattern(row.pattern).parse(sig).left.map:
                    case Error.ParseError(msg, pos) => s"pattern error at $pos: $msg"
        yield (bpm, bars)
      parsed match
        case Left(err) => statusVar.set(err)
        case Right((bpm, bars)) =>
          player.play(bpm, bars)
          playingVar.set(Some(row.id))
          statusVar.set(s"playing '${row.title}'")

  def save(): Unit =
    val name = concertNameVar.now().trim
    if name.isEmpty then statusVar.set("give the concert a name before saving")
    else
      val text = songsVar.now()
        .map(r => Seq(r.title, r.bpm, r.sign, r.pattern).mkString("\t"))
        .mkString("\n")
      dom.window.localStorage.setItem(keyPrefix + name, text)
      savedVar.set(listSaved())
      selectedVar.set(name)
      statusVar.set(s"saved '$name'")

  def load(): Unit =
    val name = selectedVar.now()
    Option(dom.window.localStorage.getItem(keyPrefix + name)) match
      case None => statusVar.set(if name.isEmpty then "select a saved concert to load" else s"no saved concert '$name'")
      case Some(text) =>
        val rows = text.split("\n", -1).toVector.filter(_.nonEmpty).map: line =>
          val f = line.split("\t", -1)
          SongRow(freshId(), f.lift(0).getOrElse(""), f.lift(1).getOrElse(""), f.lift(2).getOrElse(""), f.lift(3).getOrElse(""))
        stopPlaying()
        songsVar.set(rows)
        concertNameVar.set(name)
        statusVar.set(s"loaded '$name' (${rows.length} songs)")

  def renderRow(id: Int, initial: SongRow, rowSignal: Signal[SongRow]): HtmlElement =
    div(cls := "songrow",
      button(
        child.text <-- playingVar.signal.map(p => if p.contains(id) then "Stop" else "Play"),
        cls.toggle("playing") <-- playingVar.signal.map(_.contains(id)),
        onClick --> (_ => songsVar.now().find(_.id == id).foreach(togglePlay)),
      ),
      input(cls := "title", controlled(value <-- rowSignal.map(_.title), onInput.mapToValue --> (v => updateRow(id)(_.copy(title = v))))),
      input(cls := "bpm", controlled(value <-- rowSignal.map(_.bpm), onInput.mapToValue --> (v => updateRow(id)(_.copy(bpm = v))))),
      input(cls := "sign", controlled(value <-- rowSignal.map(_.sign), onInput.mapToValue --> (v => updateRow(id)(_.copy(sign = v))))),
      input(cls := "pattern", controlled(value <-- rowSignal.map(_.pattern), onInput.mapToValue --> (v => updateRow(id)(_.copy(pattern = v))))),
      button("Remove", onClick --> (_ => removeRow(id))),
    )

  div(cls := "app",
    h1("Welcome to ProntoPop!"),
    div(cls := "row",
      span("Concert Name: "),
      input(controlled(value <-- concertNameVar.signal, onInput.mapToValue --> concertNameVar.writer)),
      button("Save", onClick --> (_ => save())),
      span(" to Local Store"),
    ),
    div(cls := "row",
      span("Saved Concerts: "),
      select(
        children <-- savedVar.signal.map(names => ("" +: names).map(n =>
          option(value := n, if n.isEmpty then "-- select --" else n))),
        value <-- selectedVar.signal,
        onChange.mapToValue --> selectedVar.writer,
      ),
      button("Load", onClick --> (_ => load())),
      span(" from Local Store"),
    ),
    div(cls := "row",
      span("Volume: "),
      input(typ := "range", minAttr := "0", maxAttr := "100",
        controlled(value <-- volumeVar.signal, onInput.mapToValue --> { (v: String) =>
          volumeVar.set(v)
          v.toDoubleOption.foreach(d => player.setVolume(d / 100.0))
        }),
      ),
      span(child.text <-- volumeVar.signal.map(v => s"$v%")),
    ),
    div(cls := "row",
      h2("Songs:"),
      button("Silence", cls := "silence", onClick --> (_ => stopPlaying())),
    ),
    div(cls := "songrow header",
      span("On/Off"), span("Title"), span("BPM"), span("Sign."), span("Pattern"), span(),
    ),
    children <-- songsVar.signal.split(_.id)(renderRow),
    div(cls := "row", button("Add song", onClick --> (_ => addSong()))),
    div(cls := "status", child.text <-- statusVar.signal),
  )

/* Landing Page:
  legend of below LAYOUT:
      [   ]  are input field (length is num of chars)
      {Name}  are buttons with Name
      /InterfaceElem/  is a UI element of type InterfaceElem for example DropDown
      All other text is just text
      Styling should be monospace and repsonsive desktop/mobile

  LAYOUT:
----
Welcome to ProntoPop!

Concert Name: [            ]  {Save} to Local Store

Saved Concerts: /DropDown/    {Load} from Local Store

Songs:

On/Off  Title                                    BPM   Sign.   Pattern
{Play}  [Rymdresan - vi kommer aldrig tillbaka ] [120] [3/4 ] [||:!..|X..|X..|X..:||  ] {Remove}
{Play}  [Hopp om en ofri                       ] [108] [3/4 ] [||:!..|X..|X..|X..:||  ] {Remove}

{Add song}

*/
