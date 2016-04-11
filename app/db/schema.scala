package db

import java.sql.Timestamp

import db.OrePostgresDriver.api._
import models.project.author.Team
import models.project.{Channel, Page, Project, Version}
import models.user.User

/*
 * Database schema definitions. Changes must be first applied as an evolutions
 * SQL script in "conf/evolutions/default", then here, then in the associated
 * model. Tables should have their columns defined in the order in which they
 * appear in the DB.
 */

class ProjectTable(tag: Tag) extends ModelTable[Project](tag, "projects") {

  def id                    =   column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt             =   column[Timestamp]("created_at")
  def pluginId              =   column[String]("plugin_id")
  def name                  =   column[String]("name")
  def slug                  =   column[String]("slug")
  def ownerName             =   column[String]("owner_name")
  def authors               =   column[List[String]]("authors")
  def homepage              =   column[String]("homepage")
  def recommendedVersionId  =   column[Int]("recommended_version_id")
  def categoryId            =   column[Int]("category_id")
  def views                 =   column[Int]("views", O.Default(0))
  def downloads             =   column[Int]("downloads", O.Default(0))
  def stars                 =   column[Int]("stars", O.Default(0))
  def issues                =   column[String]("issues")
  def source                =   column[String]("source")
  def description           =   column[String]("description")

  override def pk = this.id

  override def * = (id.?, createdAt.?, pluginId, name, slug, ownerName,
                    authors, homepage.?, recommendedVersionId.?, categoryId,
                    views, downloads, stars, issues.?, source.?,
                    description.?) <> ((Project.apply _).tupled, Project.unapply)

}

class ProjectViewsTable(tag: Tag) extends Table[(Option[Int], Option[String],
                                                 Option[Int], Int)](tag, "project_views") {

  def id          =   column[Int]("id", O.PrimaryKey, O.AutoInc)
  def cookie      =   column[String]("cookie")
  def userId      =   column[Int]("user_id")
  def projectId   =   column[Int]("project_id")

  override def * = (id.?, cookie.?, userId.?, projectId)

}

class ProjectStarsTable(tag: Tag) extends Table[(Int, Int)](tag, "project_stars") {

  def userId      =   column[Int]("user_id")
  def projectId   =   column[Int]("project_id")

  override def * = (userId, projectId)

}

class PagesTable(tag: Tag) extends ModelTable[Page](tag, "pages") {

  def id            =   column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt     =   column[Timestamp]("created_at")
  def projectId     =   column[Int]("project_id")
  def name          =   column[String]("name")
  def slug          =   column[String]("slug")
  def contents      =   column[String]("contents")
  def isDeletable   =   column[Boolean]("is_deletable")

  override def pk = this.id

  override def * = (id.?, createdAt.?, projectId,
                    name, slug, contents, isDeletable) <> ((Page.apply _).tupled, Page.unapply)

}

class ChannelTable(tag: Tag) extends ModelTable[Channel](tag, "channels") {

  def id          =   column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt   =   column[Timestamp]("created_at")
  def name        =   column[String]("name")
  def colorId     =   column[Int]("color_id")
  def projectId   =   column[Int]("project_id")

  override def pk = this.id

  override def * = (id.?, createdAt.?, name, colorId, projectId) <> ((Channel.apply _).tupled, Channel.unapply)
}

class VersionTable(tag: Tag) extends ModelTable[Version](tag, "versions") {

  def id              =   column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt       =   column[Timestamp]("created_at")
  def versionString   =   column[String]("version_string")
  def dependencies    =   column[List[String]]("dependencies")
  def description     =   column[String]("description")
  def assets          =   column[String]("assets")
  def downloads       =   column[Int]("downloads")
  def projectId       =   column[Int]("project_id")
  def channelId       =   column[Int]("channel_id")

  override def pk = this.id

  override def * = (id.?, createdAt.?, versionString, dependencies, description.?,
                    assets.?, downloads, projectId, channelId) <> ((Version.apply _).tupled, Version.unapply)
}

class VersionDownloadsTable(tag: Tag) extends Table[(Option[Int], Option[String],
                                                     Option[Int], Int)](tag, "version_downloads") {

  def id          =   column[Int]("id", O.PrimaryKey, O.AutoInc)
  def cookie      =   column[String]("cookie")
  def userId      =   column[Int]("user_id")
  def versionId   =   column[Int]("version_id")

  override def * = (id.?, cookie.?, userId.?, versionId)

}

class UserTable(tag: Tag) extends ModelTable[User](tag, "users") {

  def externalId  =   column[Int]("external_id", O.PrimaryKey)
  def createdAt   =   column[Timestamp]("created_at")
  def name        =   column[String]("name")
  def username    =   column[String]("username")
  def email       =   column[String]("email")
  def roles       =   column[List[Int]]("roles")
  def tagline     =   column[String]("tagline")

  def pk = this.externalId

  override def * = (externalId, createdAt.?, name.?,
                    username, email, roles, tagline.?) <> ((User.apply _).tupled, User.unapply)

}

class TeamTable(tag: Tag) extends ModelTable[Team](tag, "teams") {

  def id          =   column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt   =   column[Timestamp]("created_at")
  def name        =   column[String]("name")

  def pk = this.id

  override def * = (id.?, createdAt.?, name) <> (Team.tupled, Team.unapply)

}
