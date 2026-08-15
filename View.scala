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
    // 2ch of slack covers the field's own border and its remaining side padding, with room for the
    // caret past the last character. It was 3ch while the fields still had wide side padding.
    val title = (widest(_.title) + 2).max(14).min(60)
    // a pattern character takes 1.2ch, because the field letter-spaces the beats apart
    val pattern = ((widest(_.pattern) * 1.2).ceil.toInt + 2).max(12).min(60)
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
  /** Which concert the table holds, so the dropdown reads as "this is what is loaded". */
  val selectedVar    = Var(Concerts.startupTitle)
  /** Whether the table has been touched since it was last loaded or saved. Kept for the whole
    * table rather than per song: what a load overwrites is all of it. */
  val dirtyVar       = Var(false)
  /** A concert waiting on the "discard your edits?" answer. Some(name) is what puts the dialog on
    * screen, and answering either way clears it. */
  val pendingLoadVar = Var(Option.empty[Title])
  val playingVar     = Var(Option.empty[Int])
  /** The song that played last, so the cue stays put when it stops. None until something plays. */
  val cueVar         = Var(Option.empty[Int])
  val statusVar      = Var("")
  val volumeVar      = Var("100")
  /** How many bars a song plays before stopping itself; "forever" means until Silence. */
  val barsVar        = Var("4")
  /** Driven by the browser's own fullscreenchange, so the label stays right however it was left —
    * by the button, or by the Escape key, which the browser handles itself. */
  val fullScreenVar  = Var(false)
  val barChoices     = Vector("1", "2", "4", "8", "16", "32", "forever")
  lazy val player    = Sound.initWebSound()

  def stopPlaying(): Unit =
    player.stop()
    playingVar.set(None)

  def updateRow(id: Int)(f: SongRow => SongRow): Unit =
    songsVar.update(_.map(r => if r.id == id then f(r) else r))
    dirtyVar.set(true)

  def removeRow(id: Int): Unit =
    if playingVar.now().contains(id) then stopPlaying()
    songsVar.update(_.filterNot(_.id == id))
    dirtyVar.set(true)

  def addSong(): Unit =
    songsVar.update(_ :+ SongRow(freshId()))
    dirtyVar.set(true)

  def toggleFullScreen(): Unit =
    if dom.document.fullscreenElement == null then dom.document.documentElement.requestFullscreen()
    else dom.document.exitFullscreen()

  /** Leaving fullscreen is also the browser's own answer to Escape, so this mostly agrees with what
    * already happened; asking to exit when not fullscreen would be rejected, hence the guard. */
  def exitFullScreenIfAny(): Unit =
    if dom.document.fullscreenElement != null then dom.document.exitFullscreen()

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

  /** Step the cue, wrapping at either end rather than going dead. */
  def moveCue(delta: Int): Unit =
    val rows = songsVar.now()
    if rows.nonEmpty then
      val at = cueVar.now().map(id => rows.indexWhere(_.id == id)).filter(_ >= 0).getOrElse(0)
      val to = ((at + delta) % rows.length + rows.length) % rows.length
      cueVar.set(Some(rows(to).id))

  /** Play the cued song — the one the ">" marks, or the top one before anything has played. */
  def playCued(): Unit =
    val rows = songsVar.now()
    cueVar.now().flatMap(id => rows.find(_.id == id)).orElse(rows.headOption).foreach(startPlaying)

  /** Stop if something is running, otherwise start the cued song. What the big button does, and
    * what the space bar does. */
  def togglePlayCued(): Unit =
    if playingVar.now().isDefined then stopPlaying() else playCued()

  /** The single source of truth for the keyboard. */
  val shortcuts: Vector[Shortcut] = Vector(
    Shortcut(" ", "Space", "play the cued song, or stop what is playing", evenWhileTyping = false,
      () => togglePlayCued()),
    Shortcut("Backspace", "Backspace", "stop the playing song, whatever else is going on",
      evenWhileTyping = false,
      () => stopPlaying()),
    Shortcut("Escape", "Esc", "leave full screen", evenWhileTyping = true,
      () => exitFullScreenIfAny()),
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
      dirtyVar.set(false)
      statusVar.set(s"saved '$name'")

  /** Replace the table with a concert, no questions asked. Everything that asks them calls this. */
  def loadConcert(name: Title): Unit =
    concertRows(name) match
      case None => statusVar.set(s"no concert '$name'")
      case Some(rows) =>
        stopPlaying()
        songsVar.set(rows)
        colWidthsVar.set(fitWidths(rows))
        concertNameVar.set(name)
        selectedVar.set(name)
        dirtyVar.set(false)
        statusVar.set(s"loaded '$name' (${rows.length} songs)")

  /** What choosing from the dropdown does. Loads at once when nothing would be lost, and otherwise
    * puts the question on screen rather than quietly throwing the edits away.
    *
    * The select is `controlled`, so while the question is open the dropdown snaps back to the
    * concert that is actually loaded — it must not sit there showing one concert while the table
    * holds another. */
  def chooseConcert(name: Title): Unit =
    if name.nonEmpty && name != selectedVar.now() then
      if dirtyVar.now() then pendingLoadVar.set(Some(name)) else loadConcert(name)

  /** Answer the question: Some(name) loads it, None leaves the table alone. */
  def answerPendingLoad(load: Boolean): Unit =
    val pending = pendingLoadVar.now()
    pendingLoadVar.set(None)
    if load then pending.foreach(loadConcert)

  /** Parsed rather than compared as text, so " 4 / 4 " counts and a half-typed signature does not.
    * Anything unparseable is simply not 4/4, and wears the other colour. */
  def isFourFour(sign: String): Boolean =
    SongRow.parseSignature(sign).exists(s => s.frac.numerator == 4 && s.frac.denominator == 4)

  def renderRow(id: Int, initial: SongRow, rowSignal: Signal[SongRow]): HtmlElement =
    div(cls := "songrow",
      // the ">" cue marking the current or last played song. A real button, so the column looks
      // pressable and says what it does: pressing one moves the cue to that row. It was a read-only
      // input once, which took a caret when tapped and offered an edit that was never going to
      // happen. Out of the tab order all the same — the arrow keys are the keyboard route, and one
      // of these per song would otherwise sit between every pair of rows.
      // A no-break space when unmarked, so the empty cell keeps the height of a marked one.
      button(cls := "cue", tabIndex := -1, title := "move the cue here",
        cls("cued") <-- cueSignal.map(_.contains(id)),
        onClick --> (_ => cueVar.set(Some(id))),
        child.text <-- cueSignal.map(cued => if cued.contains(id) then ">" else " ")),
      button(
        child.text <-- playingVar.signal.map(p => if p.contains(id) then "Stop" else "Play"),
        cls("playing") <-- playingVar.signal.map(_.contains(id)),
        onClick --> (_ => songsVar.now().find(_.id == id).foreach(togglePlay)),
      ),
      input(cls := "title", controlled(value <-- rowSignal.map(_.title), onInput.mapToValue --> (v => updateRow(id)(_.copy(title = v))))),
      input(cls := "bpm", controlled(value <-- rowSignal.map(_.bpm), onInput.mapToValue --> (v => updateRow(id)(_.copy(bpm = v))))),
      input(cls := "sign",
        cls("common") <-- rowSignal.map(r => isFourFour(r.sign)),
        controlled(value <-- rowSignal.map(_.sign), onInput.mapToValue --> (v => updateRow(id)(_.copy(sign = v))))),
      input(cls := "pattern", controlled(value <-- rowSignal.map(_.pattern), onInput.mapToValue --> (v => updateRow(id)(_.copy(pattern = v.replace("…", "...")))))),
      button("Remove", onClick --> (_ => removeRow(id))),
    )

  /** The question asked before a load throws edits away. Cancel takes the focus, so the reflex
    * answers — a stray Return or space bar on the keys — keep the table as it is. Clicking the
    * darkened page behind it cancels too, which is what a tap outside a dialog usually means. */
  def renderConfirmLoad(name: Title): HtmlElement =
    div(cls := "backdrop",
      onClick --> (_ => answerPendingLoad(load = false)),
      div(cls := "dialog",
        // the click that opens a concert must not also count as a click on the page behind
        onClick.stopPropagation --> (_ => ()),
        h2("Unsaved changes"),
        p(
          "The songs have been edited since they were last saved. Loading ",
          span(cls := "concertname", s"\"$name\""),
          " replaces them, and the edits are gone.",
        ),
        div(cls := "row",
          button("Cancel", cls := "cancel", onMountFocus,
            onClick --> (_ => answerPendingLoad(load = false))),
          button("Discard and load", cls := "discard",
            onClick --> (_ => answerPendingLoad(load = true))),
        ),
      ),
    )

  div(cls := "app",
    onMountCallback: _ =>
      // Driven by the state change rather than the button, so entering fullscreen by any route
      // holds the screen awake — and leaving it by any route, Escape included, lets go again.
      dom.document.addEventListener("fullscreenchange", (_: dom.Event) =>
        val on = dom.document.fullscreenElement != null
        fullScreenVar.set(on)
        if on then WakeLock.keepAwake() else WakeLock.allowSleep()
      )
    ,
    // Custom properties inherit, so setting them here resizes every row's grid at once.
    styleAttr <-- colWidthsVar.signal.map: (title, pattern) =>
      s"--col-title: ${title}ch; --col-pattern: ${pattern}ch"
    ,
    // Listening on the document rather than an element means the keys work without clicking into
    // the page first; preventDefault stops the page scrolling under them.
    documentEvents(_.onKeyDown) --> { (e: dom.KeyboardEvent) =>
      // While the question is on screen it owns the keyboard: Escape answers no, and nothing else
      // gets through — a space bar that started a song from behind a modal would be a nasty
      // surprise. Yes is left to the focused button, which Enter and Space already activate.
      if pendingLoadVar.now().isDefined then
        if e.key == "Escape" then
          e.preventDefault()
          answerPendingLoad(load = false)
      else
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
      // So the warning when loading is never a surprise: the table says all along that it holds
      // something the Local Store does not.
      span(cls := "unsaved",
        child.text <-- dirtyVar.signal.map(d => if d then "  unsaved changes" else "")),
    ),
    div(cls := "row",
      span("Saved Concerts: "),
      // Choosing loads: a Load button beside it only asked the same question twice. Controlled, so
      // a choice that is refused snaps back to the concert the table actually holds.
      select(
        cls := "concertfield",
        children <-- offeredVar.signal.map: offered =>
          offered.map: (name, builtIn) =>
            option(value := name, if builtIn then s"$name (built-in)" else name)
        ,
        controlled(value <-- selectedVar.signal, onChange.mapToValue --> (n => chooseConcert(n))),
      ),
      span(" from Local Store"),
    ),
    div(cls := "row",
      // Ordered for a narrow screen: the three pressed mid-song first, so when the row wraps it is
      // the bar count and the volume that drop to the next line, not the transport.
      //
      // One button for both starting and stopping, and it acts on the cue like everything else
      // does: green Play while silent, red Stop while a song runs. Next and Prev only walk the
      // cue — the same thing the arrow keys do — so there is one rule to hold on stage, "the cue
      // is what plays", instead of a button whose meaning depended on whether anything had played
      // yet.
      button(cls := "transport",
        cls("stopping") <-- playingVar.signal.map(_.isDefined),
        child.text <-- playingVar.signal.map(p => if p.isDefined then "Stop" else "Play"),
        onClick --> (_ => togglePlayCued()),
      ),
      button("Next", cls := "step", onClick --> (_ => moveCue(1))),
      button("Prev", cls := "step", onClick --> (_ => moveCue(-1))),
      button(cls := "fullscreen",
        child.text <-- fullScreenVar.signal.map(on => if on then "Exit full screen" else "Full screen"),
        onClick --> (_ => toggleFullScreen()),
      ),
      select(
        cls := "bars",
        title := "bars to play before stopping",
        barChoices.map(b => option(value := b, b)),
        value <-- barsVar.signal,
        onChange.mapToValue --> barsVar.writer,
      ),
      span("Volume: "),
      input(typ := "range", minAttr := "0", maxAttr := "100",
        controlled(value <-- volumeVar.signal, onInput.mapToValue --> { (v: String) =>
          volumeVar.set(v)
          v.toDoubleOption.foreach(d => player.setVolume(d / 100.0))
        }),
      ),
      span(child.text <-- volumeVar.signal.map(v => s"$v%")),
      // Rides along with the controls rather than heading a row of its own, which cost a line of
      // screen the songs wanted. Derived from what is playing rather than set as a message, so it
      // clears itself on Stop and follows a title edited mid-play.
      span(cls := "nowplaying", child.text <--
        playingVar.signal.combineWith(songsVar.signal).map: (playing, rows) =>
          playing.flatMap(id => rows.find(_.id == id))
            .map(row => s"""playing "${row.title}"""")
            .getOrElse("")
      ),
    ),
    div(cls := "songrow header",
      span("@"), span("Play"), span("Title"), span("BPM"), span("Sig."), span("Pattern"), span(),
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
    // Last in the page, so it paints over everything; only present while there is a question.
    child.maybe <-- pendingLoadVar.signal.map(_.map(renderConfirmLoad)),
  )
