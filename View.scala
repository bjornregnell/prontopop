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

  /** Widths for the two elastic columns, fitted to the widest entry, so a concert of short titles
    * or one-bar patterns does not leave half the table empty.
    *
    * Deliberately recomputed when a concert is LOADED and not while typing: a column that grew
    * under the caret would shove every field to its right, mid-keystroke. Clamped at both ends, so
    * an empty table still has usable fields and one long title cannot run away with the layout. */
  def fitWidths(rows: Vector[SongRow]): (Int, Int) =
    def widest(text: SongRow => String): Int = rows.map(r => text(r).length).maxOption.getOrElse(0)
    val title = (widest(_.title) + 3).max(16).min(60)
    // a pattern character takes 1.2ch, because the field letter-spaces the beats apart
    val pattern = ((widest(_.pattern) * 1.2).ceil.toInt + 3).max(14).min(60)
    (title, pattern)

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
  val colWidthsVar   = Var(fitWidths(songsVar.now()))
  val concertNameVar = Var(Concerts.startupTitle)
  val offeredVar     = Var(listOffered())
  val selectedVar    = Var("")
  val playingVar     = Var(Option.empty[Int])
  /** The song that played last, so the cue stays put when it stops. None until something plays. */
  val cueVar         = Var(Option.empty[Int])
  val statusVar      = Var("")
  val volumeVar      = Var("100")
  /** How many bars a song plays before stopping itself; "forever" means until Silence. */
  val barsVar        = Var("4")
  val barChoices     = Vector("1", "2", "4", "8", "16", "32", "forever")
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

  /** One keyboard shortcut. Both the key handler and the table below are built from this list, so a
    * key cannot change in one place and go stale in the other.
    *
    * @param key             the browser's KeyboardEvent.key value
    * @param shown           how the key is written in the table
    * @param does            what it does, in the table
    * @param evenWhileTyping fires with the caret in a field too — true only where the keys mean
    *                        nothing to a text field, or where being stoppable matters more
    */
  case class Shortcut(key: String, shown: String, does: String, evenWhileTyping: Boolean, act: () => Unit)

  /** Where the cue sits: the last played song while it is still in the list, otherwise the top one,
    * so a fresh table points at the song a performer would start with. */
  val cueSignal: Signal[Option[Int]] =
    cueVar.signal.combineWith(songsVar.signal).map: (cued, rows) =>
      cued.filter(id => rows.exists(_.id == id)).orElse(rows.headOption.map(_.id))

  def startPlaying(row: SongRow): Unit =
    row.toSongAndBars match
      case Left(err) => statusVar.set(err)
      case Right((song, bars)) =>
        // the cue is deliberately left alone when a bar limit runs out: it marks the last song
        // played, which is exactly what just finished
        player.play(song.bpm, bars, barsVar.now().toIntOption, () => playingVar.set(None))
        playingVar.set(Some(row.id))
        cueVar.set(Some(row.id))
        statusVar.set("")

  def togglePlay(row: SongRow): Unit =
    if playingVar.now().contains(row.id) then stopPlaying() else startPlaying(row)

  /** True while the caret is in something typeable, so arrow keys move text rather than the cue.
    * A read-only field takes no typing, so the cue column itself does not block the keys. */
  def typingSomewhere: Boolean =
    Option(dom.document.activeElement).exists: el =>
      el.tagName match
        case "INPUT"               => !el.asInstanceOf[dom.html.Input].readOnly
        case "TEXTAREA" | "SELECT" => true
        case _                     => false

  /** Step the cue, wrapping like Play next does. */
  def moveCue(delta: Int): Unit =
    val rows = songsVar.now()
    if rows.nonEmpty then
      val at = cueVar.now().map(id => rows.indexWhere(_.id == id)).filter(_ >= 0).getOrElse(0)
      val to = ((at + delta) % rows.length + rows.length) % rows.length
      cueVar.set(Some(rows(to).id))

  /** Play the song after the cue — or the first one, when nothing has played yet, so the opening
    * press does not skip the top song. Wraps at the end rather than going dead. */
  def playNext(): Unit =
    val rows = songsVar.now()
    if rows.nonEmpty then
      val at = cueVar.now().map(id => rows.indexWhere(_.id == id)).filter(_ >= 0)
      startPlaying(rows(at.map(i => (i + 1) % rows.length).getOrElse(0)))

  /** The single source of truth for the keyboard. */
  val shortcuts: Vector[Shortcut] = Vector(
    Shortcut("Escape", "Esc", "silence — stop the playing song", evenWhileTyping = true,
      () => stopPlaying()),
    Shortcut("ArrowUp", "↑ Up", "move the cue to the song above", evenWhileTyping = false,
      () => moveCue(-1)),
    Shortcut("ArrowDown", "↓ Down", "move the cue to the song below", evenWhileTyping = false,
      () => moveCue(1)),
  )

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
        colWidthsVar.set(fitWidths(rows))
        concertNameVar.set(name)
        statusVar.set(s"loaded '$name' (${rows.length} songs)")

  def renderRow(id: Int, initial: SongRow, rowSignal: Signal[SongRow]): HtmlElement =
    div(cls := "songrow",
      // placeholder for a ">" cue marking the current or last played song; read-only and out of the
      // tab order, since there is nothing here for a performer to type
      input(cls := "cue", readOnly := true, tabIndex := -1,
        value <-- cueSignal.map(cued => if cued.contains(id) then ">" else "")),
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
    // Custom properties inherit, so setting them here resizes every row's grid at once.
    styleAttr <-- colWidthsVar.signal.map: (title, pattern) =>
      s"--col-title: ${title}ch; --col-pattern: ${pattern}ch"
    ,
    // Listening on the document rather than an element means the keys work without clicking into
    // the page first; preventDefault stops the page scrolling under them.
    documentEvents(_.onKeyDown) --> { (e: dom.KeyboardEvent) =>
      shortcuts
        .find(s => s.key == e.key && (s.evenWhileTyping || !typingSomewhere))
        .foreach: s =>
          e.preventDefault()
          s.act()
    },
    Styles.createPageStyle,
    div(cls := "row titlerow",
      h1(s"ProntoPop! $Version"),
      Theme.createSelector(),
    ),
    div(cls := "row",
      // non-breaking, because HTML collapses ordinary leading spaces: pads "Concert Name:" out to
      // the width of "Saved Concerts:" so the two colons and their fields line up
      span("  Concert Name: "),
      input(cls := "concertfield",
        controlled(value <-- concertNameVar.signal, onInput.mapToValue --> concertNameVar.writer)),
      button("Save", onClick --> (_ => save())),
      // non-breaking, since HTML collapses ordinary leading spaces: pads "to" out to the width of
      // "from" below, so both "Local Store" land in the same column
      span("  to Local Store"),
    ),
    div(cls := "row",
      span("Saved Concerts: "),
      select(
        cls := "concertfield",
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
      button("Play next", cls := "playnext", onClick --> (_ => playNext())),
      select(
        cls := "bars",
        title := "bars to play before stopping",
        barChoices.map(b => option(value := b, b)),
        value <-- barsVar.signal,
        onChange.mapToValue --> barsVar.writer,
      ),
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
    // Derived from what is playing rather than set as a message, so it clears itself on Stop and
    // follows a title edited mid-play.
    div(cls := "row",
      h2("Songs:"),
      span(cls := "nowplaying", child.text <--
        playingVar.signal.combineWith(songsVar.signal).map: (playing, rows) =>
          playing.flatMap(id => rows.find(_.id == id))
            .map(row => s"""playing "${row.title}"""")
            .getOrElse("")
      ),
    ),
    div(cls := "songrow header",
      span("@"), span("On/Off"), span("Title"), span("BPM"), span("Sign."), span("Pattern"), span(),
    ),
    children <-- songsVar.signal.split(_.id)(renderRow),
    div(cls := "row", button("Add song", onClick --> (_ => addSong()))),
    div(cls := "status", child.text <-- statusVar.signal),
    div(cls := "shortcuts",
      h2("Keyboard shortcuts"),
      table(
        thead(tr(th("Key"), th("Does"), th("While typing"))),
        tbody(
          shortcuts.map: s =>
            tr(
              td(s.shown),
              td(s.does),
              td(if s.evenWhileTyping then "yes" else "no"),
            )
        ),
      ),
    ),
  )
