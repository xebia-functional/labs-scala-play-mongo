package controllers

import play.api._
import play.api.mvc._
import play.api.libs.oauth.OAuthCalculator
import play.api.libs.ws.WS
import scala.concurrent.{Future, ExecutionContext}
import ExecutionContext.Implicits.global
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.libs.json.{JsValue, Json}
import play.modules.reactivemongo.json.collection.JSONCollection
import reactivemongo.api.QueryOpts
import java.net.URLEncoder
import utils.MongoUtils._

object Application extends Controller {

  /**
   * A capped tailable future collection of tweets
   */
  val tweetsCollection: Future[JSONCollection] = cappedCollection("tweets")

  /**
   * Inde page that delegates auth to twitter or renders the main app page
   */
  def index = Action {
    implicit request =>
      Twitter.sessionTokenPair match {
        case Some(credentials) => Ok(views.html.index.render(request))
        case _ => Redirect(routes.Twitter.authenticate())
      }
  }

  /**
   * Websockets observing IO
   */
  def watchTweets = WebSocket.using[JsValue] { implicit request =>

    //streamd from twitter passing the resulting stream to an Iteratee that inserts all incoming data in Mongo
    def streamTweets(keyword : String) = WS.url(s"https://stream.twitter.com/1.1/statuses/filter.json?track=" + URLEncoder.encode(keyword, "UTF-8"))
      .sign(OAuthCalculator(Twitter.KEY, Twitter.sessionTokenPair.get))
      .postAndRetrieveStream("")(headers => Iteratee.foreach[Array[Byte]] { ba =>
      val msg = new String(ba, "UTF-8")
      Logger.debug("received message: " + msg)
      val tweet = Json.parse(msg)
      tweetsCollection.map(_.insert(tweet))
    })

    //reads on the websocket for new keywords to stream
    val in = Iteratee.foreach[JsValue] { json =>
      streamTweets((json \ "keywords").as[String])
    }

    // Enumerates the capped collection streaming new results back to the websocket
    val out = Enumerator.flatten(tweetsCollection.map {
      collection => collection
        // we want all the documents
        .find(Json.obj())
        // the cursor must be tailable and await for incoming data
        .options(QueryOpts().tailable.awaitData)
        .cursor[JsValue]
        .enumerate
    })

    // The Websocket action expect the Input Iteratee and Output enumerator as return value
    (in, out)

  }

}