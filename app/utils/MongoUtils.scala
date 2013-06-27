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
