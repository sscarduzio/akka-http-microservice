import akka.actor.ActorSystem
import akka.event.{LoggingAdapter, Logging}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{HttpResponse, HttpRequest}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorFlowMaterializer, FlowMaterializer}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.IOException
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.math._
import spray.json.DefaultJsonProtocol

case class DBResult(data: String)

trait Protocols extends DefaultJsonProtocol {
  implicit val dbResutFormat = jsonFormat1(DBResult.apply)
}

trait Service extends Protocols {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: FlowMaterializer

  def config: Config
  val logger: LoggingAdapter

  lazy val dbServiceConnectionFlow: Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    Http().outgoingConnection(config.getString("services.db-srvc.host"), config.getInt("services.db-srvc.port"))

  def dbServiceRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(dbServiceConnectionFlow).runWith(Sink.head)

  def fetchDbServiceData(query: String): Future[Either[String, DBResult]] = {
    dbServiceRequest(RequestBuilding.Get(s"/db/$query")).flatMap { response =>
      response.status match {
        case OK => Unmarshal(response.entity).to[DBResult].map(Right(_))
        case BadRequest => Future.successful(Left(s"$query: incorrect request format"))
        case _ => Unmarshal(response.entity).to[String].flatMap { entity =>
          val error = s"DB request failed with status code ${response.status} and entity $entity"
          logger.error(error)
          Future.failed(new IOException(error))
        }
      }
    }
  }

  val routes = {
    logRequestResult("akka-http-microservice") {
      pathPrefix("data") {
        (get & path(Segment)) { query =>
          complete {
            fetchDbServiceData(query).map[ToResponseMarshallable] {
              case Right(dbResult) => dbResult
              case Left(errorMessage) => BadRequest -> errorMessage
            }
          }
        }
      }
    }
  }
}

object AkkaHttpMicroservice extends App with Service {
  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorFlowMaterializer()

  override val config = ConfigFactory.load()
  override val logger = Logging(system, getClass)

  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}
