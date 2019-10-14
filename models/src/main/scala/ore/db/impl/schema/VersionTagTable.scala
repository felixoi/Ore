package ore.db.impl.schema

import java.time.OffsetDateTime

import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.table.common.NameColumn
import ore.db.{DbRef, Model, ObjId, ObjOffsetDateTime}
import ore.models.project.{TagColor, Version, VersionTag}

class VersionTagTable(tag: Tag)
    extends ModelTable[VersionTag](tag, "project_version_tags")
    with NameColumn[VersionTag] {

  def versionId       = column[DbRef[Version]]("version_id")
  def data            = column[String]("data")
  def color           = column[TagColor]("color")
  def platformVersion = column[String]("platform_version")

  override def * = {
    val convertedApply: (
        (Option[DbRef[VersionTag]], DbRef[Version], String, Option[String], TagColor, Option[String])
    ) => Model[VersionTag] = {
      case (id, versionIds, name, data, color, platformVersion) =>
        Model(
          ObjId.unsafeFromOption(id),
          ObjOffsetDateTime(OffsetDateTime.MIN),
          VersionTag(versionIds, name, data, color, platformVersion)
        )
    }
    val convertedUnapply
        : PartialFunction[Model[VersionTag], (Option[DbRef[VersionTag]], DbRef[Version], String, Option[String], TagColor, Option[String])] = {
      case Model(id, _, VersionTag(versionIds, name, data, color, platformVersion)) =>
        (id.unsafeToOption, versionIds, name, data, color, platformVersion)
    }
    (id.?, versionId, name, data.?, color, platformVersion.?) <> (convertedApply, convertedUnapply.lift)
  }
}
