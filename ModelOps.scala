package prontopop

object ModelOps:
  import Model.*

  object Dsl:
    val LoopStart = "||:"
    val LoopEnd   = ":||"
    val Accent = '!'
    val Click  = 'X'
    val Soft   = '.'
    val Silent = '_'
    val BarSep = '|'
    val accentVelocity: Velocity = 127
    val clickVelocity:  Velocity = 90
    val softVelocity:   Velocity = 50
    val clickDrum = Drum.HiHat

  extension (p: Pattern)
    /** Parse dsl into bars validated against signature; error positions index into dsl. */
    def parse(signature: Signature): Either[Error, Seq[Bar]] =
      import Dsl.*
      val numerator    = signature.frac.numerator
      val denominator  = signature.frac.denominator
      val beatDuration = Frac(1, denominator)

      def beatEvent(c: Char): Option[Event] = c match
        case Accent => Some(DrumHit(clickDrum, accentVelocity))
        case Click  => Some(DrumHit(clickDrum, clickVelocity))
        case Soft   => Some(DrumHit(clickDrum, softVelocity))
        case Silent => Some(Rest(beatDuration))
        case _      => None

      val chars = p.dsl.zipWithIndex.filterNot((c, _) => c.isWhitespace).toVector

      def text(cs: Seq[(Char, Int)]): String = cs.map(_._1).mkString

      val body: Either[Error, Vector[(Char, Int)]] =
        val hasStart = text(chars.take(3)) == LoopStart
        val hasEnd   = text(chars.takeRight(3)) == LoopEnd
        if hasStart && hasEnd then Right(chars.drop(3).dropRight(3))
        else if hasStart then Left(Error.ParseError(s"missing loop end '$LoopEnd'", p.dsl.length))
        else if hasEnd then Left(Error.ParseError(s"missing loop start '$LoopStart'", chars.head._2))
        else Right(chars)

      def splitBars(cs: Vector[(Char, Int)]): Vector[Vector[(Char, Int)]] =
        cs.foldLeft(Vector(Vector.empty[(Char, Int)])):
          case (acc, (BarSep, _)) => acc :+ Vector.empty
          case (acc, ci)          => acc.init :+ (acc.last :+ ci)
        .filter(_.nonEmpty)

      def parseBar(seg: Vector[(Char, Int)]): Either[Error, Bar] =
        if seg.length != numerator then
          Left(Error.ParseError(s"bar has ${seg.length} beats but signature is $numerator/$denominator", seg.head._2))
        else
          val init: Either[Error, Vector[(pos: PosInBar, ev: Event)]] = Right(Vector.empty)
          seg.zipWithIndex.foldLeft(init):
            case (acc, ((c, i), beat)) =>
              for
                events <- acc
                ev     <- beatEvent(c).toRight(Error.ParseError(s"unexpected character '$c'", i))
              yield
                val entry = (pos = PosInBar(Frac(beat, denominator)), ev = ev)
                events :+ entry
          .map(events => Bar(events, signature))

      for
        cs   <- body
        segs  = splitBars(cs)
        _    <- if segs.isEmpty then Left(Error.ParseError("empty pattern", 0)) else Right(())
        bars <- segs.foldLeft(Right(Vector.empty): Either[Error, Vector[Bar]]): (acc, seg) =>
                  for
                    bs  <- acc
                    bar <- parseBar(seg)
                  yield bs :+ bar
      yield bars
