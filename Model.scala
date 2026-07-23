package prontopop

object Model:
  
  type Title       = String
  type BPM         = Double
  type Numerator   = Int
  type Denominator = Int
  type Velocity    = Int
  type DSL         = String
  type Pitch       = Int

  case class Frac(numerator: Numerator, denominatior: Denominator)
  
  trait Sound
  
  enum Drum extends Sound:
    case HiHat, Snare, Base, Bongo 

  enum Instrument extends Sound:
    case Piano, Guitar
  
  
  trait Event
  case class NoteOn(instrument: Instrument, pitch: Pitch, velocity: Velocity, duration: Frac) extends Event
  case class DrumHit(drum: Drum, velocity: Velocity) extends Event 
  case class Rest(duration: Frac)

  case class PosInBar(frac: Frac)

  case class Signature(frac: Frac)

  case class Bar(events: Seq[(pos: PosInBar, ev: Event)], signature: Frac)

  case class Pattern(dsl: DSL)

  case class Song(title: Title, bpm: BPM, pattern: Pattern)

  enum Error:
    case ParseError(msg: String, pos: Int)  // TODO add more errors when needed
