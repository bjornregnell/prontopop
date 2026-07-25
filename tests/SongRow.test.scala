package prontopop.tests

import prontopop.Model.*
import prontopop.SongRow

/** The string-valued table row, and the conversions between it and the typed model. */
class SongRowSuite extends munit.FunSuite:

  val song = Song("A song", 120, Signature(3, 4), Pattern("||:!..|X..:||"))

  test("a whole bpm shows without a decimal point"):
    assertEquals(SongRow.showBpm(120), "120")
    assertEquals(SongRow.showBpm(96), "96")

  test("a fractional bpm keeps its decimals"):
    assertEquals(SongRow.showBpm(108.5), "108.5")

  test("a signature shows as numerator over denominator"):
    assertEquals(SongRow.showSignature(Signature(7, 8)), "7/8")

  test("a signature parses back from what it showed"):
    assertEquals(SongRow.parseSignature("7/8"), Right(Signature(7, 8)))
    assertEquals(SongRow.parseSignature("  3 / 4 "), Right(Signature(3, 4)))

  test("a signature that is not two positive numbers is rejected with the offending text"):
    assert(SongRow.parseSignature("4").isLeft, "no denominator")
    assert(SongRow.parseSignature("0/4").isLeft, "zero numerator")
    assert(SongRow.parseSignature("3/x").isLeft, "denominator not a number")

  test("a song becomes a row of exactly what the table should display"):
    val row = SongRow.from(1, song)
    assertEquals(row.id, 1)
    assertEquals(row.title, "A song")
    assertEquals(row.bpm, "120")
    assertEquals(row.sign, "3/4")
    assertEquals(row.pattern, "||:!..|X..:||")

  test("a row round-trips back to the song it came from"):
    assertEquals(SongRow.from(1, song).toSong, Right(song))

  test("a valid row yields the bars to play"):
    SongRow.from(1, song).toSongAndBars match
      case Left(err)            => fail(s"should have parsed: $err")
      case Right((s, bars)) =>
        assertEquals(s.bpm, 120.0)
        assertEquals(bars.length, 2)

  test("a row with a bad bpm names the bpm"):
    val row = SongRow.from(1, song).copy(bpm = "fast")
    assertEquals(row.toSongAndBars, Left("bad bpm 'fast'"))

  test("a bpm of zero or less is not playable"):
    assert(SongRow.from(1, song).copy(bpm = "0").toSongAndBars.isLeft, "zero")
    assert(SongRow.from(1, song).copy(bpm = "-90").toSongAndBars.isLeft, "negative")

  test("a row with a bad signature names the signature"):
    val row = SongRow.from(1, song).copy(sign = "three quarters")
    assert(row.toSongAndBars.left.exists(_.contains("bad signature")), "says which field")

  test("a row whose pattern disagrees with its signature reports the position"):
    val row = SongRow.from(1, song).copy(sign = "4/4")
    assert(row.toSongAndBars.left.exists(_.startsWith("pattern error at")), "carries a position")

  test("a fresh row's default pattern is playable in its default signature"):
    assert(SongRow(1).toSongAndBars.isRight, "the Add song default must work out of the box")
