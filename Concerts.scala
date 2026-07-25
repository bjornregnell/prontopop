package prontopop

object Concerts:
  import Model.* 

  val all: Map[Title, Concert] = Map(Example01, Soaree01)

  val Example01 = "Example01" -> Seq(
    Song(title = "Song title example 1", 120, Signature(3, 4), Pattern("||:!..|X..|X..|X..:||")),
    Song(title = "Song title example 2", 108, Signature(4, 4), Pattern("||:!...|X...|X...|X...:||")),
  )

  val Soaree01 = "Soaree01" -> Seq(
    Song(title = "Rymdresan - vi kommer aldrig tillbaka", 120, Signature(3, 4), Pattern("||:!..|X..|X..|X..:||")),
    Song(title = "Hopp om en ofri", 108, Signature(3, 4), Pattern("||:!..|X..|X..|X..:||")),
  )