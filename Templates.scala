package prontopop

object Templates:
  val syntax = 
    s"""|  [   ]  are input field (length is num of chars)
        |  {Name}  are buttons with Name
        |  /--InterfaceElem--/  is a UI element of type InterfaceElem for example /--DropDown--/
        |  All other text is just text
        |  Styling should be monospace and repsonsive desktop/mobile
        |""".stripMargin

  object LandingPage:
    val template =
          s"""|  ProntoPop!  $Version            /--ThemeDropdown{Automatic}--/
              |  
              |  Concert Name: [            ]    {Save} to Local Store
              |  
              |  Saved Concerts: /--DropDown--/  {Load} from Local Store
              |  
              |  Songs:  {Silence}
              |  
              |  On/Off  Title                                    BPM   Sign.   Pattern
              |  {Play}  [Rymdresan - vi kommer aldrig tillbaka ] [120] [3/4 ] [||:!..|X..|X..|X..:||  ] {Remove}
              |  {Play}  [Hopp om en ofri                       ] [108] [3/4 ] [||:!..|X..|X..|X..:||  ] {Remove}
              |  
              |  {Add song}
              |""".stripMargin