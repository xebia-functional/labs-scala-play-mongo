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
import play.api.libs.Comet

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
   * Comet observing IO
   */
  def watchTweets(keywords : String) = Action { implicit request =>

    Logger.debug(s"watchTweets invoked with: $keywords")

    //streams from twitter passing the resulting stream to an Iteratee that inserts all incoming data in Mongo
    WS.url(s"https://stream.twitter.com/1.1/statuses/filter.json?track=" + URLEncoder.encode(keywords, "UTF-8"))
      .sign(OAuthCalculator(Twitter.KEY, Twitter.sessionTokenPair.get))
      .postAndRetrieveStream("")(headers => Iteratee.foreach[Array[Byte]] { ba =>
      val msg = new String(ba, "UTF-8")
      Logger.debug(s"received message: $msg")
      val tweet = Json.parse(msg)
      tweetsCollection.map(_.insert(tweet))
    })

    // Enumerates the capped collection streaming new results back to the response
    val out = Enumerator.flatten(tweetsCollection.map {
      collection => collection
        // we want all the documents
        .find(Json.obj())
        // the cursor must be tailable and await for incoming data
        .options(QueryOpts().tailable.awaitData)
        .cursor[JsValue]
        .enumerate
    })

    Ok.stream(out &> Comet(callback = "parent.cometMessage"))

  }

}