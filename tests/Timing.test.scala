package prontopop.tests

import prontopop.Model.*
import prontopop.ModelOps.*
import prontopop.Timing
import prontopop.Timing.{Beat, Cursor, Schedule}

/** The click's arithmetic, tested without any audio clock. */
class TimingSuite extends munit.FunSuite:

  val sig34 = Signature(3, 4)
  val sig44 = Signature(4, 4)

  /** Four bars of 3/4: twelve beats, one per beat. */
  val bars34 = Pattern("||:!..|X..|X..|X..:||").parse(sig34)
    .fold(e => fail(s"fixture did not parse: $e"), identity)

  val sched = Timing.schedule(bars34)

  def at(cursor: Cursor, startTime: Double = 0.0, spb: Double = 0.5): Double =
    Timing.timeOf(sched, cursor, startTime, spb)

  // ---- laying out a pattern ----

  test("a beat position becomes beats relative to the signature's beat unit"):
    assertEqualsDouble(Timing.beatsOf(Frac(0, 4), 4), 0.0, 1e-12)
    assertEqualsDouble(Timing.beatsOf(Frac(1, 4), 4), 1.0, 1e-12)
    assertEqualsDouble(Timing.beatsOf(Frac(2, 4), 4), 2.0, 1e-12)
    assertEqualsDouble(Timing.beatsOf(Frac(1, 8), 8), 1.0, 1e-12)

  test("bpm becomes seconds per beat"):
    assertEqualsDouble(Timing.secsPerBeat(120), 0.5, 1e-12)
    assertEqualsDouble(Timing.secsPerBeat(60), 1.0, 1e-12)

  test("four bars of 3/4 lay out as twelve beats, one beat apart, over a twelve-beat loop"):
    assertEquals(sched.beats.length, 12)
    assertEqualsDouble(sched.loopBeats, 12.0, 1e-12)
    assertEquals(sched.beats.map(_.offset).toVector, (0 until 12).map(_.toDouble).toVector)

  test("bars of different signatures follow one another without a gap or overlap"):
    val mixed = Timing.schedule(
      Pattern("!..").parse(sig34).toOption.get ++ Pattern("!...").parse(sig44).toOption.get
    )
    assertEquals(mixed.beats.map(_.offset).toVector, Vector(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
    assertEqualsDouble(mixed.loopBeats, 7.0, 1e-12)

  test("an empty schedule is recognised as empty, so nothing tries to play it"):
    assert(Timing.schedule(Nil).isEmpty, "no bars")
    assert(Schedule(Vector(Beat(0, Rest(Frac(1, 4)))), 0.0).isEmpty, "zero-length loop")

  // ---- the cursor ----

  test("the cursor walks the pattern and wraps into the next loop"):
    assertEquals(Timing.advance(sched, Cursor(0, 0)), Cursor(1, 0))
    assertEquals(Timing.advance(sched, Cursor(11, 0)), Cursor(0, 1))
    assertEquals(Timing.advance(sched, Cursor(11, 7)), Cursor(0, 8))

  test("a beat's time is start plus its whole-loop offset in seconds"):
    assertEqualsDouble(at(Cursor(0, 0), startTime = 10.0), 10.0, 1e-12)
    assertEqualsDouble(at(Cursor(3, 0), startTime = 10.0), 11.5, 1e-12)
    assertEqualsDouble(at(Cursor(0, 1), startTime = 10.0), 16.0, 1e-12)

  test("the first beat of the next loop follows the last beat by exactly one beat"):
    assertEqualsDouble(at(Cursor(0, 1)) - at(Cursor(11, 0)), 0.5, 1e-12)

  // ---- the property a metronome lives on ----

  test("beat times never drift, however long it plays"):
    // computed from the index rather than accumulated, so rounding cannot pile up
    val spb = Timing.secsPerBeat(137)  // deliberately not a round number
    val beat10k = Timing.timeOf(sched, Cursor(4, 833), 5.0, spb)
    val expected = 5.0 + (833 * 12 + 4) * spb
    assertEqualsDouble(beat10k, expected, 1e-9)

  test("every consecutive pair of beats is exactly one beat apart across a loop boundary"):
    val spb = Timing.secsPerBeat(137)
    // beats fall at 0, spb, 2*spb, ...; the horizon is inclusive, so 30*spb admits beats 0 to 30
    val (beats, _) = Timing.due(sched, Timing.Start, 0.0, spb, horizon = 30 * spb)
    assertEquals(beats.length, 31, "31 beats spans two loop boundaries at 12 beats per loop")
    val gaps = beats.map(_._1).sliding(2).collect { case Vector(a, b) => b - a }.toVector
    assertEquals(gaps.length, 30)
    gaps.foreach(g => assertEqualsDouble(g, spb, 1e-9))

  // ---- what falls due ----

  test("nothing is due before the first beat"):
    val (beats, next) = Timing.due(sched, Timing.Start, startTime = 10.0, 0.5, horizon = 9.9)
    assert(beats.isEmpty, "too early")
    assertEquals(next, Timing.Start, "the cursor does not move")

  test("a beat exactly on the horizon is due, so none is ever skipped"):
    val (beats, _) = Timing.due(sched, Timing.Start, startTime = 10.0, 0.5, horizon = 10.0)
    assertEquals(beats.length, 1)

  test("everything inside the window comes back in order, with the cursor left after it"):
    val (beats, next) = Timing.due(sched, Timing.Start, startTime = 0.0, 0.5, horizon = 1.2)
    assertEquals(beats.map(_._1).toVector, Vector(0.0, 0.5, 1.0))
    assertEquals(next, Cursor(3, 0))
    assertEquals(beats.head._2, DrumHit(Dsl.clickDrum, Dsl.accentVelocity), "the accent leads")

  test("resuming from the returned cursor replays nothing and skips nothing"):
    val (first, mid)  = Timing.due(sched, Timing.Start, 0.0, 0.5, horizon = 1.2)
    val (second, end) = Timing.due(sched, mid, 0.0, 0.5, horizon = 2.2)
    val together      = (first ++ second).map(_._1)
    assertEquals(together.toVector, Vector(0.0, 0.5, 1.0, 1.5, 2.0))
    assertEquals(together.distinct.size, together.size, "no beat sounded twice")
    assertEquals(end, Cursor(5, 0))

  test("a window spanning the loop boundary keeps the beat spacing"):
    val (beats, _) = Timing.due(sched, Cursor(10, 0), 0.0, 0.5, horizon = 6.5)
    assertEquals(beats.map(_._1).toVector, Vector(5.0, 5.5, 6.0, 6.5))

  // ---- stopping after a number of bars ----

  test("a bar limit counts bars, cycling the pattern as often as needed"):
    assertEqualsDouble(Timing.beatsForBars(bars34, 1), 3.0, 1e-12)
    assertEqualsDouble(Timing.beatsForBars(bars34, 4), 12.0, 1e-12, "one full pass of four 3/4 bars")
    assertEqualsDouble(Timing.beatsForBars(bars34, 6), 18.0, 1e-12, "one and a half passes")

  test("bars of different signatures each count their own length"):
    val mixed = Pattern("!..").parse(sig34).toOption.get ++ Pattern("!...").parse(sig44).toOption.get
    assertEqualsDouble(Timing.beatsForBars(mixed, 1), 3.0, 1e-12, "the 3/4 bar")
    assertEqualsDouble(Timing.beatsForBars(mixed, 2), 7.0, 1e-12, "plus the 4/4 bar")
    assertEqualsDouble(Timing.beatsForBars(mixed, 3), 10.0, 1e-12, "then round to the 3/4 bar again")

  test("no bars at all means no beats"):
    assertEqualsDouble(Timing.beatsForBars(bars34, 0), 0.0, 1e-12)
    assertEqualsDouble(Timing.beatsForBars(Nil, 4), 0.0, 1e-12)

  test("nothing is due beyond the bar limit, however far the horizon reaches"):
    val limit = Timing.beatsForBars(bars34, 2)  // six beats
    val (beats, _) = Timing.due(sched, Timing.Start, 0.0, 0.5, horizon = 1e6, untilBeats = limit)
    assertEquals(beats.length, 6, "six beats and not one more")
    assertEquals(beats.map(_._1).toVector, Vector(0.0, 0.5, 1.0, 1.5, 2.0, 2.5))

  test("the limit does not cut a beat that falls exactly on it short"):
    // twelve beats of limit must yield all twelve, beat 12 belonging to the next pass
    val (beats, at) = Timing.due(sched, Timing.Start, 0.0, 0.5, 1e6, untilBeats = 12.0)
    assertEquals(beats.length, 12)
    assert(Timing.finished(sched, at, 12.0), "and playing is over")

  test("a limit reports finished only once the cursor reaches it"):
    val (_, part) = Timing.due(sched, Timing.Start, 0.0, 0.5, horizon = 1.0, untilBeats = 12.0)
    assert(!Timing.finished(sched, part, 12.0), "still mid-pattern")
    assert(!Timing.finished(sched, Timing.Start, Double.PositiveInfinity), "forever never finishes")

  test("an empty schedule yields nothing instead of spinning forever"):
    val empty = Timing.schedule(Nil)
    val (beats, next) = Timing.due(empty, Timing.Start, 0.0, 0.5, horizon = 1e6)
    assert(beats.isEmpty, "nothing to play")
    assertEquals(next, Timing.Start)

  test("a nonsensical tempo yields nothing instead of spinning forever"):
    val (beats, next) = Timing.due(sched, Timing.Start, 0.0, secsPerBeat = 0.0, horizon = 1e6)
    assert(beats.isEmpty, "zero seconds per beat would make every beat due at once")
    assertEquals(next, Timing.Start)
