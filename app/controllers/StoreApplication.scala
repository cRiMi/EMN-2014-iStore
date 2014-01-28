package controllers

import play.api.mvc.{Action, Controller}
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._
import models.Models
import play.api.libs.json._
import java.util.concurrent.TimeUnit
import play.api.libs.iteratee.{Concurrent, Enumerator}
import play.api.libs.concurrent.Promise
import scala.util.Random
import play.api.Logger


object StoreApplication extends Controller {

  lazy val (globalEnumerator, globalChannel) = Concurrent.broadcast[JsObject]

  def operations(from: String) = Action.async {
    val enumerator = Future.successful(globalEnumerator)
    // génération de bruit !!!
    val noise = Enumerator.generateM[JsObject](
      Promise.timeout(Some(
        Json.obj(
          "timestamp" -> System.currentTimeMillis(),
          "from" -> from,
          "message" -> "System ERROR"
        )
      ), 2000 + Random.nextInt(2000), TimeUnit.MILLISECONDS)
    )
    enumerator.map(e => Ok.chunked( e >- noise ))
  }

  def performSale = Action.async(parse.json) { request =>
    Models.saleFmt.reads(request.body) match {
      case JsSuccess(sale, _) => {
        globalChannel.push( request.body.as[JsObject] )
        Future( Ok( Json.obj( "succes" -> true ) ) )
      }
      case JsError(_) => {
        Logger.error( "WTF !" )
        Future( Ok( Json.obj( "succes" -> false ) ) )
      }
    }
  }
}
