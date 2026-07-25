package prontopop

import com.raquo.laminar.api.L.*
import org.scalajs.dom

def createProntoPopLandingPage(): HtmlElement =
  import Model.*
  import ModelOps.*

  val keyPrefix = "prontopop.concert."

  var lastId = 0
  def freshId(): Int =
    lastId += 1
    lastId

  /** Titles of the concerts in local storage. */
  def listSaved(): Vector[Title] =
    val ls = dom.window.localStorage
    (0 until ls.length).toVector
      .flatMap(i => Option(ls.key(i)))
      .filter(_.startsWith(keyPrefix))
      .map(_.drop(keyPrefix.length))
      .sorted

  /** What the dropdown offers: saved concerts first, then the built-in ones whose title nobody has
    * saved over. Paired with a flag so a built-in can say so in its label. */
  def listOffered(): Vector[(Title, Boolean)] =
    val saved = listSaved()
    saved.map(_ -> false) ++ Concerts.titles.filterNot(saved.contains).map(_ -> true)

  def rowsOf(concert: Concert): Vector[SongRow] =
    concert.toVector.map(song => SongRow.from(freshId(), song))

  def rowsOfSaved(text: String): Vector[SongRow] =
    text.split("\n", -1).toVector.filter(_.nonEmpty).map: line =>
      val f = line.split("\t", -1)
      SongRow(
        freshId(),
        f.lift(0).getOrElse(""),
        f.lift(1).getOrElse(""),
        f.lift(2).getOrElse(""),
        f.lift(3).getOrElse("").replace("…", "..."),
      )

  /** Local storage wins over a built-in of the same title. */
  def concertRows(name: Title): Option[Vector[SongRow]] =
    Option(dom.window.localStorage.getItem(keyPrefix + name)).map(rowsOfSaved)
      .orElse(Concerts.all.get(name).map(rowsOf))

  val songsVar       = Var(rowsOf(Concerts.startup))
  val concertNameVar = Var(Concerts.startupTitle)
  val offeredVar     = Var(listOffered())
  val selectedVar    = Var("")
  val playingVar     = Var(Option.empty[Int])
  val statusVar      = Var("")
  val volumeVar      = Var("100")
  lazy val player    = Sound.initWebSound()

  def stopPlaying(): Unit =
    player.stop()
    playingVar.set(None)

  def updateRow(id: Int)(f: SongRow => SongRow): Unit =
    songsVar.update(_.map(r => if r.id == id then f(r) else r))

  def removeRow(id: Int): Unit =
    if playingVar.now().contains(id) then stopPlaying()
    songsVar.update(_.filterNot(_.id == id))

  def addSong(): Unit = songsVar.update(_ :+ SongRow(freshId()))

  def togglePlay(row: SongRow): Unit =
    if playingVar.now().contains(row.id) then stopPlaying()
    else
      row.toSongAndBars match
        case Left(err) => statusVar.set(err)
        case Right((song, bars)) =>
          player.play(song.bpm, bars)
          playingVar.set(Some(row.id))
          statusVar.set(s"playing '${song.title}'")

  def save(): Unit =
    val name = concertNameVar.now().trim
    if name.isEmpty then statusVar.set("give the concert a name before saving")
    else
      val text = songsVar.now()
        .map(r => Seq(r.title, r.bpm, r.sign, r.pattern).mkString("\t"))
        .mkString("\n")
      dom.window.localStorage.setItem(keyPrefix + name, text)
      offeredVar.set(listOffered())
      selectedVar.set(name)
      statusVar.set(s"saved '$name'")

  def load(): Unit =
    val name = selectedVar.now()
    concertRows(name) match
      case None =>
        statusVar.set(if name.isEmpty then "select a concert to load" else s"no concert '$name'")
      case Some(rows) =>
        stopPlaying()
        songsVar.set(rows)
        concertNameVar.set(name)
        statusVar.set(s"loaded '$name' (${rows.length} songs)")

  def renderRow(id: Int, initial: SongRow, rowSignal: Signal[SongRow]): HtmlElement =
    div(cls := "songrow",
      button(
        child.text <-- playingVar.signal.map(p => if p.contains(id) then "Stop" else "Play"),
        cls("playing") <-- playingVar.signal.map(_.contains(id)),
        onClick --> (_ => songsVar.now().find(_.id == id).foreach(togglePlay)),
      ),
      input(cls := "title", controlled(value <-- rowSignal.map(_.title), onInput.mapToValue --> (v => updateRow(id)(_.copy(title = v))))),
      input(cls := "bpm", controlled(value <-- rowSignal.map(_.bpm), onInput.mapToValue --> (v => updateRow(id)(_.copy(bpm = v))))),
      input(cls := "sign", controlled(value <-- rowSignal.map(_.sign), onInput.mapToValue --> (v => updateRow(id)(_.copy(sign = v))))),
      input(cls := "pattern", controlled(value <-- rowSignal.map(_.pattern), onInput.mapToValue --> (v => updateRow(id)(_.copy(pattern = v.replace("…", "...")))))),
      button("Remove", onClick --> (_ => removeRow(id))),
    )

  div(cls := "app",
    Styles.createPageStyle,
    Theme.createSelector(),
    h1(s"ProntoPop! $Version"),
    div(cls := "row",
      span("Concert Name: "),
      input(controlled(value <-- concertNameVar.signal, onInput.mapToValue --> concertNameVar.writer)),
      button("Save", onClick --> (_ => save())),
      span(" to Local Store"),
    ),
    div(cls := "row",
      span("Saved Concerts: "),
      select(
        children <-- offeredVar.signal.map: offered =>
          option(value := "", "-- select --") +: offered.map: (name, builtIn) =>
            option(value := name, if builtIn then s"$name (built-in)" else name)
        ,
        value <-- selectedVar.signal,
        onChange.mapToValue --> selectedVar.writer,
      ),
      button("Load", onClick --> (_ => load())),
      span(" from Local Store"),
    ),
    div(cls := "row",
      button("Silence", cls := "silence", onClick --> (_ => stopPlaying())),
      span("Volume: "),
      input(typ := "range", minAttr := "0", maxAttr := "100",
        controlled(value <-- volumeVar.signal, onInput.mapToValue --> { (v: String) =>
          volumeVar.set(v)
          v.toDoubleOption.foreach(d => player.setVolume(d / 100.0))
        }),
      ),
      span(child.text <-- volumeVar.signal.map(v => s"$v%")),
    ),
    div(cls := "row", h2("Songs:")),
    div(cls := "songrow header",
      span("On/Off"), span("Title"), span("BPM"), span("Sign."), span("Pattern"), span(),
    ),
    children <-- songsVar.signal.split(_.id)(renderRow),
    div(cls := "row", button("Add song", onClick --> (_ => addSong()))),
    div(cls := "status", child.text <-- statusVar.signal),
  )
