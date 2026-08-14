package prontopop

import org.scalajs.dom
import scala.scalajs.js

object Sound:
  import Model.*

  trait SoundPlayer:
    /** Loop bars at bpm, replacing whatever was playing.
      *
      * @param untilBars how many bars to play before stopping; None loops until stop()
      * @param onEnded   called when a bar limit runs out, NOT when stop() is called — the caller
      *                  already knows about its own stop, but needs telling about this one
      */
    def play(bpm: BPM, bars: Seq[Bar], untilBars: Option[Int], onEnded: () => Unit): Unit
    def stop(): Unit
    def isPlaying: Boolean
    /** Master volume 0.0..1.0, effective immediately, also while playing. */
    def setVolume(volume: Double): Unit

  /** WebAudio-backed player. Call play from a user gesture so the browser lets audio start. */
  def initWebSound(): SoundPlayer = WebAudioPlayer()

  private class WebAudioPlayer extends SoundPlayer:
    private val lookahead   = 0.1   // seconds scheduled ahead of currentTime for rock-solid timing
    private val tickMs      = 25.0  // scheduler wake-up interval
    private val clickLength = 0.05  // seconds from click attack to silence

    private var ctxOpt    = Option.empty[dom.AudioContext]
    private var masterOpt = Option.empty[dom.GainNode]  // master volume, all tones route through it
    private var volume    = 1.0
    private var timer     = Option.empty[js.timers.SetIntervalHandle]
    // fires after the last scheduled click has actually sounded, so the UI does not say "stopped"
    // while the final beat is still in the air
    private var endTimer  = Option.empty[js.timers.SetTimeoutHandle]
    private var untilBeats = Double.PositiveInfinity
    private var whenEnded: () => Unit = () => ()

    // What to play and how far we have got. The arithmetic lives in Timing, which is pure and
    // tested on the JVM; this class keeps only the audio clock and the graph.
    private var sched       = Timing.Schedule(Vector.empty, 0.0)
    private var cursor      = Timing.Start
    private var secsPerBeat = 0.5
    private var startTime   = 0.0  // ctx time of beat 0 of loop 0

    private def ctx: dom.AudioContext =
      ctxOpt.getOrElse:
        val c = new dom.AudioContext()
        val master = c.createGain()
        master.gain.value = volume
        master.connect(c.destination)
        ctxOpt = Some(c)
        masterOpt = Some(master)
        c

    def setVolume(v: Double): Unit =
      volume = v.max(0.0).min(1.0)
      masterOpt.foreach(_.gain.value = volume)

    def play(bpm: BPM, bars: Seq[Bar], untilBars: Option[Int], onEnded: () => Unit): Unit =
      stop()
      val laid = Timing.schedule(bars)
      if !laid.isEmpty && bpm > 0 then
        val c = ctx
        if c.state == "suspended" then c.resume()
        sched = laid
        secsPerBeat = Timing.secsPerBeat(bpm)
        startTime = c.currentTime + 0.05
        cursor = Timing.Start
        untilBeats = untilBars.map(n => Timing.beatsForBars(bars, n)).getOrElse(Double.PositiveInfinity)
        whenEnded = onEnded
        timer = Some(js.timers.setInterval(tickMs)(tick()))

    /** Stop asking Timing for more beats. Anything already handed to the audio graph still sounds,
      * which is what lets a bar limit finish cleanly. */
    private def stopTicking(): Unit =
      timer.foreach(js.timers.clearInterval)
      timer = None

    def stop(): Unit =
      stopTicking()
      endTimer.foreach(js.timers.clearTimeout)
      endTimer = None

    def isPlaying: Boolean = timer.nonEmpty || endTimer.nonEmpty

    /** Ask Timing what falls inside the lookahead window, hand it to the audio graph, keep the
      * cursor it gives back. All the arithmetic that could go wrong happens in there. */
    private def tick(): Unit =
      val horizon = ctx.currentTime + lookahead
      val (beats, next) = Timing.due(sched, cursor, startTime, secsPerBeat, horizon, untilBeats)
      beats.foreach((t, ev) => playEvent(ev, t))
      cursor = next
      if Timing.finished(sched, cursor, untilBeats) then
        // every beat is now in the audio graph, so stop scheduling and wait out the last one
        stopTicking()
        val endsAt = startTime + untilBeats * secsPerBeat
        val waitMs = ((endsAt - ctx.currentTime) * 1000 + clickLength * 1000).max(0)
        endTimer = Some(js.timers.setTimeout(waitMs):
          endTimer = None
          whenEnded()
        )

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
      masterOpt.foreach(gain.connect)
      osc.start(t)
      osc.stop(t + length + 0.01)

    private def drumFreq(drum: Drum): Double = drum match
      case Drum.HiHat => 1400
      case Drum.Snare => 700
      case Drum.Bongo => 400
      case Drum.Base  => 120

    private def midiFreq(pitch: Pitch): Double =
      440.0 * math.pow(2, (pitch - 69) / 12.0)
