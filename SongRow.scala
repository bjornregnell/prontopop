package prontopop

import Model.*
import ModelOps.*

/** One editable row of the song table. Every field is a String on purpose: the performer types
  * freely and gets a parse error in the status line, rather than a rejected keystroke. */
case class SongRow(
  id: Int,
  title: String = "",
  bpm: String = "120",
  sign: String = "4/4",
  pattern: String = "||:!...:||",
):

  /** Validate the typed fields into the model, yielding the song and the bars to play, or a
    * message naming what is wrong. Shared by playing now and (later) saving a typed concert. */
  def toSongAndBars: Either[String, (Song, Seq[Bar])] =
    for
      beats <- bpm.trim.toDoubleOption.filter(_ > 0).toRight(s"bad bpm '$bpm'")
      sig   <- SongRow.parseSignature(sign)
      bars  <- Pattern(pattern).parse(sig).left.map:
                 case Error.ParseError(msg, pos) => s"pattern error at $pos: $msg"
    yield (Song(title, beats, sig, Pattern(pattern)), bars)

  def toSong: Either[String, Song] = toSongAndBars.map((song, _) => song)

  /** A break in the concert rather than a song: nothing to play, and nothing to type. Marked by a
    * negative id, so a pause is told from a song by the one field every row already has. */
  def isPause: Boolean = id < 0

object SongRow:

  /** The shape of a pause row. Its id is replaced with a fresh NEGATIVE one as it is made — every
    * row needs its own, or two pauses in a concert would be the same row to the table, and
    * removing one would remove both. */
  val Empty = SongRow(id = -1, title = "Pause")

  /** A whole bpm must read "120", never "120.0". */
  def showBpm(bpm: BPM): String =
    if bpm.isFinite && bpm == bpm.floor then bpm.toLong.toString else bpm.toString

  def showSignature(sig: Signature): String = s"${sig.frac.numerator}/${sig.frac.denominator}"

  def parseSignature(s: String): Either[String, Signature] = s.trim.split("/") match
    case Array(n, d) if n.trim.toIntOption.exists(_ > 0) && d.trim.toIntOption.exists(_ > 0) =>
      Right(Signature(n.trim.toInt, d.trim.toInt))
    case _ => Left(s"bad signature '$s', expected like 3/4")

  /** The model going the other way, for a built-in concert arriving in the table. */
  def from(id: Int, song: Song): SongRow =
    SongRow(id, song.title, showBpm(song.bpm), showSignature(song.signature), song.pattern.dsl)
