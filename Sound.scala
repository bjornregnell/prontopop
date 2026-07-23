package prontopop

import org.scalajs.dom
import scala.scalajs.js

object Sound:
  import Model.*

  trait SoundPlayer:
    /** Loop bars at bpm until stop(); replaces whatever was playing. */
    def play(bpm: BPM, bars: Seq[Bar]): Unit
    def stop(): Unit
    def isPlaying: Boolean

  /** WebAudio-backed player. Call play from a user gesture so the browser lets audio start. */
  def initWebSound(): SoundPlayer = WebAudioPlayer()

  private class WebAudioPlayer extends SoundPlayer:
    private val lookahead   = 0.1   // seconds scheduled ahead of currentTime for rock-solid timing
    private val tickMs      = 25.0  // scheduler wake-up interval
    private val clickLength = 0.05  // seconds from click attack to silence

    private var ctxOpt = Option.empty[dom.AudioContext]
    private var timer  = Option.empty[js.timers.SetIntervalHandle]

    private var events      = Vector.empty[(offset: Double, ev: Event)]  // offsets in beats from loop start
    private var loopBeats   = 0.0
    private var secsPerBeat = 0.5
    private var startTime   = 0.0  // ctx time of beat 0 of loop 0
    private var nextIndex   = 0
    private var loopCount   = 0

    private def ctx: dom.AudioContext =
      ctxOpt.getOrElse:
        val c = new dom.AudioContext()
        ctxOpt = Some(c)
        c

    private def beatsOf(f: Frac, beatsPerWhole: Int): Double =
      f.numerator.toDouble * beatsPerWhole / f.denominator

    def play(bpm: BPM, bars: Seq[Bar]): Unit =
      stop()
      if bars.nonEmpty && bpm > 0 then
        val c = ctx
        if c.state == "suspended" then c.resume()
        val scheduled = Vector.newBuilder[(offset: Double, ev: Event)]
        var barStart = 0.0
        for bar <- bars do
          val sig = bar.signature.frac
          for e <- bar.events do
            scheduled += ((offset = barStart + beatsOf(e.pos.frac, sig.denominator), ev = e.ev))
          barStart += sig.numerator
        events = scheduled.result()
        loopBeats = barStart
        secsPerBeat = 60.0 / bpm
        startTime = c.currentTime + 0.05
        nextIndex = 0
        loopCount = 0
        timer = Some(js.timers.setInterval(tickMs)(tick()))

    def stop(): Unit =
      timer.foreach(js.timers.clearInterval)
      timer = None

    def isPlaying: Boolean = timer.nonEmpty

    private def timeOfNext: Double =
      startTime + (loopCount * loopBeats + events(nextIndex).offset) * secsPerBeat

    private def tick(): Unit =
      val horizon = ctx.currentTime + lookahead
      var t = timeOfNext
      while t <= horizon do
        playEvent(events(nextIndex).ev, t)
        nextIndex += 1
        if nextIndex >= events.length then
          nextIndex = 0
          loopCount += 1
        t = timeOfNext

    private def playEvent(ev: Event, t: Double): Unit = ev match
      case DrumHit(drum, velocity) => tone(drumFreq(drum), velocity, t, clickLength)
      case NoteOn(_, pitch, velocity, duration) =>
        val secs = duration.numerator.toDouble / duration.denominator * 4 * secsPerBeat  // whole note = 4 beats until instruments get real synthesis
        tone(midiFreq(pitch), velocity, t, secs)
      case _ => ()  // Rest is silence

    /** One decaying sine blip; velocity scales gain AND pitch so an accent is louder and higher. */
    private def tone(baseFreq: Double, velocity: Velocity, t: Double, length: Double): Unit =
      val c = ctx
      val v = velocity.max(1).min(127) / 127.0
      val osc  = c.createOscillator()
      val gain = c.createGain()
      osc.frequency.value = baseFreq * (0.75 + 0.5 * v)
      gain.gain.setValueAtTime(v, t)
      gain.gain.exponentialRampToValueAtTime(0.001, t + length)
      osc.connect(gain)
      gain.connect(c.destination)
      osc.start(t)
      osc.stop(t + length + 0.01)

    private def drumFreq(drum: Drum): Double = drum match
      case Drum.HiHat => 1400
      case Drum.Snare => 700
      case Drum.Bongo => 400
      case Drum.Base  => 120

    private def midiFreq(pitch: Pitch): Double =
      440.0 * math.pow(2, (pitch - 69) / 12.0)
