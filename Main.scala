//> using scala 3.9.0-RC1
//> using platform scala-js
//> using jsVersion 1.22.0
//> using dep com.raquo::laminar::17.2.1

package prontopop

import com.raquo.laminar.api.L.*
import org.scalajs.dom

val Version = "v0.1.0"

@main def run(): Unit =
  renderOnDomContentLoaded(dom.document.getElementById("app"), createProntoPopLandingPage())
