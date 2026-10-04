package restaurants

import com.mongodb.client.MongoCollection
import org.bson.Document
import restaurants.config.AppConfig
import restaurants.db.{IndexManager, MongoConnection}
import restaurants.india.{IndiaAnalytics, IndiaIndexes, IndiaService, MongoIndiaRepository}

/** Wires the object graph once: connection → repository → services.
  * The CLI, the web server and the importer all start from here (composition).
  */
final class AppContext private (val connection: MongoConnection) extends AutoCloseable {

  def config: AppConfig = connection.config

  def collection: MongoCollection[Document] = connection.restaurants

  val repository = new MongoIndiaRepository(collection)
  val service    = new IndiaService(repository)
  val analytics  = new IndiaAnalytics(collection)
  val indexes    = new IndexManager(collection, IndiaIndexes.specs)

  def namespace: String = s"${config.database}.${config.collection}"

  override def close(): Unit = connection.close()
}

object AppContext {

  def connect(): Either[AppError, AppContext] =
    for {
      config     <- AppConfig.load().left.map(ConfigError(_))
      connection <- MongoConnection.open(config)
    } yield new AppContext(connection)
}
