package controllers

import play.api.mvc.{WebSocket, Action, Controller}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.libs.iteratee.{Concurrent, Iteratee}
import play.api.Play.current
import play.api.Play
import play.api.libs.ws.WS
import java.util.concurrent.atomic.AtomicInteger
import play.api.mvc.WebSocket.FrameFormatter._
import play.Logger
import models.{Sale, Models}
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsObject
import scala.util.{Success, Failure, Random}
import java.util.Date

object MobileApplication extends Controller {

  val performSaleURL = Play.configuration.getString("urls.performsale").getOrElse("http://localhost:9000/performSale")

  def index() = Action {
    Ok(views.html.mobile.list( Models.cities ))
  }

  def sale(from: String) = Action {
    Ok(views.html.mobile.sale(from))
  }

  def performSale(from: String) = WebSocket.using[JsValue] { rh =>
    val counter = new AtomicInteger(0)
    val (out, channel) = Concurrent.broadcast[JsValue]
    val in = Iteratee.foreach[JsValue] { obj =>
      Models.mobileSaleFmt.reads(obj) match {
        case JsSuccess(sale, _) => {
          val sendSale = Sale(sale.id, sale.price, sale.product.id, if (Random.nextBoolean()) "public" else "private", from, sale.vendorId, System.currentTimeMillis())
          WS.url(performSaleURL).post[JsValue](Models.saleFmt.writes(sendSale)).onComplete {
            case Failure(_) => Logger.error("error while sending data to store app")
            case Success(_) => {
              Logger.info("sale sent to the store app")
              channel.push( Json.obj("nbrSales" -> counter.incrementAndGet()))
            }
          }
        }
        case JsError(e) => Logger.error("Error while receiving mobile sale : $e")
      }
    }
    (in, out)
  }
}
