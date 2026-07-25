package prontopop

object Concerts:
  import Model.*

  val Example01 = "Example01" -> Seq(
    Song(title = "Song title example 1", 120, Signature(3, 4), Pattern("||:!..|X..|X..|X..:||")),
    Song(title = "Song title example 2", 108, Signature(4, 4), Pattern("||:!...|X...|X...|X...:||")),
  )

  val Soaree01 = "Soaree01" -> Seq(
    Song(title = "Rymdresan - vi kommer aldrig tillbaka", 120, Signature(3, 4), Pattern("||:!..|X..|X..|X..:||")),
    Song(title = "Hopp om en ofri", 108, Signature(3, 4), Pattern("||:!..|X..|X..|X..:||")),
  )

  /** The concerts in the order they should be offered.
    *
    * Declared AFTER the concerts it names, and deliberately a Seq: object vals initialize in source
    * order, so naming them earlier would read nulls, and a Map stops preserving insertion order
    * once it holds more than four entries. */
  val ordered: Seq[(Title, Concert)] = Seq(Example01, Soaree01)

  val titles: Seq[Title] = ordered.map((title, _) => title)

  val all: Map[Title, Concert] = ordered.toMap

  /** What the app opens on, so no caller has to name a key that might move. */
  val startupTitle: Title = Example01._1
  val startup: Concert    = Example01._2
