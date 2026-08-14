package prontopop

import Model.*

/** The arithmetic behind the click, with no audio clock in sight.
  *
  * Everything here is a pure function of numbers, so it runs and is tested on the JVM. The player in
  * `Sound.scala` keeps only the parts that genuinely need a browser: an `AudioContext`, its clock,
  * and a timer that asks this code what is due next.
  *
  * The property that matters for a metronome is that a beat's time is COMPUTED from its index, never
  * accumulated by adding one interval to the last. Accumulation drifts as rounding errors pile up;
  * `timeOf` cannot, because it multiplies out from `startTime` every time. */
object Timing:

  /** One event and where it falls, in beats from the start of the loop. */
  case class Beat(offset: Double, ev: Event)

  /** A pattern laid out for playing: its beats in order, and how many beats one loop lasts.
    * `loopBeats` is the length of the whole pattern, not of a bar. */
  case class Schedule(beats: Vector[Beat], loopBeats: Double):
    def isEmpty: Boolean = beats.isEmpty || loopBeats <= 0

  /** Where playback has got to: which beat, and how many times the pattern has come round. */
  case class Cursor(index: Int, loop: Int)

  val Start: Cursor = Cursor(0, 0)

  /** A position inside a bar, expressed in beats — where a beat is one `1/beatsPerWhole` note. */
  def beatsOf(frac: Frac, beatsPerWhole: Int): Double =
    frac.numerator.toDouble * beatsPerWhole / frac.denominator

  def secsPerBeat(bpm: BPM): Double = 60.0 / bpm

  /** Lay bars end to end. Each bar contributes its signature's numerator worth of beats, so bars of
    * different signatures can follow one another. */
  def schedule(bars: Seq[Bar]): Schedule =
    val beats = Vector.newBuilder[Beat]
    var barStart = 0.0
    for bar <- bars do
      val sig = bar.signature.frac
      for e <- bar.events do
        beats += Beat(barStart + beatsOf(e.pos.frac, sig.denominator), e.ev)
      barStart += sig.numerator
    Schedule(beats.result(), barStart)

  /** How many beats pass before `barCount` bars have gone by, cycling the pattern as often as
    * needed. Counts the bars themselves rather than multiplying, since a pattern may mix
    * signatures and its bars then differ in length. */
  def beatsForBars(bars: Seq[Bar], barCount: Int): Double =
    if barCount <= 0 || bars.isEmpty then 0.0
    else Iterator.continually(bars).flatten.take(barCount)
      .map(_.signature.frac.numerator.toDouble).sum

  /** Where the cursor stands, in beats from the very start of playing. */
  def beatsAt(sched: Schedule, cursor: Cursor): Double =
    cursor.loop * sched.loopBeats + sched.beats(cursor.index).offset

  /** True once the cursor has reached a bar limit, so nothing further should sound. */
  def finished(sched: Schedule, cursor: Cursor, untilBeats: Double): Boolean =
    !sched.isEmpty && beatsAt(sched, cursor) >= untilBeats

  /** The next beat, wrapping to the top of the pattern and counting the loop. */
  def advance(sched: Schedule, cursor: Cursor): Cursor =
    if cursor.index + 1 >= sched.beats.length then Cursor(0, cursor.loop + 1)
    else Cursor(cursor.index + 1, cursor.loop)

  /** When the beat under the cursor sounds, on the same clock `startTime` came from. */
  def timeOf(sched: Schedule, cursor: Cursor, startTime: Double, secsPerBeat: Double): Double =
    startTime + (cursor.loop * sched.loopBeats + sched.beats(cursor.index).offset) * secsPerBeat

  /** Everything falling at or before `horizon`, in order, and where the cursor ends up.
    *
    * This is the scheduling loop: the player calls it on a timer with a horizon a little ahead of
    * the audio clock, hands the results to the audio graph, and keeps the returned cursor. Beats
    * strictly advance in time, so the loop always terminates; an empty or zero-length schedule
    * yields nothing rather than spinning. */
  def due(
    sched: Schedule,
    cursor: Cursor,
    startTime: Double,
    secsPerBeat: Double,
    horizon: Double,
    untilBeats: Double = Double.PositiveInfinity,
  ): (Vector[(Double, Event)], Cursor) =
    if sched.isEmpty || secsPerBeat <= 0 then (Vector.empty, cursor)
    else
      val out = Vector.newBuilder[(Double, Event)]
      var at = cursor
      var t = timeOf(sched, at, startTime, secsPerBeat)
      // the limit is checked here, not by stopping the timer later: the lookahead would otherwise
      // have already scheduled a click or two past the end, and they would sound
      while t <= horizon && beatsAt(sched, at) < untilBeats do
        out += ((t, sched.beats(at.index).ev))
        at = advance(sched, at)
        t = timeOf(sched, at, startTime, secsPerBeat)
      (out.result(), at)
