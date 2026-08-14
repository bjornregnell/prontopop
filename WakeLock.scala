package prontopop

import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success, Try}

/** Keeps the screen awake, so a phone propped on a music stand does not lock in the middle of a
  * song.
  *
  * A HANDROLLED FACADE: the Screen Wake Lock API is not in scalajs-dom, and a few lines here beat
  * taking a dependency for them.
  *
  * Two things about this API are easy to get wrong:
  *
  *   - the browser DROPS the lock whenever the page stops being visible — a tab switch, or the
  *     screen going off before the lock took effect — so it has to be asked for again on the way
  *     back. Hence the visibilitychange listener; without it the lock silently stops working after
  *     the first interruption.
  *   - it may simply be absent: an older browser, or an insecure origin, since it needs HTTPS or
  *     localhost. Every call here then does nothing, quietly, which is the right failure: the
  *     screen behaves as it always did.
  */
object WakeLock:

  @js.native
  private trait Sentinel extends js.Object:
    def release(): js.Promise[Unit] = js.native

  @js.native
  private trait Api extends js.Object:
    def request(kind: String): js.Promise[Sentinel] = js.native

  private def api: Option[Api] =
    val wl = dom.window.navigator.asInstanceOf[js.Dynamic].selectDynamic("wakeLock")
    if js.isUndefined(wl) || wl == null then None else Some(wl.asInstanceOf[Api])

  /** Whether this browser offers the API at all. */
  def isAvailable: Boolean = api.isDefined

  private var held      = Option.empty[Sentinel]
  private var wanted    = false
  private var listening = false

  /** Hold the screen awake, and keep holding it across the page being hidden and shown again. */
  def keepAwake(): Unit =
    wanted = true
    listenForVisibility()
    acquire()

  /** Let the screen sleep on its usual timer again. */
  def allowSleep(): Unit =
    wanted = false
    held.foreach(sentinel => Try(sentinel.release()))
    held = None

  private def acquire(): Unit =
    if wanted && held.isEmpty then
      api.foreach: wl =>
        Try(wl.request("screen").toFuture).foreach: pending =>
          pending.onComplete:
            // wanted can have turned false while the request was in flight — then let it go again
            case Success(sentinel) => if wanted then held = Some(sentinel) else Try(sentinel.release())
            case Failure(_)        => () // refused, e.g. not a visible document; nothing to do

  private def listenForVisibility(): Unit =
    if !listening then
      listening = true
      dom.document.addEventListener("visibilitychange", (_: dom.Event) =>
        if wanted && !dom.document.hidden then
          held = None // the browser already dropped it on the way out; ask afresh
          acquire()
      )
