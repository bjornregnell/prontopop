//> using scala 3.9.0-RC4
//> using test.dep org.scalameta::munit::1.3.4

package prontopop.tests

import prontopop.Model.*
import prontopop.ModelOps.*
import prontopop.ModelOps.Dsl.*

/** The pattern DSL: one character per beat, validated against the song's time signature. */
class ModelOpsSuite extends munit.FunSuite:

  val sig34 = Signature(3, 4)
  val sig44 = Signature(4, 4)

  val mockup = Pattern("||:!..|X..|X..|X..:||")

  test("a looping pattern parses into one bar per bar-separator"):
    val bars = mockup.parse(sig34).fold(e => fail(s"did not parse: $e"), identity)
    assertEquals(bars.length, 4)
    assert(bars.forall(_.events.length == 3), "every bar holds three beats")

  test("'!' is an accented hit, 'X' a normal one and '.' a soft one"):
    val bars = mockup.parse(sig34).fold(e => fail(s"did not parse: $e"), identity)
    assertEquals(bars.head.events.head.ev, DrumHit(clickDrum, accentVelocity))
    assertEquals(bars.head.events(1).ev, DrumHit(clickDrum, softVelocity))
    assertEquals(bars(1).events.head.ev, DrumHit(clickDrum, clickVelocity))

  test("a soft click is quieter than a normal one, which is quieter than an accent"):
    assert(softVelocity < clickVelocity, "soft below normal")
    assert(clickVelocity < accentVelocity, "normal below accent")

  test("'_' is a silent beat, not a quiet one"):
    val bars = Pattern("!.._").parse(sig44).fold(e => fail(s"did not parse: $e"), identity)
    assertEquals(bars.head.events(3).ev, Rest(Frac(1, 4)))

  test("beat positions count up over the signature's denominator"):
    val bars = Pattern("!..").parse(sig34).fold(e => fail(s"did not parse: $e"), identity)
    val expected = Vector(PosInBar(Frac(0, 4)), PosInBar(Frac(1, 4)), PosInBar(Frac(2, 4)))
    assertEquals(bars.head.events.map(_.pos).toVector, expected)

  test("a bar remembers the signature it was validated against"):
    val bars = Pattern("!..").parse(sig34).fold(e => fail(s"did not parse: $e"), identity)
    assertEquals(bars.head.signature, sig34)

  test("loop markers are optional but must come in pairs"):
    assert(Pattern("!..|X..").parse(sig34).isRight, "no markers is fine")
    assert(Pattern("||:!..").parse(sig34).isLeft, "opened but never closed")
    assert(Pattern("!..:||").parse(sig34).isLeft, "closed but never opened")

  test("whitespace is ignored, so a pattern may be spaced out for reading"):
    assertEquals(Pattern("||: !.. | X.. :||").parse(sig34), Pattern("||:!..|X..:||").parse(sig34))

  test("a smart-punctuation ellipsis counts as three soft clicks"):
    val bars = Pattern("||:!…:||").parse(sig44).fold(e => fail(s"did not parse: $e"), identity)
    assertEquals(bars.head.events.map(_.ev).toVector, Vector(
      DrumHit(clickDrum, accentVelocity),
      DrumHit(clickDrum, softVelocity),
      DrumHit(clickDrum, softVelocity),
      DrumHit(clickDrum, softVelocity),
    ))

  test("a bar with the wrong number of beats is reported at the bar's first character"):
    assertEquals(
      Pattern("!..|X.").parse(sig34),
      Left(Error.ParseError("bar has 2 beats but signature is 3/4", 4)),
    )

  test("an unknown character is reported at its own position"):
    assertEquals(Pattern("!.?").parse(sig34), Left(Error.ParseError("unexpected character '?'", 2)))

  test("a pattern with no beats is an error, with or without markers"):
    assert(Pattern("").parse(sig34).isLeft, "empty")
    assert(Pattern("||::||").parse(sig34).isLeft, "markers only")
