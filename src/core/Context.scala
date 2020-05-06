package mutatus

import com.google.cloud.datastore.{DatastoreReader, DatastoreWriter, Key, FullEntity, DatastoreException}

sealed trait Context {
  implicit val service: Service
}

object Context {
  implicit def default(implicit svc: Service) = Default(svc)

  sealed trait ReadApi {
    self: Context =>
    val read: DatastoreReader
  }
  sealed trait WriteApi {
    self: Context =>
    val write: DatastoreWriter
    def saveAll(entities: Traversable[FullEntity[_]]): Result[Unit]
    def deleteAll(keys: Traversable[Key]): Result[Unit]
  }

  /**
    * Default context used to perform non-batched operations.
    */
  case class Default(service: Service)
      extends Context
      with ReadApi
      with WriteApi {
    val read: DatastoreReader = service.datastore
    val write: DatastoreWriter = service.datastore
    def deleteAll(keys: Traversable[Key]): Result[Unit] = Result {
      val batch = service.datastore.newBatch()
      batch.delete(keys.toList: _*)
      batch.submit()
    }
    def saveAll(entities: Traversable[FullEntity[_]]): Result[Unit] = Result {
      val batch = service.datastore.newBatch()
      batch.put(entities.toList: _*)
      batch.submit()
    }
  }

  /**
    * Context used to performs batched operations using Datastore Transactions API.
    */
  private[mutatus] case class Transaction(service: Service)
      extends Context
      with ReadApi
      with WriteApi {
    val tx = service.datastore.newTransaction()
    val read: DatastoreReader = tx
    val write: DatastoreWriter = tx
    def deleteAll(keys: Traversable[Key]): Result[Unit] =
      Result(write.delete(keys.toList: _*))
    def saveAll(entities: Traversable[FullEntity[_]]): Result[Unit] =
      Result(write.put(entities.toList: _*))
  }

  /**
    * Context used for batched operations using Datastore Batch API. It enabled only Write operations.
    */
  private[mutatus] case class Batch(service: Service)
      extends Context
      with WriteApi {
    val batch = service.datastore.newBatch()
    val write: DatastoreWriter = batch

    def deleteAll(keys: Traversable[Key]): Result[Unit] =
      Result(write.delete(keys.toList: _*))
    def saveAll(entities: Traversable[FullEntity[_]]): Result[Unit] =
      Result(write.put(entities.toList: _*))
  }
}

