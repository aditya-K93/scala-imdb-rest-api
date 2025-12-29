package config

import enumeratum.*

sealed abstract class AppEnvironment extends EnumEntry

object AppEnvironment extends Enum[AppEnvironment] with CirisEnum[AppEnvironment]:
  case object Test extends AppEnvironment
  case object Prod extends AppEnvironment

  val values: IndexedSeq[AppEnvironment] = findValues
