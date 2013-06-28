## A Play2 Scala Comet + Reactive Mongo Demo ##

This is a demo app composed of a Play2 web app featuring the use of Comet, Http Streaming with the Twitter API and reactive streaming
to Mongo capped collections part of a tech talk series about Scala at the [XII Betabeers Cádiz aniversary](http://betabeers.com/community/betabeers-cadiz-18/)

The talk was composed of two parts. A demo of deploying a Play app to Heroku and another demo showing how Play Iteratees can be used to Stream
data and combined with Comet.

The live demo is available at [http://bbcdz12.herokuapp.com/](http://bbcdz12.herokuapp.com/)

### 1. Play and Heroku ###

The first part of the talk was about setting a Play2 Scala App and deploying it to heroku.
These steps assume you have already installed [Play2](http://www.playframework.com/download) and the [Heroku toolbelt](https://toolbelt.heroku.com/)

**Create a play app**

```sh
play new <app_name>
```

**Run it**

```sh
cd <app_name>
play run
```

**Add it to Git**

```sh
git init
git add .
git commit -m "First deployment"
```

**Deploy to Heroku**

```sh
heroku create
git push heroku master
```

And then you are done. Your app is being deployed to Heroku as your are reading this :).
Heroku uses a git remote to keep track of your deployment and you just push changes onto it any time you want to deploy new
changes.

Here is some more info about [deploying with Git to Heroku](https://devcenter.heroku.com/articles/git) and [Play support in Heroku](https://devcenter.heroku.com/articles/play-support)

### 2. A Reactive Mongo Example with Iteratees and Comet ###

The second part of this demo showcases how play has native built-in support for Comet and how you may use Iteratees to reactively handle data streams.
We will be connecting to the Twitter API, streaming tweets in real time and feeding the incoming stream asynchronously into a Mongo Collection.

The Mongo collection is a special type of collection, [a capped collection](http://docs.mongodb.org/manual/core/capped-collections/) that may be tailed in order to receive callback notifications when there are incoming
records added to it.

We will be using Play [Iteratees and Enumerators](http://mandubian.com/2012/08/27/understanding-play2-iteratees-for-normal-humans/) to retrieve the keywords that are going to be sent by the user through a Comet and keep a constant enumerator
over the capped collection that broadcasts incoming records back to the HTML client.

First we will setup the MongoLab add-on on Heroku. The sandbox version gives you a free sandbox environment for development.

**Setup MongoDB on Heroku**

```sh
heroku addons:add mongolab:sandbox
```

To access our MongoDB server we will be using the [Reactive Mongo Driver](http://reactivemongo.org/) and the [Reactive Mongo Play Plugin](https://github.com/zenexity/Play-ReactiveMongo)

Edit conf/application.conf and add the mongodb.uri property pointing to the ${MONGOLAB_URI} env variable.
Note that you may use the same uri in localhost by creating an environment variable using the value provided by the Heroku config in your app

Run the following command to obtain your app config including the MongoLab URI used to connect to MongoDB

```
heroku config
```

Then add the property value in your app config

```
mongodb.uri = ${MONGOLAB_URI}
```

Edit *project/Build.scala* and add the Reactive Mongo Play Plugin Dependencies. Play uses [SBT](http://www.scala-sbt.org/), similar to Maven, SBT downloads all dependencies
from Maven and Ivy compatible repositories

```scala
val appDependencies = Seq(
    "org.reactivemongo" %% "play2-reactivemongo" % "0.9"
)

val main = play.Project(appName, appVersion, appDependencies).settings(
    resolvers += "Rhinofly Internal Repository" at "http://maven-repository.rhinofly.net:8081/artifactory/libs-release-local"
)
```

Edit or create *conf/play.plugins* and register the Reactive Mongo Play plugin

```
400:play.modules.reactivemongo.ReactiveMongoPlugin
```

**Setup Twitter OAuth Boilerplate**

Due to new requirements in the Twitter streaming API we need to authenticate as a user in order to obtain a stream from Twitter.
The code below is just standard boilerplate over Play's builtin Oauth Support that redirects the user to Twitter Auth before they use the app.

*controllers/Twitter.scala*

```scala
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
    implicit request =>
      request.getQueryString("oauth_verifier").map {
        verifier =>
          val tokenPair = sessionTokenPair(request).get
          // We got the verifier; now get the access token, store it and back to index
          TWITTER.retrieveAccessToken(tokenPair, verifier) match {
            case Right(t) => {
              // We received the authorized tokens in the OAuth object - store it before we proceed
              Redirect(routes.Application.index).withSession("token" -> t.token, "secret" -> t.secret)
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

```

**The Gist**

Now the most important part. First we need to create or obtain a Future reference to a Mongo capped collection that can
be tailed to obtain callbacks when new records are inserted into the collection.
We are using a simple util object that can give us any collection as a Future capped collection. 

Converting an existing collection to a capped collection may be a risky operation that can cause data loss, **proceed at your own risk**.

*utils/MongoUtils.scala*

```scala

package utils

import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global
import play.api.Play.current
import scala.concurrent.Future
import play.modules.reactivemongo.json.collection.JSONCollection
import play.modules.reactivemongo.ReactiveMongoPlugin
import play.api.Logger

object MongoUtils {

  implicit def cappedCollection(name : String) = {
      val db = ReactiveMongoPlugin.db
      val collection = db.collection[JSONCollection](name)
      collection.stats().flatMap {
        case stats if !stats.capped =>
          // the collection is not capped, so we convert it
          Logger.debug("converting to capped")
          collection.convertToCapped(1024 * 1024, None)
        case _ => Future(collection)
      }.recover {
        // the collection does not exist, so we create it
        case _ =>
          Logger.debug("creating capped collection...")
          collection.createCapped(1024 * 1024, None)
      }.map { _ =>
          Logger.debug("the capped collection is available")
          collection
      }
  }

}

```

In the next part we are going to create a controller that uses Play's Iteratees to consume the Twitter Stream and forward any incoming Tweets into Mongo.
We are also going to use Iteratees and Comet to consume user input and enumerate the capped collection that will continuously notify the browser of
any inserted Tweets.

Note that you could just use the Iteratee to stream back to the browser from Twitter but we use MongoDB not just to store tweets but to also notify any other
clients observing the collection

*controllers/Application.scala*

```scala

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
   * Websockets observing IO
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

```

**Setup routes**

Finally we just setup the routes where our controller methods will be mapped to the outside world and exposed for our
HTML client to connect to

*conf/routes*

```
GET     /                           controllers.Application.index
GET     /assets/*file               controllers.Assets.at(path="/public", file)
GET     /auth                       controllers.Twitter.authenticate()
GET     /watchTweets                controllers.Application.watchTweets
```

**HTML UI**

Nothing interesting here, just a bunch of boilerplate HTML with a Bootstrap basic UI that binds HTML actions to Comet calls all contained in a ScalaTemplate.
The Comet endpoint receives tweets from the server in JSON format appending the received results to a list of tweets streamed so far.
As a side effect since everyone accessing this app from a Browser has an open connection to the server and all clients would be observing the same Mongo collection
they will all see the same results realtime streaming before their eyes.

*app/views/index.scala.html*

```html
@()(implicit request: Request[AnyContent])
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">

<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
    <head>
        <meta http-equiv="Content-Type" content="text/html; charset=utf-8"/>
        <title>Twitter Mongo Streaming</title>
        <link rel="stylesheet" type="text/css" href="@routes.Assets.at("stylesheets/main.css")">
        <script src="//ajax.googleapis.com/ajax/libs/jquery/1.9.0/jquery.min.js"></script>
        <link href="//netdna.bootstrapcdn.com/twitter-bootstrap/2.3.2/css/bootstrap-combined.min.css" rel="stylesheet">
        <script src="//cdn.jsdelivr.net/jsrender/1.0pre35/jsrender.min.js"></script>
        <script src="//netdna.bootstrapcdn.com/twitter-bootstrap/2.3.2/js/bootstrap.min.js"></script>
    </head>
    <body>
        <div class="container-narrow">

            <div class="masthead">
                <ul class="nav nav-pills pull-right">
                    <li class="active"><a href="#">Home</a></li>
                    <li><a href="#">About</a></li>
                    <li><a href="#">Contact</a></li>
                </ul>
                <h3 class="muted">Twitter -> MongoDB -> Browser</h3>
            </div>

            <hr>

            <div class="jumbotron">
                <p class="lead">Part of the Scala Play demo for the @@betabeers #bbCDZ Anniversary.</p>
                <form class="form-search">
                    <input type="text" id="keywordsInput" class="input-medium search-query" />
                    <button id="runKeywordsButton" class="btn btn-large btn-success">Stream!</button>
                </form>
            </div>

            <hr>

            <div class="row-fluid">
                <ul id="list">
                    <script id="tweetTemplate" type="text/x-jsrender">
                        <li>
                            <div class="media">
                                <a class="pull-left" href="#">
                                    <img class="media-object" data-src="holder.js/48x48" alt="48x48" style="width: 48px; height: 48px;" src="{{>user.profile_image_url}}">
                                </a>
                                <div class="media-body">
                                    <h4 class="media-heading"><span>{{>user.name}}</span> <small><i>@@{{>user.screen_name}}</i></small> <small><i>{{>user.created_at}}</i></small></h4>
                                    {{>text}}
                                </div>
                            </div>
                        </li>
                    </script>
                </ul>
            </div>

            <hr>

            <script type="text/javascript">

                var cometMessage = function(event) {
                    console.log('message:' + event);
                    var tweet = event;
                    if (tweet && tweet.user) {
                       $('#list').prepend($("#tweetTemplate").render(tweet));
                    }
                }

                $(document).ready(function(){

                    $('#runKeywordsButton').click(function(){

                        var keywords = $('#keywordsInput').val();

                        $('#commetIframe').remove();

                        $('body').append('<iframe width="700" scrolling="no" height="400" frameborder="0" seamless="seamless" id="commetIframe" src="@routes.Application.watchTweets("").absoluteURL()' + keywords + '"></iframe>');

                        return false;
                    });

                });

            </script>

            <div class="footer">
                <p>&copy; 47 Degrees 2013</p>
            </div>

        </div> <!-- /container -->

    </body>
</html>

```

Optionally you may setup your IDE for Scala and Play Development. We use [IntelliJ IDEA](http://www.jetbrains.com/idea/) at [47 Degrees](http://47deg.com). Simply run the following command to get all the
.idea .iml and idea files generated based on your SBT deps.

```
play idea
```

While not a production ready app, this basic example illustrates non blocking, reactive data flow from a remote datasource and back to a client and the use of Iteratees and Enumerators to
consume streams of data as well as how simple it is to get started developing web apps with Scala, Play and Heroku.

# License

Copyright (C) 2012 47 Degrees, LLC
http://47deg.com
hello@47deg.com

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.







