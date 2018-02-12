/* Efflorescence, version 0.2.0. Copyright 2016 Jon Pretty, Propensive Ltd.
 *
 * The primary distribution site is: http://propensive.com/
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package efflorescence

import magnolia._
import adversaria._
import com.google.cloud.datastore._
import util.Try
import language.experimental.macros, language.existentials
import annotation.StaticAnnotation

/** Efflorescence package object */
object `package` {
  /** provides a default instance of the GCP Datastore service */
  implicit val defaultDataStore: Service = Service(DatastoreOptions.getDefaultInstance.getService)

  /** provides `save` and `delete` methods on case class instances */
  implicit class DataExt[T <: Product](value: T) {
    /** saves the case class as a Datastore entity */
    def save()(implicit svc: Service, encoder: Encoder[T], id: IdField[T], dao: Dao[T]): Ref[T] = {
      val keyValues = encoder.encode("", value)

      new Ref[T](svc.readWrite.put {
        keyValues.foldLeft(Entity.newBuilder(dao.keyFactory.newKey(id.key(value)))) {
          case (entity, (key, dsType: DsType)) => dsType.set(entity, key)
        }.build()
      }.getKey)
    }

    /** deletes the Datastore entity with this ID */
    def delete()(implicit svc: Service, id: IdField[T], dao: Dao[T]): Unit =
      svc.readWrite.delete(dao.keyFactory.newKey(id.key(value)))
  }
}

final class id() extends StaticAnnotation

/** a reference to another case class instance stored in the GCP Datastore */
case class Ref[T](ref: Key) {

  /** resolves the reference and returns a case class instance */
  def apply()(implicit svc: Service, decoder: Decoder[T]): T = decoder.decode(svc.read.get(ref))
  override def toString: String = s"$Ref[${ref.getKind}]($key)"

  /** a `String` version of the key contained by this reference */
  def key: String = ref.getNameOrId.toString
}

/** companion object for `Namespace`, providing a default namespace */
object Namespace { implicit val defaultNamespace: Namespace = Namespace("") }

/** a GCP namespace */
case class Namespace(name: String) {
  def option: Option[String] = if(name.isEmpty) None else Some(name)
}

/** companion object for Geo instances */
object Geo { def apply(latLng: LatLng): Geo = Geo(latLng.getLatitude, latLng.getLongitude) }

/** a geographical position, with latitude and longitude */
case class Geo(lat: Double, lng: Double) { def toLatLng = LatLng.of(lat, lng) }

/** a representation of the GCP Datastore service */
case class Service(readWrite: Datastore) { def read: DatastoreReader = readWrite }

/** typeclass for encoding a value into a type which can be stored in the GCP Datastore */
trait Encoder[T] { def encode(key: String, value: T): List[(String, DsType)] }

/** typeclass for decoding a value from the GCP Datastore into a Scala type */
trait Decoder[T] { def decode(obj: BaseEntity[_], prefix: String = ""): T }

/** typeclass for generating an ID field from a case class */
trait IdField[T] { def key(t: T): String }

object IdField {
  implicit def annotationId[T](implicit ann: FindMetadata[id, T]): IdField[T] =
    new IdField[T] { def key(t: T): String = ann.get(t).toString }
}

/** a data access object for a particular type */
case class Dao[T](kind: String)(implicit svc: Service, namespace: Namespace) {

  private[efflorescence] lazy val keyFactory = {
    val baseFactory = svc.readWrite.newKeyFactory().setKind(kind)
    namespace.option.foldLeft(baseFactory)(_.setNamespace(_))
  }

  /** returns an iterator of all the values of this type stored in the GCP Platform */
  def all()(implicit decoder: Decoder[T]): Iterator[T] = {
    val baseQueryBuilder = Query.newEntityQueryBuilder().setKind(kind)
    val query: EntityQuery = namespace.option.foldLeft(baseQueryBuilder)(_.setNamespace(_)).build()
    val results: QueryResults[Entity] = svc.read.run(query)

    new Iterator[Entity] {
      def next(): Entity = results.next()
      def hasNext: Boolean = results.hasNext
    }.map(decoder.decode(_))
  }
}

/** generic type for Datastore datatypes */
class DsType(val set: (Entity.Builder, String) => Entity.Builder)

/** companion object for DsType */
object DsType {
  final case class DsString(value: String) extends DsType(_.set(_, value))
  final case class DsLong(value: Long) extends DsType(_.set(_, value))
  final case class DsBoolean(value: Boolean) extends DsType(_.set(_, value))
  final case class DsDouble(value: Double) extends DsType(_.set(_, value))
  final case class DsKey(value: Ref[_]) extends DsType(_.set(_, value.ref))
  final case class DsLatLng(value: Geo) extends DsType(_.set(_, value.toLatLng))
  final case object DsRemove extends DsType(_.remove(_))
}

/** companion object for `Decoder`, including Magnolia generic derivation */
object Decoder {
  type Typeclass[T] = Decoder[T]
  
  /** generates a new `Decoder` for the type `T` */
  implicit def gen[T]: Decoder[T] = macro Magnolia.gen[T]

  /** combines `Decoder`s for each parameter of the case class `T` into a `Decoder` for `T` */
  def combine[T](caseClass: CaseClass[Decoder, T]): Decoder[T] = (obj, prefix) =>
    caseClass.construct { param =>
      param.typeclass.decode(obj, if (prefix.isEmpty) param.label else s"$prefix.${param.label}")
    }

  /** tries `Decoder`s for each subtype of sealed trait `T` until one doesn`t throw an exception
   *
   *  This is a suboptimal implementation, and a better solution may be possible with more work. */
  def dispatch[T](st: SealedTrait[Decoder, T]): Decoder[T] = (obj, prefix) =>
    st.subtypes.view.map { sub => Try(sub.typeclass.decode(obj, prefix)) }.find(_.isSuccess).get.get

  implicit val int: Decoder[Int] = _.getLong(_).toInt
  implicit val string: Decoder[String] = _.getString(_)
  implicit val long: Decoder[Long] = _.getLong(_)
  implicit val byte: Decoder[Byte] = _.getLong(_).toByte
  implicit val short: Decoder[Short] = _.getLong(_).toShort
  implicit val char: Decoder[Char] = _.getString(_).head
  implicit val boolean: Decoder[Boolean] = _.getBoolean(_)
  implicit val double: Decoder[Double] = _.getDouble(_)
  implicit val float: Decoder[Float] = _.getDouble(_).toFloat
  implicit val geo: Decoder[Geo] = (obj, name) => Geo(obj.getLatLng(name))
  implicit def ref[T: Dao: IdField]: Decoder[Ref[T]] = (obj, ref) => Ref[T](obj.getKey(ref))
  
  implicit def optional[T: Decoder]: Decoder[Option[T]] =
    (obj, key) => if(obj.contains(key)) Some(implicitly[Decoder[T]].decode(obj)) else None

  implicit def list[T: Decoder]: Decoder[List[T]] = new Decoder[List[T]] {
    def decode(obj: BaseEntity[_], prefix: String): List[T] = {
      Stream.from(0).map { idx =>
        Try(implicitly[Decoder[T]].decode(obj, s"$prefix.$idx"))
      }.takeWhile(_.isSuccess).map(_.get).to[List]
    }
  }
}

/** companion object for `Encoder`, including Magnolia generic derivation */
object Encoder {
  type Typeclass[T] = Encoder[T]
  
  /** generates a new `Encoder` for the type `T` */
  implicit def gen[T]: Encoder[T] = macro Magnolia.gen[T]

  /** combines `Encoder`s for each parameter of the case class `T` into a `Encoder` for `T` */
  def combine[T](caseClass: CaseClass[Encoder, T]): Encoder[T] = { (key, value) =>
    caseClass.parameters.to[List].flatMap { param =>
      param.typeclass.encode(param.label, param.dereference(value)) map {
        case (key, v) => (s"${param.label}.$key", v)
      }
    }
  }

  /** chooses the appropriate `Encoder` of a subtype of the sealed trait `T` based on its type */
  def dispatch[T](sealedTrait: SealedTrait[Encoder, T]): Encoder[T] =
    (key, value) => sealedTrait.dispatch(value) { st => st.typeclass.encode(key, st.cast(value)) }

  implicit val string: Encoder[String] = (k, v) => List((k, DsType.DsString(v)))
  implicit val long: Encoder[Long] = (k, v) => List((k, DsType.DsLong(v)))
  implicit val int: Encoder[Int] = (k, v) => List((k, DsType.DsLong(v)))
  implicit val short: Encoder[Short] = (k, v) => List((k, DsType.DsLong(v)))
  implicit val char: Encoder[Char] = (k, v) => List((k, DsType.DsString(v.toString)))
  implicit val byte: Encoder[Byte] = (k, v) => List((k, DsType.DsLong(v)))
  implicit val boolean: Encoder[Boolean] = (k, v) => List((k, DsType.DsBoolean(v)))
  implicit val double: Encoder[Double] = (k, v) => List((k, DsType.DsDouble(v)))
  implicit val float: Encoder[Float] = (k, v) => List((k, DsType.DsDouble(v)))
  implicit val geo: Encoder[Geo] = (k, v) => List((k, DsType.DsLatLng(v)))
  implicit def ref[T]: Encoder[Ref[T]] = (k, v) => List((k, DsType.DsKey(v)))

  implicit def optional[T: Encoder]: Encoder[Option[T]] = {
    case (k, None) => List((k, DsType.DsRemove))
    case (k, Some(value)) => implicitly[Encoder[T]].encode(k, value)
  }

  implicit def list[T: Encoder]: Encoder[List[T]] = (prefix, list) =>
    list.zipWithIndex.flatMap { case (t, idx) =>
      implicitly[Encoder[T]].encode(if(prefix.isEmpty) s"$idx" else s"$prefix.$idx", t)
    }
}

/** companion object for data access objects */
object Dao {
  implicit def apply[T](implicit metadata: TypeMetadata[T], namespace: Namespace): Dao[T] =
    Dao(metadata.typeName)
}