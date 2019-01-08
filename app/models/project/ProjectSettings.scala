package models.project

import java.nio.file.Files._
import java.sql.Timestamp
import java.time.Instant

import play.api.Logger

import db.impl.OrePostgresDriver.api._
import db.impl.schema.{ProjectRoleTable, ProjectSettingsTable, UserTable}
import db.{DbRef, Model, ModelQuery, ModelService, ObjId, ObjectTimestamp}
import form.project.ProjectSettingsForm
import models.user.{Notification, User}
import ore.permission.role.Role
import ore.project.io.ProjectFiles
import ore.project.{Category, ProjectOwned}
import ore.user.notification.NotificationType
import util.StringUtils._

import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import slick.lifted.TableQuery

/**
  * Represents a [[Project]]'s settings.
  *
  * @param id           Unique ID
  * @param createdAt    Instant of creation
  * @param projectId    ID of project settings belong to
  * @param homepage     Project homepage
  * @param issues      Project issues URL
  * @param source      Project source URL
  * @param licenseName Project license name
  * @param licenseUrl  Project license URL
  */
case class ProjectSettings(
    id: ObjId[ProjectSettings] = ObjId.Uninitialized(),
    createdAt: ObjectTimestamp = ObjectTimestamp.Uninitialized,
    projectId: DbRef[Project] = -1L,
    homepage: Option[String] = None,
    issues: Option[String] = None,
    source: Option[String] = None,
    licenseName: Option[String] = None,
    licenseUrl: Option[String] = None,
    forumSync: Boolean = true
) extends Model {

  override type M = ProjectSettings
  override type T = ProjectSettingsTable

  /**
    * Saves a submitted [[ProjectSettingsForm]] to the [[Project]].
    *
    * @param formData Submitted settings
    * @param messages MessagesApi instance
    */
  //noinspection ComparingUnrelatedTypes
  def save(project: Project, formData: ProjectSettingsForm)(
      implicit fileManager: ProjectFiles,
      service: ModelService,
      cs: ContextShift[IO]
  ): IO[(Project, ProjectSettings)] = {
    import cats.instances.vector._
    Logger.debug("Saving project settings")
    Logger.debug(formData.toString)
    val newOwnerId = formData.ownerId.getOrElse(project.ownerId)

    val queryOwnerName = TableQuery[UserTable].filter(_.id === newOwnerId).map(_.name)

    val updateProject = service.runDBIO(queryOwnerName.result.head).flatMap { ownerName =>
      service.updateIfDefined(
        project.copy(
          category = Category.values.find(_.title == formData.categoryName).get,
          description = noneIfEmpty(formData.description),
          ownerId = newOwnerId,
          ownerName = ownerName
        )
      )
    }

    val updateSettings = service.updateIfDefined(
      copy(
        issues = noneIfEmpty(formData.issues),
        source = noneIfEmpty(formData.source),
        licenseUrl = noneIfEmpty(formData.licenseUrl),
        licenseName = if (formData.licenseUrl.nonEmpty) Some(formData.licenseName) else licenseName,
        forumSync = formData.forumSync
      )
    )

    val modelUpdates = (updateProject, updateSettings).parTupled

    modelUpdates.flatMap { t =>
      // Update icon
      if (formData.updateIcon) {
        fileManager.getPendingIconPath(project).foreach { pendingPath =>
          val iconDir = fileManager.getIconDir(project.ownerName, project.name)
          if (notExists(iconDir))
            createDirectories(iconDir)
          list(iconDir).forEach(delete(_))
          move(pendingPath, iconDir.resolve(pendingPath.getFileName))
        }
      }

      // Handle member changes
      if (project.isDefined) {
        // Add new roles
        val dossier = project.memberships
        formData
          .build()
          .toVector
          .parTraverse { role =>
            dossier.addRole(project, role.copy(projectId = project.id.value))
          }
          .flatMap { roles =>
            val notifications = roles.map { role =>
              Notification(
                createdAt = ObjectTimestamp(Timestamp.from(Instant.now())),
                userId = role.userId,
                originId = project.ownerId,
                notificationType = NotificationType.ProjectInvite,
                messageArgs = NonEmptyList.of("notification.project.invite", role.role.title, project.name)
              )
            }

            service.bulkInsert(notifications)
          }
          .productR {
            // Update existing roles
            val usersTable = TableQuery[UserTable]
            // Select member userIds
            service
              .runDBIO(usersTable.filter(_.name.inSetBind(formData.userUps)).map(_.id).result)
              .flatMap { userIds =>
                import cats.instances.list._
                val roles = formData.roleUps.traverse { role =>
                  Role.projectRoles
                    .find(_.value == role)
                    .fold(IO.raiseError[Role](new RuntimeException("supplied invalid role type")))(IO.pure)
                }

                roles.map(xs => userIds.zip(xs))
              }
              .map {
                _.map {
                  case (userId, role) => updateMemberShip(userId).update(role)
                }
              }
              .flatMap(updates => service.runDBIO(DBIO.sequence(updates)).as(t))
          }
      } else IO.pure(t)
    }
  }

  private def memberShipUpdate(userId: Rep[DbRef[User]]) =
    TableQuery[ProjectRoleTable].filter(_.userId === userId).map(_.roleType)

  private lazy val updateMemberShip = Compiled(memberShipUpdate _)
}
object ProjectSettings {
  implicit val query: ModelQuery[ProjectSettings] =
    ModelQuery.from[ProjectSettings](TableQuery[ProjectSettingsTable], _.copy(_, _))

  implicit val isProjectOwned: ProjectOwned[ProjectSettings] = (a: ProjectSettings) => a.projectId
}
