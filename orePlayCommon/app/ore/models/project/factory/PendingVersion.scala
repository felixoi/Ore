package ore.models.project.factory

import scala.language.higherKinds

import play.api.cache.SyncCacheApi

import ore.Cacheable
import ore.data.project.Dependency
import ore.data.{Color, Platform}
import ore.db.{DbRef, Model, ModelService}
import ore.models.project._
import ore.models.project.io.PluginFileWithData
import ore.models.user.User

import zio.blocking.Blocking
import zio.interop.catz._
import zio.{Task, ZIO}

/**
  * Represents a pending version to be created later.
  *
  * @param plugin         Uploaded plugin
  */
case class PendingVersion(
    versionString: String,
    dependencies: List[Dependency],
    description: Option[String],
    projectId: DbRef[Project],
    fileSize: Long,
    hash: String,
    fileName: String,
    authorId: DbRef[User],
    plugin: PluginFileWithData,
    createForumPost: Boolean,
    cacheApi: SyncCacheApi
) extends Cacheable {

  def complete(
      project: Model[Project],
      factory: ProjectFactory
  ): ZIO[Blocking, Nothing, (Model[Project], Model[Version], Seq[Model[VersionTag]])] =
    free[Task].orDie *> factory.createVersion(project, this)

  override def key: String = s"$projectId/$versionString"

  def dependenciesAsGhostTags: Seq[VersionTag] =
    Platform.ghostTags(-1L, dependencies)

  def asVersion(projectId: DbRef[Project]): Version = Version(
    versionString = versionString,
    dependencyIds = dependencies.map {
      case Dependency(pluginId, Some(version)) => s"$pluginId:$version"
      case Dependency(pluginId, None)          => pluginId
    },
    description = description,
    projectId = projectId,
    fileSize = fileSize,
    hash = hash,
    authorId = Some(authorId),
    fileName = fileName,
    createForumPost = createForumPost
  )
}
