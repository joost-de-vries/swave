/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.PipeElem
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class FilterStage(predicate: Any ⇒ Boolean, negated: Boolean) extends InOutStage with PipeElem.InOut.Filter {

  def pipeElemType: String = "filter"
  def pipeElemParams: List[Any] = predicate :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      var funError: Throwable = null
      val p = try predicate(elem) catch { case NonFatal(e) => { funError = e; false } }
      if (funError eq null) {
        if (p != negated) out.onNext(elem)
        else in.request(1)
        stay()
      } else {
        in.cancel()
        stopError(funError, out)
      }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
