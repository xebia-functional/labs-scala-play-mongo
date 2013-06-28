package controllers

import play.api.Play.current
import play.api.libs.oauth.{RequestToken, ServiceInfo, ConsumerKey, OAuth}
import play.api.mvc.{RequestHeader, Action, Controller}
import play.api.Play

object Twitter extends Controller {

  val cfg = Play.application.configuration

  val KEY = ConsumerKey(cfg.getString("twitter.consumerKey").get, cfg.getString("twitter.consumerSecret").get)

  val TWITTER = OAuth(ServiceInfo(
    "https://api.twitter.com/oauth/request_token",
    "https://api.twitter.com/oauth/access_token",
    "https://api.twitter.com/oauth/authorize", KEY),
    false)

  def authenticate = Action {
    request =>
      request.getQueryString("oauth_verifier").map {
        verifier =>
          val tokenPair = sessionTokenPair(request).get
          // We got the verifier; now get the access token, store it and back to index
          TWITTER.retrieveAccessToken(tokenPair, verifier) match {
            case Right(t) => {
              // We received the authorized tokens in the OAuth object - store it before we proceed
              Redirect(routes.Application.index()).withSession("token" -> t.token, "secret" -> t.secret)
            }
            case Left(e) => throw e
          }
      }.getOrElse(
        TWITTER.retrieveRequestToken(routes.Twitter.authenticate().absoluteURL()) match {
          case Right(t) => {
            // We received the unauthorized tokens in the OAuth object - store it before we proceed
            Redirect(TWITTER.redirectUrl(t.token)).withSession("token" -> t.token, "secret" -> t.secret)
          }
          case Left(e) => throw e
        })
  }

  def sessionTokenPair(implicit request: RequestHeader): Option[RequestToken] = {
    for {
      token <- request.session.get("token")
      secret <- request.session.get("secret")
    } yield {
      RequestToken(token, secret)
    }
  }

}
