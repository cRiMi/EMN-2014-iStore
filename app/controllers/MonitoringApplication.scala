package controllers

import play.api.mvc.{Action, Controller}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import models.{Sale, Models, Event}
import play.api.libs.ws.WS.WSRequestHolder
import play.api.libs.iteratee.{Enumeratee, Concurrent, Enumerator}
import play.api.libs.ws.WS
import play.api.libs.{iteratee, EventSource}
import play.api.Play
import play.api.Play.current
import com.fasterxml.jackson.annotation.JsonValue

object MonitoringApplication extends Controller {

  val baseUrl = Play.configuration.getString("urls.stream").getOrElse("http://localhost:9000/operations?from=%s")

  def index(role: String) = Action {
    Ok(views.html.monitoring.monitoring(role))
  }

  def feed(role: String, lower: Int, higher: Int) = Action {
    val secure: Enumeratee[Event, Event] = Enumeratee.collect[Event] {
      case s: models.Status if role == "MANAGER" => s
      case o@Sale(_, _, _, "public", _, _, _) => o
      case o@Sale(_, _, _, "private",_, _, _) if role == "MANAGER" => o
    }

    val inBounds: Enumeratee[Event, Event] = Enumeratee.collect[Event] {
      case s: models.Status => s
      case o@Sale(_, amount, _, _, _, _, _) if amount > lower && amount < higher => o
    }

    def getStream(request: WSRequestHolder): Enumerator[Array[Byte]] = {
      val (iteratee, enumerator) = Concurrent.joined[Array[Byte]]
      request.get(_ => iteratee).map(_.run)
      enumerator
    }

    def reads(json: JsValue): JsResult[Event] = json \ "price" match {
      case _: JsUndefined => Models.statusFmt.reads(json)
      case _ => Models.saleFmt.reads(json)
    }
    def writes(e: Event): JsValue = e match {
      case s: models.Status => Models.statusFmt.writes(s)
      case o: Sale => Models.saleFmt.writes(o)
    }

    val nantes = getStream(WS.url(baseUrl.format( "Nantes" )))
    val paris = getStream(WS.url(baseUrl.format( "Paris" )))
    val lyon = getStream(WS.url(baseUrl.format( "Lyon" )))
    val bordeaux = getStream(WS.url(baseUrl.format( "Bordeaux" )))

    val pipeline = (bordeaux >- paris >- nantes >- lyon)

    val byteToJson: Enumeratee[Array[Byte], JsValue] = Enumeratee.map {
      byteArray => Json.parse( byteArray )
    }

    val jsValueToJsResult: Enumeratee[JsValue, JsResult[Event]] = Enumeratee.map {
      jsvalue => reads( jsvalue )
    }

    val filterSucces: Enumeratee[JsResult[Event], Event] = {
      Enumeratee.collect {
        case JsSuccess(e, _) => e
      }
    }

    val eventToJsValue: Enumeratee[Event, JsValue] = Enumeratee.map {
      e => writes(e)
    }

    Ok.feed( pipeline.through( byteToJson ><> jsValueToJsResult ><> filterSucces ><> secure ><> inBounds ><> eventToJsValue ).through( EventSource() ) ).as("text/event-stream")
  }

}
