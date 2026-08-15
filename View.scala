package prontopop

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js

def createProntoPopLandingPage(): HtmlElement =
  import Model.*
  import ModelOps.*

  val keyPrefix = "prontopop.concert."

  /** The hidden file input, once the page has it: the Import button presses it. */
  var fileChooser: dom.html.Input = null

  /** How a pause is written to the Local Store: the same three dashes the songbook uses to ask for
    * one, so the break looks like a break wherever it is stored. No song can produce this line —
    * a song always writes its four tab-separated fields — and concerts saved before pauses existed
    * contain no such line and load exactly as they did. */
  val pauseLine: String = "---"

  /** Reading is looser than writing: dashes and nothing else, however many and whatever whitespace
    * is around them, since a file that has been through an editor or another machine's line endings
    * should still say what it meant. The same rule the songbook's parser uses. */
  def isPauseLine(s: String): Boolean =
    val t = s.trim
    t.length >= 3 && t.forall(_ == '-')

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

  /** What the dropdown offers: every saved concert, then every built-in one. A built-in stays on
    * the list even when a saved concert has taken its title — saving is not a way to delete what
    * ships in the app, and the original is the thing somebody who has made a mess of a concert
    * most wants back. Paired with a flag so a built-in can say so in its label. */
  def listOffered(): Vector[(Title, Boolean)] =
    listSaved().map(_ -> false) ++ Concerts.titles.map(_ -> true)

  /** A dropdown entry, marking a built-in so a title shared with a saved concert still picks out
    * one of the two. The marker is a control character, which no one types into a concert name,
    * and it never leaves this file: it names an option, and nothing else. Built from its code
    * point rather than written as a literal, which would be an invisible character in the source. */
  val builtInMark: String = 1.toChar.toString

  def keyOf(name: Title, builtIn: Boolean): String = if builtIn then builtInMark + name else name

  /** The title inside a dropdown key, without the marker. */
  def titleOf(key: String): Title = key.stripPrefix(builtInMark)

  /** How a key reads to a person: the same text its option shows. */
  def labelOf(key: String): String =
    if key.startsWith(builtInMark) then s"${titleOf(key)} (built-in)" else key

  /** A pause takes a fresh id like any other row, negated: negative means pause, and no two rows
    * share a key. Sharing one would make Laminar treat every pause as the same row, and removing
    * one would remove them all. */
  def pauseRow(): SongRow = SongRow.Empty.copy(id = -freshId())

  def rowsOf(concert: Concert): Vector[SongRow] =
    concert.toVector.map:
      case Pause      => pauseRow()
      case song: Song => SongRow.from(freshId(), song)

  /** Widths for the two elastic columns, fitted to the widest entry, so a concert of short titles
    * or one-bar patterns does not leave half the table empty.
    *
    * Deliberately recomputed when a concert is LOADED and not while typing: a column that grew
    * under the caret would shove every field to its right, mid-keystroke. Clamped at both ends, so
    * an empty table still has usable fields and one long title cannot run away with the layout. */
  def fitWidths(rows: Vector[SongRow]): (Int, Int) =
    // a pause has no title or pattern on screen, so its placeholder text must not set a width
    val songs = rows.filterNot(_.isPause)
    def widest(text: SongRow => String): Int = songs.map(r => text(r).length).maxOption.getOrElse(0)
    // 2ch of slack covers the field's own border and its remaining side padding, with room for the
    // caret past the last character. It was 3ch while the fields still had wide side padding.
    val title = (widest(_.title) + 2).max(14).min(60)
    // a pattern character takes 1.2ch, because the field letter-spaces the beats apart
    val pattern = ((widest(_.pattern) * 1.2).ceil.toInt + 2).max(12).min(60)
    (title, pattern)

  def rowsOfSaved(text: String): Vector[SongRow] =
    // \r\n and lone \r as well as \n: a concert may arrive as a file from another machine
    text.replace("\r\n", "\n").replace("\r", "\n")
      .split("\n", -1).toVector.filter(_.trim.nonEmpty).map: line =>
      if isPauseLine(line) then pauseRow()
      else
        val f = line.split("\t", -1)
        SongRow(
          freshId(),
          f.lift(0).getOrElse(""),
          f.lift(1).getOrElse(""),
          f.lift(2).getOrElse(""),
          f.lift(3).getOrElse("").replace("…", "..."),
        )

  /** The songs behind a dropdown key. The key says which of the two a shared title means, so
    * neither hides the other: local storage is asked for a saved key, the app itself for a
    * built-in one. */
  def concertRows(key: String): Option[Vector[SongRow]] =
    if key.startsWith(builtInMark) then Concerts.all.get(titleOf(key)).map(rowsOf)
    else Option(dom.window.localStorage.getItem(keyPrefix + key)).map(rowsOfSaved)

  val songsVar       = Var(rowsOf(Concerts.startup))
  val colWidthsVar   = Var(fitWidths(songsVar.now()))
  val concertNameVar = Var(Concerts.startupTitle)
  val offeredVar     = Var(listOffered())
  /** Which dropdown entry the table holds, so it reads as "this is what is loaded". A key, not a
    * title: the app opens on the built-in, and a saved concert may share its name. */
  val selectedVar    = Var(keyOf(Concerts.startupTitle, builtIn = true))
  /** Whether the table has been touched since it was last loaded or saved. Kept for the whole
    * table rather than per song: what a load overwrites is all of it. */
  val dirtyVar       = Var(false)
  /** A question the app will not act without an answer to. The subject is set apart from the two
    * halves of the sentence so it can be highlighted: it is the concert being acted on, and it is
    * the word the reader checks before answering.
    *
    * @param heading  what kind of question it is
    * @param before   the sentence up to the subject
    * @param subject  the concert, as a person reads it
    * @param after    the rest of the sentence, saying what will be lost
    * @param confirm  the label on the answer that does it
    * @param act      what to do if that answer is given
    */
  case class Ask(heading: String, before: String, subject: String, after: String, confirm: String,
    act: () => Unit)

  /** The question on screen, if any. Answering either way clears it. */
  val pendingAskVar = Var(Option.empty[Ask])
  val playingVar     = Var(Option.empty[Int])
  /** The song that played last, so the cue stays put when it stops. None until something plays. */
  val cueVar         = Var(Option.empty[Int])
  val statusVar      = Var("")
  val volumeVar      = Var("100")
  /** How many bars a song plays before stopping itself; the infinity sign means until stopped. */
  val barsVar        = Var("4")
  /** Driven by the browser's own fullscreenchange, so the label stays right however it was left —
    * by the button, or by the Escape key, which the browser handles itself. */
  val fullScreenVar  = Var(false)
  /** How many bars to play. Anything that is not a number means forever, so the infinity sign both
    * reads as forever and behaves as it: nothing parses it to an Int. */
  val barChoices     = Vector("1", "2", "4", "8", "16", "32", "∞")
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

  /** Where the ">" sits, by the same rule the marker uses: the cued row while it is still in the
    * list, otherwise the first song. -1 when there is no song to point at. */
  def cuedIndex(rows: Vector[SongRow]): Int =
    cueVar.now().map(id => rows.indexWhere(_.id == id)).filter(_ >= 0)
      .getOrElse(rows.indexWhere(!_.isPause))

  /** Put a new row directly under the cue, so a concert grows where the eye already is rather than
    * at the bottom of a long list.
    *
    * A new SONG takes the cue with it. Without that, adding three songs would leave them in the
    * reverse of the order they were made, each one landing under the same unmoved marker. A pause
    * cannot take the cue — there is nothing there to play — so it leaves the marker where it is. */
  def insertRow(row: SongRow): Unit =
    val rows = songsVar.now()
    val at = cuedIndex(rows)
    songsVar.set(rows.patch(if at >= 0 then at + 1 else rows.length, Vector(row), 0))
    if !row.isPause then cueVar.set(Some(row.id))
    dirtyVar.set(true)

  def addSong(): Unit = insertRow(SongRow(freshId()))

  /** Swap a row with the one above or below it. Silent at the ends rather than wrapping: the
    * buttons that would go nowhere are disabled, and a keyboard or a double click that gets past
    * them should do nothing rather than something surprising. */
  def moveRow(id: Int, delta: Int): Unit =
    val rows = songsVar.now()
    val i = rows.indexWhere(_.id == id)
    val j = i + delta
    if i >= 0 && j >= 0 && j < rows.length then
      songsVar.set(rows.updated(i, rows(j)).updated(j, rows(i)))
      dirtyVar.set(true)

  /** Where a row sits now, so the ends of the table can grey out the move that leads nowhere. */
  def indexSignal(id: Int): Signal[(Int, Int)] =
    songsVar.signal.map(rows => (rows.indexWhere(_.id == id), rows.length))

  def addPause(): Unit = insertRow(pauseRow())

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
      cued.filter(id => rows.exists(_.id == id))
        .orElse(rows.find(!_.isPause).map(_.id))  // never a pause: there is nothing there to play

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

  /** Step the cue, wrapping at either end rather than going dead. Pauses are left out of the walk
    * entirely, so a step never lands on one and never has to step twice to get past it. */
  def moveCue(delta: Int): Unit =
    val songs = songsVar.now().filterNot(_.isPause)
    if songs.nonEmpty then
      val at = cueVar.now().map(id => songs.indexWhere(_.id == id)).filter(_ >= 0).getOrElse(0)
      val to = ((at + delta) % songs.length + songs.length) % songs.length
      cueVar.set(Some(songs(to).id))

  /** Play the cued song — the one the ">" marks, or the top one before anything has played. */
  def playCued(): Unit =
    val rows = songsVar.now()
    cueVar.now().flatMap(id => rows.find(_.id == id)).orElse(rows.find(!_.isPause))
      .filterNot(_.isPause).foreach(startPlaying)

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

  /** A concert as text: one song per line, its four fields separated by tabs, and a pause as three
    * dashes. One format for the Local Store and for a file on disk — a concert exported and
    * imported again is the same concert, and either can be edited in a spreadsheet. */
  def concertText(rows: Vector[SongRow]): String =
    rows
      .map(r => if r.isPause then pauseLine else Seq(r.title, r.bpm, r.sign, r.pattern).mkString("\t"))
      .mkString("\n")

  def save(): Unit =
    val name = concertNameVar.now().trim
    if name.isEmpty then statusVar.set("give the concert a name before saving")
    else
      val text = concertText(songsVar.now())
      dom.window.localStorage.setItem(keyPrefix + name, text)
      offeredVar.set(listOffered())
      selectedVar.set(name)
      dirtyVar.set(false)
      statusVar.set(s"saved '$name'")

  /** Replace the table with a concert, no questions asked. Everything that asks them calls this.
    * The Concert Name field takes the plain title, so saving a loaded built-in saves under its
    * name — and the built-in stays in the dropdown beside it. */
  def loadConcert(key: String): Unit =
    concertRows(key) match
      case None => statusVar.set(s"no concert '${labelOf(key)}'")
      case Some(rows) =>
        stopPlaying()
        songsVar.set(rows)
        colWidthsVar.set(fitWidths(rows))
        concertNameVar.set(titleOf(key))
        selectedVar.set(key)
        dirtyVar.set(false)
        statusVar.set(s"loaded '${labelOf(key)}' (${rows.length} songs)")

  /** What choosing from the dropdown does. Loads at once when nothing would be lost, and otherwise
    * puts the question on screen rather than quietly throwing the edits away.
    *
    * The select is `controlled`, so while the question is open the dropdown snaps back to the
    * concert that is actually loaded — it must not sit there showing one concert while the table
    * holds another. */
  def chooseConcert(key: String): Unit =
    if key.nonEmpty && key != selectedVar.now() then
      if !dirtyVar.now() then loadConcert(key)
      else pendingAskVar.set(Some(Ask(
        heading = "Unsaved changes",
        before = "The songs have been edited since they were last saved. Loading ",
        subject = labelOf(key),
        after = " replaces them, and the edits are gone.",
        confirm = "Discard and load",
        act = () => loadConcert(key),
      )))

  /** Take a saved concert out of the Local Store. The songs stay on screen: removing the stored
    * copy is not the same as losing the work, and Save turns orange to say the table now holds
    * something the store does not. A built-in cannot get here — the button is disabled for one,
    * and this asks the store, which never had it. */
  def removeConcert(name: Title): Unit =
    dom.window.localStorage.removeItem(keyPrefix + name)
    offeredVar.set(listOffered())
    // the dropdown must name something that exists; the built-ins are always there to fall back on
    selectedVar.set(offeredVar.now().headOption.map((n, b) => keyOf(n, b)).getOrElse(""))
    dirtyVar.set(true)
    statusVar.set(s"removed '$name' from the Local Store")

  /** Ask before removing, since nothing brings a saved concert back. */
  def askRemoveConcert(): Unit =
    val key = selectedVar.now()
    if key.nonEmpty && !key.startsWith(builtInMark) then
      pendingAskVar.set(Some(Ask(
        heading = "Remove concert",
        before = "Removing ",
        subject = key,
        after = " from the Local Store cannot be undone. The songs stay in the table, unsaved.",
        confirm = "Remove",
        act = () => removeConcert(key),
      )))

  /** Only what a file name can safely carry, so a concert called "Soaré / 2026" still lands on
    * disk. Empty names fall back rather than producing a file called ".tsv". */
  def fileNameOf(name: String): String =
    val safe = name.trim.map(c => if c.isLetterOrDigit || "-_. ".contains(c) then c else '-').trim
    (if safe.isEmpty then "concert" else safe) + ".tsv"

  /** Write the concert out as a file. Nothing travels — the app has no server and the file lands on
    * the same machine the Local Store is on — which is why this is an export and not a download.
    *
    * The mechanism is an object URL on an anchor carrying the `download` attribute, which every
    * browser here has, rather than showSaveFilePicker, which Firefox does not; whether a "where
    * to?" dialog appears is then the browser's own setting for saving files. */
  def exportConcert(): Unit =
    val text = concertText(songsVar.now())
    val bag = js.Dynamic.literal("type" -> "text/tab-separated-values;charset=utf-8")
    // the element type is spelled out because js.Array is invariant, so js.Array[String] is not it
    val parts = js.Array[dom.BufferSource | dom.Blob | String](text)
    val blob = dom.Blob(parts, bag.asInstanceOf[dom.BlobPropertyBag])
    val url = dom.URL.createObjectURL(blob)
    val a = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
    a.href = url
    a.setAttribute("download", fileNameOf(concertNameVar.now()))
    // it has to be in the document for the click to count in every browser
    dom.document.body.appendChild(a)
    a.click()
    dom.document.body.removeChild(a)
    dom.URL.revokeObjectURL(url)
    statusVar.set(s"exported '${fileNameOf(concertNameVar.now())}'")

  /** Put an imported concert in the table. Not a load: it came from a file, not from the Local
    * Store, so the table now holds something the store does not have and Save says so in orange.
    * The file's name fills the Concert field, so saving it is one press. */
  def takeImported(name: String, rows: Vector[SongRow]): Unit =
    stopPlaying()
    songsVar.set(rows)
    colWidthsVar.set(fitWidths(rows))
    concertNameVar.set(name)
    cueVar.set(None)
    dirtyVar.set(true)
    statusVar.set(s"imported '$name' (${rows.count(!_.isPause)} songs) — not saved yet")

  /** What a chosen file becomes, once it has been read. Refuses a file that holds no songs rather
    * than emptying the table over it, and asks first if there are edits to lose. */
  def imported(fileName: String, text: String): Unit =
    val name = fileName.reverse.dropWhile(_ != '.').drop(1).reverse.trim match
      case "" => fileName.trim
      case stem => stem
    val rows = rowsOfSaved(text)
    if rows.forall(_.isPause) then
      statusVar.set(s"'$fileName' holds no songs — nothing imported")
    else if !dirtyVar.now() then takeImported(name, rows)
    else
      pendingAskVar.set(Some(Ask(
        heading = "Unsaved changes",
        before = "The songs have been edited since they were last saved. Importing ",
        subject = fileName,
        after = " replaces them, and the edits are gone.",
        confirm = "Discard and import",
        act = () => takeImported(name, rows),
      )))

  /** Read the file the chooser handed over. Its value is cleared afterwards so that choosing the
    * same file twice in a row still counts as a change. */
  def readChosen(input: dom.html.Input): Unit =
    val files = input.files
    if files != null && files.length > 0 then
      val file = files(0)
      val reader = dom.FileReader()
      reader.onload = _ =>
        imported(file.name, reader.result.asInstanceOf[String])
        input.value = ""
      reader.onerror = _ =>
        statusVar.set(s"could not read '${file.name}'")
        input.value = ""
      reader.readAsText(file, "UTF-8")

  /** Answer the question: yes runs what it asked about, no leaves everything alone. */
  def answerAsk(yes: Boolean): Unit =
    val pending = pendingAskVar.now()
    pendingAskVar.set(None)
    if yes then pending.foreach(_.act())

  /** Parsed rather than compared as text, so " 4 / 4 " counts and a half-typed signature does not.
    * Anything unparseable is simply not 4/4, and wears the other colour. */
  def isFourFour(sign: String): Boolean =
    SongRow.parseSignature(sign).exists(s => s.frac.numerator == 4 && s.frac.denominator == 4)

  /** A pause: a rule drawn across the table where the songs stop for a while. It carries no fields
    * and no Play button — there is nothing to type and nothing to sound — but it keeps its Remove,
    * so a break can be taken out like anything else. */
  def renderPause(id: Int): HtmlElement =
    div(cls := "songrow pause",
      span(),
      div(cls := "pauseline", title := "a pause in the concert"),
      button("Remove", onClick --> (_ => removeRow(id))),
      // a pause arrives at the bottom of the table, so it needs these more than a song does
      moveButtons(id),
    )

  /** Move up and Move down, greyed out at the ends where there is nothing to swap with.
    *
    * Solid triangles, not the thin arrows the transport wears: those move the CUE, these move the
    * ROW, and one glyph doing both jobs on the same screen would be a trap. The words are still
    * there, in the hover title. */
  def moveButtons(id: Int): Seq[HtmlElement] =
    val at = indexSignal(id)
    Seq(
      button("▲", cls := "moveup", title := "move up: swap with the row above",
        disabled <-- at.map((i, _) => i <= 0),
        onClick --> (_ => moveRow(id, -1))),
      button("▼", cls := "movedown", title := "move down: swap with the row below",
        disabled <-- at.map((i, n) => i < 0 || i >= n - 1),
        onClick --> (_ => moveRow(id, 1))),
    )

  /** Which of the two a row is can be decided once, from the initial value: the sign of an id
    * never changes, so a song never becomes a pause under the same key. */
  def renderRow(id: Int, initial: SongRow, rowSignal: Signal[SongRow]): HtmlElement =
    if initial.isPause then renderPause(id) else renderSong(id, rowSignal)

  def renderSong(id: Int, rowSignal: Signal[SongRow]): HtmlElement =
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
      // Tempo and signature come before the title: they are the two a performer checks against the
      // count-in, and next to the pattern they read as the one thing they describe.
      input(cls := "bpm", controlled(value <-- rowSignal.map(_.bpm), onInput.mapToValue --> (v => updateRow(id)(_.copy(bpm = v))))),
      input(cls := "sign",
        cls("common") <-- rowSignal.map(r => isFourFour(r.sign)),
        controlled(value <-- rowSignal.map(_.sign), onInput.mapToValue --> (v => updateRow(id)(_.copy(sign = v))))),
      input(cls := "title", controlled(value <-- rowSignal.map(_.title), onInput.mapToValue --> (v => updateRow(id)(_.copy(title = v))))),
      input(cls := "pattern", controlled(value <-- rowSignal.map(_.pattern), onInput.mapToValue --> (v => updateRow(id)(_.copy(pattern = v.replace("…", "...")))))),
      button("Remove", onClick --> (_ => removeRow(id))),
      moveButtons(id),
    )

  /** Any question the app must not act without an answer to. Cancel takes the focus, so the reflex
    * answers — a stray Return or space bar on the keys — leave everything as it is. Clicking the
    * darkened page behind it cancels too, which is what a tap outside a dialog usually means. */
  def renderAsk(ask: Ask): HtmlElement =
    div(cls := "backdrop",
      onClick --> (_ => answerAsk(yes = false)),
      div(cls := "dialog",
        // the click that answers must not also count as a click on the page behind
        onClick.stopPropagation --> (_ => ()),
        h2(ask.heading),
        p(
          ask.before,
          span(cls := "concertname", s"\"${ask.subject}\""),
          ask.after,
        ),
        div(cls := "row",
          button("Cancel", cls := "cancel", onMountFocus,
            onClick --> (_ => answerAsk(yes = false))),
          button(ask.confirm, cls := "discard",
            onClick --> (_ => answerAsk(yes = true))),
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
      if pendingAskVar.now().isDefined then
        if e.key == "Escape" then
          e.preventDefault()
          answerAsk(yes = false)
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
    // Naming a concert and picking one share a line: both answer "which concert", both are set
    // before the gig and left alone, and a line of screen is a song. "Local Store" is gone from
    // both labels — it said where things go, which nobody has a second choice about.
    div(cls := "row",
      span("Concert: "),
      input(cls := "concertfield",
        controlled(value <-- concertNameVar.signal, onInput.mapToValue --> concertNameVar.writer)),
      // Red while the table holds something the Local Store does not, so the warning when loading
      // is never the first news of it — and the button doing the telling is also the cure. This
      // replaced a line of text saying the same thing, which cost the width of the words.
      button("Save", cls := "save",
        cls("dirty") <-- dirtyVar.signal,
        title <-- dirtyVar.signal.map(d => if d then "unsaved changes" else "save this concert"),
        onClick --> (_ => save())),
      span(cls := "loadlabel", "Load: "),
      // Choosing loads: a Load button beside it only asked the same question twice. Controlled, so
      // a choice that is refused snaps back to the concert the table actually holds.
      select(
        cls := "concertfield",
        children <-- offeredVar.signal.map: offered =>
          offered.map: (name, builtIn) =>
            val key = keyOf(name, builtIn)
            option(value := key, labelOf(key))
        ,
        controlled(value <-- selectedVar.signal, onChange.mapToValue --> (n => chooseConcert(n))),
      ),
      // Only ever the saved copy, and only after asking. Disabled on a built-in: those ship with
      // the app, and a dropdown entry nobody can restore is not something a button should offer.
      button("Remove concert", cls := "removeconcert",
        disabled <-- selectedVar.signal.map(k => k.isEmpty || k.startsWith(builtInMark)),
        title <-- selectedVar.signal.map: k =>
          if k.startsWith(builtInMark) then "a built-in concert cannot be removed"
          else s"remove '$k' from the Local Store",
        onClick --> (_ => askRemoveConcert())),
      // Export and Import rather than Download and Upload: nothing travels. The app has no server,
      // and the file lands on the same machine the Local Store is already on.
      button("Export concert", cls := "export",
        title <-- concertNameVar.signal.map(n => s"write this concert to a file, ${fileNameOf(n)}"),
        onClick --> (_ => exportConcert())),
      // The chooser is a file input, which cannot be styled into the row, so it is hidden and the
      // button presses it. Its own change event is what carries the file, so it has to be a real
      // element in the page rather than one made on the spot.
      button("Import concert", cls := "import", title := "read a concert from a .tsv file",
        onClick --> (_ => Option(fileChooser).foreach(_.click()))),
      input(typ := "file", cls := "chooser",
        accept := ".tsv,.txt,text/plain,text/tab-separated-values",
        onMountCallback(ctx => fileChooser = ctx.thisNode.ref),
        onChange --> (ev => readChosen(ev.target.asInstanceOf[dom.html.Input])),
      ),
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
      // The arrow points where the cue goes, matching the arrow keys and the table below it: down
      // is the next song, up is the one before. Titled, since an arrow alone does not say what it
      // moves.
      button("↓", cls := "step", title := "move the cue to the song below",
        onClick --> (_ => moveCue(1))),
      button("↑", cls := "step", title := "move the cue to the song above",
        onClick --> (_ => moveCue(-1))),
      // one word each way, since the row has to fit a phone; the title says the rest
      button(cls := "fullscreen",
        title <-- fullScreenVar.signal.map(on => if on then "leave full screen" else "go full screen"),
        child.text <-- fullScreenVar.signal.map(on => if on then "Exit" else "Full"),
        onClick --> (_ => toggleFullScreen()),
      ),
      select(
        cls := "bars",
        title := "bars to play before stopping",
        barChoices.map(b => option(value := b, b)),
        value <-- barsVar.signal,
        onChange.mapToValue --> barsVar.writer,
      ),
      // Down here with the cue's own controls, because that is what they act on: both put their
      // row under the ">". "Add song", not "Add", since the row also carries a concert dropdown's
      // worth of meaning nearby and "Add" alone would not say which.
      button("Add song", cls := "addsong", title := "add an empty song under the cue",
        onClick --> (_ => addSong())),
      button("Add pause", cls := "addpause", title := "add a pause under the cue",
        onClick --> (_ => addPause())),
      span("Vol: "),
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
      span("@"), span("Play"), span("BPM"), span("Sig."), span("Title"), span("Pattern"),
      span(), span(), span(),
    ),
    children <-- songsVar.signal.split(_.id)(renderRow),
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
    child.maybe <-- pendingAskVar.signal.map(_.map(renderAsk)),
  )
