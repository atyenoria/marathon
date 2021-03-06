package mesosphere.marathon.api.v2

import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.core.base.{ Clock, ConstantClock }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskCount
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.{ MarathonConf, MarathonSpec }
import mesosphere.util.Mockito
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class QueueResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  test("return well formatted JSON") {
    //given
    val app = AppDefinition(id = "app".toRootPath)
    queue.list returns Seq(
      QueuedTaskCount(
        app, tasksLeftToLaunch = 23, taskLaunchesInFlight = 0, tasksLaunchedOrRunning = 0, clock.now() + 100.seconds
      )
    )

    //when
    val response = queueResource.index(auth.request, auth.response)

    //then
    response.getStatus should be(200)
    val json = Json.parse(response.getEntity.asInstanceOf[String])
    val queuedApps = (json \ "queue").as[Seq[JsObject]]
    val jsonApp1 = queuedApps.find { apps => (apps \ "app" \ "id").as[String] == "/app" }.get

    (jsonApp1 \ "app").as[V2AppDefinition] should be(V2AppDefinition(app))
    (jsonApp1 \ "count").as[Integer] should be(23)
    (jsonApp1 \ "delay" \ "overdue").as[Boolean] should be(false)
    (jsonApp1 \ "delay" \ "timeLeftSeconds").as[Integer] should be(100) //the deadline holds the current time...
  }

  test("the generated info from the queue contains 0 if there is no delay") {
    //given
    val app = AppDefinition(id = "app".toRootPath)
    queue.list returns Seq(
      QueuedTaskCount(
        app, tasksLeftToLaunch = 23, taskLaunchesInFlight = 0, tasksLaunchedOrRunning = 0,
        backOffUntil = clock.now() - 100.seconds
      )
    )
    //when
    val response = queueResource.index(auth.request, auth.response)

    //then
    response.getStatus should be(200)
    val json = Json.parse(response.getEntity.asInstanceOf[String])
    val queuedApps = (json \ "queue").as[Seq[JsObject]]
    val jsonApp1 = queuedApps.find { apps => (apps \ "app" \ "id").get == JsString("/app") }.get

    (jsonApp1 \ "app").as[V2AppDefinition] should be(V2AppDefinition(app))
    (jsonApp1 \ "count").as[Integer] should be(23)
    (jsonApp1 \ "delay" \ "overdue").as[Boolean] should be(true)
    (jsonApp1 \ "delay" \ "timeLeftSeconds").as[Integer] should be(0)
  }

  test("unknown application backoff can not be removed from the taskqueue") {
    //given
    queue.list returns Seq.empty

    //when
    val response = queueResource.resetDelay("unknown", auth.request, auth.response)

    //then
    response.getStatus should be(404)
  }

  test("application backoff can be removed from the taskqueue") {
    //given
    val app = AppDefinition(id = "app".toRootPath)
    queue.list returns Seq(
      QueuedTaskCount(
        app, tasksLeftToLaunch = 23, taskLaunchesInFlight = 0, tasksLaunchedOrRunning = 0,
        backOffUntil = clock.now() + 100.seconds
      )
    )

    //when
    val response = queueResource.resetDelay("app", auth.request, auth.response)

    //then
    response.getStatus should be(204)
    verify(queue, times(1)).resetDelay(app)
  }

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request
    val resp = auth.response

    When(s"the index is fetched")
    val index = queueResource.index(req, resp)
    Then("we receive a NotAuthenticated response")
    index.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"one delay is reset")
    val resetDelay = queueResource.resetDelay("appId", req, resp)
    Then("we receive a NotAuthenticated response")
    resetDelay.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    val resp = auth.response

    When(s"one delay is reset")
    val resetDelay = queueResource.resetDelay("appId", req, resp)
    Then("we receive a not authorized response")
    resetDelay.getStatus should be(auth.UnauthorizedStatus)
  }

  var clock: Clock = _
  var config: MarathonConf = _
  var queueResource: QueueResource = _
  var auth: TestAuthFixture = _
  var queue: LaunchQueue = _

  before {
    clock = ConstantClock()
    auth = new TestAuthFixture
    config = mock[MarathonConf]
    queue = mock[LaunchQueue]
    queueResource = new QueueResource(
      clock,
      queue,
      auth.auth,
      auth.auth,
      config
    )
  }
}
