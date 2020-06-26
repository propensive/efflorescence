package mutatus

sealed abstract class MutatusException(msg: String) extends Exception(msg)
case class SerializationException(msg: String) extends MutatusException(msg)
case class DatabaseException(exception: Exception)
    extends MutatusException(exception.getMessage())
case class NotSavedException(kind: String)
    extends MutatusException(
      s"Entity of type $kind cannot be deleted becasue it has not been saved"
    )