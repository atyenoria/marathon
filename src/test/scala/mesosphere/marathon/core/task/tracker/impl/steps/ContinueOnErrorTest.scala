package mesosphere.marathon.core.task.tracker.impl.steps

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.TaskStatusUpdateStep
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.{ Mockito, CaptureLogEvents }
import org.apache.mesos.Protos.{ TaskID, TaskStatus }
import org.scalatest.{ GivenWhenThen, Matchers, FunSuite }

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class ContinueOnErrorTest extends FunSuite with Matchers with GivenWhenThen with Mockito {
  test("name uses nested name") {
    object nested extends TaskStatusUpdateStep {
      override def name: String = "nested"
      override def processUpdate(
        timestamp: Timestamp, appId: PathId, maybeTask: Option[MarathonTask], mesosStatus: TaskStatus): Future[_] = ???
    }

    ContinueOnErrorStep(nested).name should equal ("continueOnError(nested)")
  }

  private[this] val timestamp: Timestamp = Timestamp(1)
  private[this] val appId: PathId = PathId("/test")

  test("A successful step should not produce logging output") {
    def processUpdate(step: TaskStatusUpdateStep): Future[_] = {
      step.processUpdate(timestamp, appId, maybeTask = None, mesosStatus = TaskStatus.newBuilder().buildPartial())
    }

    Given("a nested step that is always successful")
    val nested = mock[TaskStatusUpdateStep]
    processUpdate(nested).asInstanceOf[Future[Unit]] returns Future.successful(())

    When("executing the step")
    val logEvents = CaptureLogEvents.forBlock {
      val resultFuture = processUpdate(ContinueOnErrorStep(nested))
      Await.result(resultFuture, 3.seconds)
    }

    Then("it should execute the nested step")
    processUpdate(verify(nested, times(1)))
    And("not produce any logging output")
    logEvents should be (empty)
  }

  test("A failing step should log the error but proceed") {
    def processUpdate(step: TaskStatusUpdateStep): Future[_] = {
      step.processUpdate(
        timestamp, appId, maybeTask = None,
        mesosStatus = TaskStatus.newBuilder().setTaskId(TaskID.newBuilder().setValue("task")).buildPartial())
    }

    Given("a nested step that is always successful")
    val nested = mock[TaskStatusUpdateStep]
    nested.name returns "nested"
    processUpdate(nested).asInstanceOf[Future[Unit]] returns Future.failed(new RuntimeException("error!"))

    When("executing the step")
    val logEvents = CaptureLogEvents.forBlock {
      val resultFuture = processUpdate(ContinueOnErrorStep(nested))
      Await.result(resultFuture, 3.seconds)
    }

    Then("it should execute the nested step")
    processUpdate(verify(nested, times(1)))
    And("not produce any logging output")
    logEvents.map(_.toString) should be (
      Vector("[ERROR] while executing step nested for [task], continue with other steps")
    )
  }
}
