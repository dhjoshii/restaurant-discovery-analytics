package restaurants.db

import com.mongodb.MongoCommandException
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.IndexOptions
import org.bson.Document

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/** An index this application relies on, plus *why* it exists.
  * `multikey` marks indexes over an array field (one key per array element).
  */
final case class IndexSpec(keys: List[(String, Int)], unique: Boolean, purpose: String, multikey: Boolean = false) {

  /** MongoDB's default naming convention, e.g. `city_1_rating_-1` or `restaurant_id_1`. */
  def name: String = keys.map((field, dir) => s"${field}_$dir").mkString("_")

  def isCompound: Boolean = keys.size > 1

  def keyDocument: Document = keys.foldLeft(new Document()) { case (doc, (field, dir)) => doc.append(field, dir) }
}

/** A live index as reported by `listIndexes`. */
final case class IndexInfo(name: String, keys: List[(String, Int)], unique: Boolean, multikeyField: Boolean, purpose: String) {
  def isCompound: Boolean = keys.size > 1
}

enum IndexStatus {
  case Created, AlreadyExisted
  case CreatedNonUnique(reason: String)
  case Failed(reason: String)
}

final case class IndexResult(spec: IndexSpec, status: IndexStatus)

/** Creates and describes the indexes that back every search and aggregation of one collection. */
final class IndexManager(collection: MongoCollection[Document], val specs: List[IndexSpec]) {

  import IndexManager.*

  /** Idempotently creates every index in `specs`. */
  def ensureIndexes(): List[IndexResult] = {
    val existing = Try(collection.listIndexes().into(new java.util.ArrayList[Document]()).asScala.map(_.getString("name")).toSet)
      .getOrElse(Set.empty[String])

    specs.map { spec =>
      if (existing.contains(spec.name)) IndexResult(spec, IndexStatus.AlreadyExisted)
      else
        create(spec, spec.unique) match {
          case Success(_) => IndexResult(spec, IndexStatus.Created)
          case Failure(e: MongoCommandException) if spec.unique && e.getErrorCode == DuplicateKeyCode =>
            // The data already contains duplicates, so fall back to a regular index.
            create(spec, unique = false) match {
              case Success(_)  => IndexResult(spec, IndexStatus.CreatedNonUnique("existing duplicate values"))
              case Failure(e2) => IndexResult(spec, IndexStatus.Failed(e2.getMessage))
            }
          case Failure(e) => IndexResult(spec, IndexStatus.Failed(Option(e.getMessage).getOrElse(e.toString)))
        }
    }
  }

  def listIndexes(): List[IndexInfo] =
    collection
      .listIndexes()
      .into(new java.util.ArrayList[Document]())
      .asScala
      .toList
      .map { doc =>
        val name   = doc.getString("name")
        val keyDoc = Option(doc.get("key")).collect { case d: Document => d }.getOrElse(new Document())
        val keys = keyDoc.entrySet().asScala.toList.map { e =>
          val dir = e.getValue match {
            case n: Number => n.intValue()
            case _         => 0 // text / hashed / 2dsphere indexes use string values
          }
          e.getKey -> dir
        }
        IndexInfo(
          name = name,
          keys = keys,
          unique = Option(doc.get("unique")).contains(true),
          multikeyField = specs.exists(s => s.name == name && s.multikey),
          purpose = purposeOf(name)
        )
      }

  def purposeOf(indexName: String): String =
    if (indexName == "_id_") "Default primary-key index created by MongoDB"
    else specs.find(_.name == indexName).map(_.purpose).getOrElse("Created outside this application")

  private def create(spec: IndexSpec, unique: Boolean): Try[String] =
    Try(collection.createIndex(spec.keyDocument, new IndexOptions().name(spec.name).unique(unique)))
}

object IndexManager {

  private val DuplicateKeyCode = 11000
}
