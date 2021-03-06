/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal
import scala.annotation.tailrec
import com.typesafe.scalalogging.Logger
import org.jctools.queues.MpscLinkedQueue8
import swave.core.Dispatcher

/**
  * Minimalistic actor implementation without `become`.
  */
private[impl] abstract class StreamActor(final val dispatcher: Dispatcher, final val throughput: Int) {

  protected type MessageType <: AnyRef

  protected def log: Logger

  /**
    * The mailbox.
    */
  private[this] val mailbox = {
    // TODO: upgrade to intrusive variant as soon as https://github.com/JCTools/JCTools/issues/102 is cleared
    new MpscLinkedQueue8[MessageType]()
  }

  protected final def mailboxEmpty: Boolean = mailbox.isEmpty

  /*
   * Internal object instance hiding the AtomicBoolean and Runnable interfaces.
   * We could have the StreamActor itself extend `AtomicBoolean with Runnable` and save an allocation
   * but we pay this small cost and prevent leaking `get`, `set`, `run` and other methods to the outside.
   * The atomic boolean signals whether we are currently running or
   * scheduled to run on the env.executionContext or whether we are "idle".
   *
   * We start out in state SCHEDULED to protect ourselves from starting
   * mailbox processing before the object has been fully initialized.
   */
  private[this] val scheduled: AtomicBoolean with Runnable =
    new AtomicBoolean(true) with Runnable {
      final def run(): Unit =
        try {
          @tailrec def processMailbox(maxRemainingMessages: Int): Unit =
            if (maxRemainingMessages > 0) {
              val nextMsg = mailbox.poll()
              if (nextMsg ne null) {
                receive(nextMsg)
                processMailbox(maxRemainingMessages - 1)
              }
            }
          processMailbox(throughput)
        } catch {
          // Non-fatal exceptions thrown in user code should never bubble up to here!
          // Non-fatal exceptions thrown in (async) swave code should always bubble up to here!
          case NonFatal(e) ⇒ log.error("Uncaught exception in StreamActor::receive", e)
        } finally {
          startMessageProcessing()
        }
    }

  protected def receive(msg: MessageType): Unit

  private[impl] final def enqueue(msg: MessageType): Unit = {
    mailbox.offer(msg)
    trySchedule()
  }

  private def trySchedule(): Unit =
    if (scheduled.compareAndSet(false, true)) {
      try dispatcher.execute(scheduled)
      catch {
        case NonFatal(e) ⇒
          scheduled.set(false)
          throw e
      }
    }

  // must be called at the end of the outermost constructor
  protected final def startMessageProcessing(): Unit = {
    scheduled.set(false)
    if (!mailbox.isEmpty)
      trySchedule()
  }
}
