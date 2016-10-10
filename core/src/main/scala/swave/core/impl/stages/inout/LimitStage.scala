/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.{PipeElem, StreamLimitExceeded}
import swave.core.impl.{Inport, Outport}
import swave.core.macros._

// format: OFF
@StageImpl
private[core] final class LimitStage(max: Long, cost: AnyRef ⇒ Long) extends InOutStage with PipeElem.InOut.Limit {

  requireArg(max >= 0)

  def pipeElemType: String = "limit"
  def pipeElemParams: List[Any] = max :: cost :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out, max) }

  /**
    * @param in        the active upstream
    * @param out       the active downstream
    * @param remaining max number of elements still allowed before completion, >= 0
    */
  def running(in: Inport, out: Outport, remaining: Long): State = state(
    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      var funError: Throwable = null
      val rem = try remaining - cost(elem) catch { case NonFatal(e) => { funError = e; 0L } }
      if (funError eq null) {
        if (rem >= 0) {
          out.onNext(elem)
          running(in, out, rem)
        } else {
          in.cancel()
          stopError(new StreamLimitExceeded(max, elem), out)
        }
      } else {
        in.cancel()
        stopError(funError, out)
      }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
