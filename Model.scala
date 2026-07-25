package prontopop

object Model:

  type Title       = String
  type BPM         = Double
  type Numerator   = Int
  type Denominator = Int
  type Velocity    = Int
  type DSL         = String
  type Pitch       = Int
  type Concert     = Seq[Song]

  case class Frac(numerator: Numerator, denominator: Denominator)

  trait Sound(val isOneOff: Boolean)

  enum Drum extends Sound(isOneOff = true):
    case HiHat, Snare, Base, Bongo

  enum Instrument extends Sound(isOneOff = false):
    case Piano, Guitar


  trait Event
  case class NoteOn(instrument: Instrument, pitch: Pitch, velocity: Velocity, duration: Frac) extends Event
  case class DrumHit(drum: Drum, velocity: Velocity) extends Event
  case class Rest(duration: Frac) extends Event

  case class PosInBar(frac: Frac)

  case class Signature(frac: Frac)
  object Signature:
    def apply(n: Numerator, d: Denominator): Signature = Signature(Frac(n, d))

  case class Bar(events: Seq[(pos: PosInBar, ev: Event)], signature: Signature)

  case class Pattern(dsl: DSL)

  case class Song(title: Title, bpm: BPM, signature: Signature, pattern: Pattern)

  enum Error:
    case ParseError(msg: String, pos: Int)  // TODO add more errors when needed
