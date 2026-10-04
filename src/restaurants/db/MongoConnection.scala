package restaurants.db

import com.mongodb.client.{MongoClient, MongoClients, MongoCollection}
import com.mongodb.{ConnectionString, MongoClientSettings}
import org.bson.Document
import restaurants.config.AppConfig
import restaurants.{AppError, DatabaseError}

import java.util.concurrent.TimeUnit
import scala.util.Try

/** Owns the single [[MongoClient]] of the process.
  *
  * The client is private: callers only get the collection handle they need,
  * which keeps connection management encapsulated in one place.
  */
final class MongoConnection private (client: MongoClient, val config: AppConfig) extends AutoCloseable {

  /** The restaurants collection (`sample_restaurants.india_restaurants` by default). */
  def restaurants: MongoCollection[Document] = collection(config.collection)

  /** Any collection in the configured database (e.g. `india_restaurants`). */
  def collection(name: String): MongoCollection[Document] =
    client.getDatabase(config.database).getCollection(name)

  /** Round-trips a `ping` command; `Failure` means the cluster is unreachable. */
  def ping(): Try[Unit] = Try {
    client.getDatabase("admin").runCommand(new Document("ping", 1))
    ()
  }

  def serverVersion: Option[String] =
    Try(client.getDatabase("admin").runCommand(new Document("buildInfo", 1)).getString("version")).toOption

  override def close(): Unit = client.close()
}

object MongoConnection {

  /** Builds a client with short, explicit timeouts and verifies it with a ping. */
  def open(config: AppConfig): Either[AppError, MongoConnection] =
    Try {
      val settings = MongoClientSettings
        .builder()
        .applyConnectionString(new ConnectionString(config.mongoUri))
        .applicationName("restaurant-discovery-analytics")
        .applyToClusterSettings { b => b.serverSelectionTimeout(15, TimeUnit.SECONDS); () }
        .applyToSocketSettings { b => b.connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS); () }
        .build()
      new MongoConnection(MongoClients.create(settings), config)
    }.toEither.left
      .map(e => DatabaseError(s"Invalid MongoDB connection string: ${Option(e.getMessage).getOrElse("unknown error")}", e))
      .flatMap { connection =>
        connection.ping().toEither match {
          case Right(_) => Right(connection)
          case Left(error) =>
            connection.close()
            Left(AppError.fromThrowable(error))
        }
      }
}
