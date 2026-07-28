package prontopop

object Concerts:
  import Model.*

  val Example01 = "Example01" -> Seq(
    Song(title = "Song title example 1", 120, Signature(3, 4), Pattern("||:!..|X..|X..|X..:||")),
    Song(title = "Song title example 2", 108, Signature(4, 4), Pattern("||:!...|X...|X...|X...:||")),
  )

  // BEGIN GENERATED Soaree01 -- written by ./parsesoaree.sc from the songbook; do not edit by hand
  val Soaree01 = "Soaree01" -> Seq(
    Song(title = "Rymdresan", 155, Signature(3, 4), Pattern("||:!..:||")),
    Song(title = "Hopp om en ofri", 148, Signature(3, 4), Pattern("||:!..:||")),
    Song(title = "Slå en signal", 82, Signature(4, 4), Pattern("||:!...:||")),
    Song(title = "Värnhems skugga", 87, Signature(3, 4), Pattern("||:!..:||")),
    Song(title = "Nu börjar flykten", 108, Signature(4, 4), Pattern("||:!...:||")),
    Song(title = "Bara barnen kan", 96, Signature(3, 4), Pattern("||:!..:||")),
    Song(title = "Vi lovar stabilitet", 108, Signature(4, 4), Pattern("||:!...:||")),
    Song(title = "Aldrig mer igen", 100, Signature(3, 4), Pattern("||:!..:||")),
    Song(title = "I min tidsmaskin", 170, Signature(3, 4), Pattern("||:!..:||")),
    Song(title = "Du kan inte hindra mig", 180, Signature(3, 4), Pattern("||:!..:||")),
    Song(title = "Vi borde säga som det är", 95, Signature(4, 4), Pattern("||:!...:||")),
    Song(title = "Du kan lämna det kvar", 125, Signature(3, 4), Pattern("||:!..:||")),
    Song(title = "Om du verkligen vill", 114, Signature(4, 4), Pattern("||:!...:||")),
    Song(title = "Det kommer en vår", 110, Signature(4, 4), Pattern("||:!...:||")),
    Song(title = "Vintergatans frid", 130, Signature(3, 4), Pattern("||:!..:||")),
    Song(title = "Djävulen har flytt", 117, Signature(4, 4), Pattern("||:!...:||")),
    Song(title = "Har ni hört", 100, Signature(4, 4), Pattern("||:!...:||")),
    Song(title = "Man pova påsikti", 100, Signature(4, 4), Pattern("||:!...:||")),
  )
  // END GENERATED Soaree01

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
