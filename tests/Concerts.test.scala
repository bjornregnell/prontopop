package prontopop.tests

import prontopop.Concerts
import prontopop.SongRow

/** The built-in concerts. The first test is a regression guard: `all` once threw at initialization
  * because it was declared above the vals it names, and object vals initialize in source order. */
class ConcertsSuite extends munit.FunSuite:

  test("all initializes without throwing"):
    assertEquals(Concerts.all.size, Concerts.ordered.size)

  test("titles keep the order they are declared in, which a Map would not"):
    assertEquals(Concerts.titles, Concerts.ordered.map((title, _) => title))

  test("every title resolves to the concert it was declared with"):
    Concerts.ordered.foreach: (title, concert) =>
      assertEquals(Concerts.all.get(title), Some(concert))

  test("the startup concert is one of the built-ins"):
    assertEquals(Concerts.all.get(Concerts.startupTitle), Some(Concerts.startup))

  test("no two built-in concerts share a title"):
    assertEquals(Concerts.titles.distinct.size, Concerts.titles.size)

  test("no built-in concert is empty"):
    Concerts.ordered.foreach: (title, concert) =>
      assert(concert.nonEmpty, s"'$title' has no songs")

  test("every built-in song is playable: its pattern agrees with its signature"):
    for
      (title, concert) <- Concerts.ordered
      song             <- concert
    do
      val row = SongRow.from(1, song)
      assert(
        row.toSongAndBars.isRight,
        s"'$title' / '${song.title}' does not play: ${row.toSongAndBars.left.getOrElse("")}",
      )

  test("every built-in song survives the trip through the table unchanged"):
    for
      (_, concert) <- Concerts.ordered
      song         <- concert
    do assertEquals(SongRow.from(1, song).toSong, Right(song))
