package discourse

import scala.language.higherKinds

import ore.db.Model
import ore.discourse.DiscoursePost
import ore.models.project.{Project, Version}
import ore.models.user.User

import cats.Applicative

class OreDiscourseApiDisabled[F[_]](implicit F: Applicative[F]) extends OreDiscourseApi[F] {

  override def createProjectTopic(project: Model[Project]): F[Model[Project]] = F.pure(project)

  override def updateProjectTopic(project: Model[Project]): F[Boolean] = F.pure(true)

  override def postDiscussionReply(project: Project, user: User, content: String): F[Either[String, DiscoursePost]] =
    F.pure(Left("Tried to post discussion with API disabled"))

  override def createVersionPost(project: Model[Project], version: Model[Version]): F[Model[Version]] = F.pure(version)

  override def updateVersionPost(project: Model[Project], version: Model[Version]): F[Boolean] = F.pure(true)

  override def changeTopicVisibility(project: Project, isVisible: Boolean): F[Unit] = F.unit

  override def deleteProjectTopic(project: Model[Project]): F[Model[Project]] = F.pure(project)

  override def isAvailable: F[Boolean] = F.pure(false)
}
