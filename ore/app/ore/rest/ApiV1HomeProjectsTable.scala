package ore.rest

import ore.db.DbRef
import ore.db.impl.OrePostgresDriver.api._
import ore.models.project.Project

import io.circe.Json

class ApiV1HomeProjectsTable(tag: Tag) extends Table[HomeProjectsV1](tag, "home_projects") {

  def id               = column[DbRef[Project]]("id")
  def promotedVersions = column[Json]("promoted_versions")

  override def * = (id, promotedVersions) <> ((HomeProjectsV1.apply _).tupled, HomeProjectsV1.unapply)
}

case class HomeProjectsV1(id: DbRef[Project], promotedVersions: Json)
