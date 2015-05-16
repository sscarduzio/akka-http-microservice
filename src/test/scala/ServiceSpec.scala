import akka.event.NoLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.{HttpResponse, HttpRequest}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Flow
import org.scalatest._

class ServiceSpec extends FlatSpec with Matchers with ScalatestRouteTest with Service {
  override def testConfigSource = "akka.loglevel = WARNING"
  override def config = testConfig
  override val logger = NoLogging

  val ip1Info = DBResult("data1")
  val ip2Info = DBResult("data2")

  override lazy val dbServiceConnectionFlow: Flow[HttpRequest, HttpRequest, Unit]#Repr[HttpResponse, Unit] = Flow[HttpRequest].map { request =>
    if (request.uri.toString().endsWith(ip1Info.data))
      HttpResponse(status = OK, entity = marshal(ip1Info))
    else if(request.uri.toString().endsWith(ip2Info.data))
      HttpResponse(status = OK, entity = marshal(ip2Info))
    else
      HttpResponse(status = BadRequest, entity = marshal("Bad ip format"))
  }

  "Service" should "respond to single IP query" in {
    Get(s"/data/${ip1Info.data}") ~> routes ~> check {
      status shouldBe OK
      contentType shouldBe `application/json`
      responseAs[DBResult] shouldBe ip1Info
    }
  }

  it should "respond with bad request on incorrect IP format" in {
    Get("/data/asdfg") ~> routes ~> check {
      status shouldBe BadRequest
      responseAs[String].length should be > 0
    }
  }
}
