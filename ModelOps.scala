package prontopop

object ModelOps:
  import Model.*
  extension (p: Pattern)
    def parse: Either[Error, Seq[Bar]] = ???