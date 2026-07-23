package prontopop

import com.raquo.laminar.api.L.*
import org.scalajs.dom


// TODO  Laminar stuff that models the UI elements
def createProntoPopLandingPage(): HtmlElement = ???


/* Landing Page:   
  legend of below LAYOUT:
      [   ]  are input field (length is num of chars)
      {Name}  are buttons with Name
      /InterfaceElem/  is a UI element of type InterfaceElem for example DropDown 
      All other text is just text
      Styling should be monospace and repsonsive desktop/mobile

  LAYOUT:
----
Welcome to ProntoPop!

Concert Name: [            ]  {Save} to Local Store  

Saved Concerts: /DropDown/    {Load} from Local Store

Songs:

On/Off  Title                                    BPM   Sign.   Pattern
{Play}  [Rymdresan - vi kommer aldrig tillbaka ] [120] [3/4 ] [||:!..|X..|X..|X..:||  ] {Remove}
{Play}  [Hopp om en ofri                       ] [108] [3/4 ] [||:!..|X..|X..|X..:||  ] {Remove}

{Add song}

*/