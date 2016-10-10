/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import swave.core.impl.{Inport, Outport, StreamRunner}
import swave.core.macros._
import swave.core.{Cancellable, PipeElem, StreamTimeoutException}

import scala.concurrent.duration._

// format: OFF
@StageImpl
private[core] final class WithCompletionTimeoutStage(timeout: FiniteDuration) extends InOutStage
  with PipeElem.InOut.WithCompletionTimeout {

  requireArg(timeout > Duration.Zero, "The `timeout` must be > 0")

  def pipeElemType: String = "withCompletionTimeout"
  def pipeElemParams: List[Any] = timeout :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForRunnerAssignment(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingFirstDemand() = state(
      request = (n, _) => {
        in.request(n.toLong)
        active(runner.scheduleTimeout(this, timeout))
      },

      cancel = stopCancelF(in),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    def active(timer: Cancellable) = state(
      request = requestF(in),

      cancel = _ => {
        timer.cancel()
        stopCancel(in)
      },

      onNext = (elem, _) => {
        out.onNext(elem)
        stay()
      },

      onComplete = _ => {
        timer.cancel()
        stopComplete(out)
      },

      onError = (e, _) => {
        timer.cancel()
        stopError(e, out)
      },

      xEvent = {
        case StreamRunner.Timeout(_) =>
          val e = new StreamTimeoutException(s"The stream was not completed within $timeout")
          stopError(e, out)
      })

    awaitingFirstDemand()
  }
}
