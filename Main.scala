//> using scala 3.9.0-RC1
//> using platform scala-js
//> using jsVersion 1.22.0
//> using dep com.raquo::laminar::17.2.1

import com.raquo.laminar.api.L.*
import org.scalajs.dom

@main def run(): Unit =
  renderOnDomContentLoaded(dom.document.getElementById("app"), appElement())

def appElement(): HtmlElement =
  val pops = Var(0)
  div(
    h1("prontopop"),
    p("A single-page, server-less Scala.js + Laminar app."),
    button(
      "Pop!",
      onClick --> (_ => pops.update(_ + 1)),
    ),
    p(
      "Pops so far: ",
      child.text <-- pops.signal.map(_.toString),
    ),
  )
