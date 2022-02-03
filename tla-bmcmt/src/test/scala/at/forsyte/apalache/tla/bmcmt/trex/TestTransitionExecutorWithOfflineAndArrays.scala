package at.forsyte.apalache.tla.bmcmt.trex

import at.forsyte.apalache.tla.bmcmt.{SymbStateRewriterImpl, arraysEncoding}
import at.forsyte.apalache.tla.bmcmt.analyses._
import at.forsyte.apalache.tla.bmcmt.smt.{RecordingSolverContext, SolverConfig}
import org.junit.runner.RunWith
import org.scalatest.Outcome
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestTransitionExecutorWithOfflineAndArrays extends TestTransitionExecutorImpl[OfflineExecutionContextSnapshot] {
  override protected def withFixture(test: OneArgTest): Outcome = {
    val solver = RecordingSolverContext.createZ3(None,
        SolverConfig(debug = false, profile = false, randomSeed = 0, smtEncoding = arraysEncoding))
    val rewriter = new SymbStateRewriterImpl(solver, new ExprGradeStoreImpl())
    val exeCtx = new OfflineExecutionContext(rewriter)
    try {
      test(exeCtx)
    } finally {
      rewriter.dispose()
    }
  }
}
