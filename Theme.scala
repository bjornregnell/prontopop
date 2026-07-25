package prontopop

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.util.Try

/** The design-language themes: a data-theme attribute on <html> that the CSS in [[Styles]] reads,
  * remembered per origin so the choice survives a reload. */
object Theme:

  /** The key genscalator's own pages use, so a theme picked there carries over on a shared origin. */
  val StorageKey = "gs-theme"

  val Auto = "auto"

  val all: Vector[(String, String)] = Vector(
    Auto            -> "Automatic",
    "forgy-dark"    -> "Forgy dark",
    "smither-light" -> "Smither light",
    "calm-dark"     -> "Calm dark",
    "calm-light"    -> "Calm light",
  )

  private def isKnown(theme: String): Boolean = all.exists((v, _) => v == theme)

  private def saved: String =
    Try(Option(dom.window.localStorage.getItem(StorageKey))).toOption.flatten
      .filter(isKnown).getOrElse(Auto)

  /** Automatic means NO attribute, so the CSS falls through to the operating system's setting. */
  def show(theme: String): Unit =
    if theme == Auto then dom.document.documentElement.removeAttribute("data-theme")
    else dom.document.documentElement.setAttribute("data-theme", theme)

  def showSaved(): Unit = show(saved)

  def createSelector(): HtmlElement =
    val chosen = Var(saved)
    select(
      idAttr := "theme-select",
      all.map((v, label) => option(value := v, label)),
      controlled(
        value <-- chosen.signal,
        onChange.mapToValue --> { (v: String) =>
          chosen.set(v)
          Try(dom.window.localStorage.setItem(StorageKey, v))  // private mode refuses; still switch this page
          show(v)
        },
      ),
    )
