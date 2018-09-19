package discourse

import java.nio.file.Path

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

import db.ModelService
import db.impl.access.ProjectBase
import models.project.{Project, Version}
import models.user.User
import ore.OreConfig
import util.StringUtils._

import akka.actor.Scheduler
import com.google.common.base.Preconditions.{checkArgument, checkNotNull}
import org.spongepowered.play.discourse.DiscourseApi

/**
  * An implementation of [[DiscourseApi]] suited to Ore's needs.
  *
  * Note: It is very important that the implementor of this trait is a
  * singleton, otherwise countless threads will be spawned from this object's
  * [[RecoveryTask]].
  */
trait OreDiscourseApi extends DiscourseApi {

  /** Initialize before start() */
  var projects: ProjectBase = _
  var isEnabled             = true

  /** Username of admin account to move topics to secured categories */
  val admin: String

  /** The category where project topics are posted to */
  val categoryDefault: Int

  /** The category where deleted project topics are moved to */
  val categoryDeleted: Int

  /** Path to project topic template */
  val topicTemplatePath: Path

  /** Path to version release template */
  val versionReleasePostTemplatePath: Path

  /** Rate at which to retry failed attempts */
  val retryRate: FiniteDuration

  /** Scheduler for maintaining synchronization when requests fail */
  val scheduler: Scheduler

  /** The base URL for this instance */
  val baseUrl: String

  val templates: Templates = new Templates

  private var recovery: RecoveryTask = _

  //This executionContext, and modelService should only be used in start() which is called from Bootstrap
  def bootstrapExecutionContext: ExecutionContext
  def bootstrapService: ModelService
  def bootstrapConfig: OreConfig

  /**
    * Initializes and starts this API instance.
    */
  def start(): Unit = {
    if (!this.isEnabled) {
      Logger.info("Discourse API initialized in disabled mode.")
      return
    }
    checkNotNull(this.projects, "projects are null", "")
    this.recovery = new RecoveryTask(this.scheduler, this.retryRate, this)(
      bootstrapExecutionContext,
      bootstrapService,
      bootstrapConfig
    )
    this.recovery.start()
    Logger.info("Discourse API initialized.")
  }

  /**
    * Creates a new topic for the specified [[Project]].
    *
    * @param project Project to create topic for.
    * @return        True if successful
    */
  def createProjectTopic(
      project: Project
  )(implicit ec: ExecutionContext, service: ModelService, config: OreConfig): Future[Project] = {
    if (!this.isEnabled)
      return Future.successful(project)
    checkArgument(project.isDefined, "undefined project", "")
    val content = this.templates.projectTopic(project)
    val title   = this.templates.projectTitle(project)

    createTopic(
      poster = project.ownerName,
      title = title,
      content = content,
      categoryId = Some(this.categoryDefault)
    ).flatMap {
        case Left(errors) =>
          // Request went through but Discourse responded with errors
          // Don't schedule a retry because this will just keep happening
          val message =
            s"""|Request to create project topic was successful but Discourse responded with errors:
                |Project: ${project.url}
                |Title: $title
                |Content: $content
                |Errors: ${errors.toString}""".stripMargin
          Logger.warn(message)
          project.logger.flatMap(_.err(message)).map(_ => project)
        case Right(topic) =>
          // Topic created!
          // Catch some unexpected cases (should never happen)
          if (!topic.isTopic)
            throw new RuntimeException("project post isn't topic?")
          if (topic.username != project.ownerName)
            throw new RuntimeException("project post user isn't owner?")

          Logger.debug(s"""|New project topic:
                           |Project: ${project.url}
                           |Topic ID: ${topic.topicId}
                           |Post ID: ${topic.postId}""".stripMargin)

          // Update the post and topic id in the project
          service.update(project.copy(topicId = Some(topic.topicId), postId = Some(topic.postId)))
      }
      .transform(
        identity,
        e => {
          // Something went wrong. Turn on debug mode to gez debug messages from play discourse for further investigations.
          Logger.warn(s"Could not create project topic for project ${project.url}. Rescheduling...")
          e
        }
      )
  }

  /**
    * Updates a [[Project]]'s forum topic with the appropriate content.
    *
    * @param project  Project to update topic for
    * @return         True if successful
    */
  def updateProjectTopic(
      project: Project
  )(implicit ec: ExecutionContext, service: ModelService, config: OreConfig): Future[Boolean] = {
    if (!this.isEnabled)
      return Future.successful(true)
    checkArgument(project.isDefined, "undefined project", "")
    checkArgument(project.topicId.isDefined, "undefined topic id", "")
    checkArgument(project.postId.isDefined, "undefined post id", "")

    val topicId   = project.topicId
    val postId    = project.postId
    val title     = this.templates.projectTitle(project)
    val content   = this.templates.projectTopic(project)
    val ownerName = project.ownerName

    // Set flag so that if we are interrupted we will remember to do it later
    service.update(project.copy(isTopicDirty = true))

    // A promise for our final result
    val resultPromise: Promise[Boolean] = Promise()

    def logErrors(errors: List[String]): Unit = {
      val message = "Request to update project topic was successful but Discourse responded with errors:\n" +
        s"Project: ${project.url}\n" +
        s"Topic ID: $topicId\n" +
        s"Title: $title\n" +
        s"Errors: ${errors.toString}"
      project.logger.map(_.err(message))
      Logger.warn(message)
    }

    def fail(message: String) = {
      Logger.warn(s"Couldn't update project topic for project ${project.url}: " + message)
      resultPromise.success(false)
    }

    // Update title
    updateTopic(
      username = ownerName,
      topicId = topicId.get,
      title = Some(title),
      categoryId = None
    ).andThen {
      case Success(errors) =>
        if (errors.nonEmpty) {
          // Request went through but Discourse responded with errors
          logErrors(errors)
          resultPromise.success(false)
        } else {
          // Title updated! Update the content now
          updatePost(
            username = ownerName,
            postId = postId.get,
            content = content
          ).andThen {
            case Success(updateErrors) =>
              if (updateErrors.nonEmpty) {
                logErrors(errors)
                resultPromise.success(false)
              } else {
                // Title and content updated!
                Logger.debug(s"Project topic updated for ${project.url}.")
                service.update(project.copy(isTopicDirty = false))
                resultPromise.success(true)
              }
            case Failure(e) =>
              fail(e.getMessage)
          }
        }
      case Failure(e) =>
        // Discourse never received our request!
        fail(e.getMessage)
    }

    resultPromise.future
  }

  /**
    * Posts a new reply to a [[Project]]'s forum topic.
    *
    * @param project  Project to post to
    * @param user     User who is posting
    * @param content  Post content
    * @return         List of errors Discourse returns
    */
  def postDiscussionReply(project: Project, user: User, content: String)(
      implicit ec: ExecutionContext
  ): Future[List[String]] = {
    if (!this.isEnabled) {
      Logger.warn("Tried to post discussion with API disabled?") // Shouldn't be reachable
      return Future.successful(List.empty)
    }
    checkArgument(project.topicId.isDefined, "undefined topic id", "")
    // It's OK if Discourse responds with errors here, we will just show them to the user
    createPost(
      username = user.name,
      topicId = project.topicId.get,
      content = content
    ).map(_.left.toOption.getOrElse(List.empty)).recover {
      case _: Exception =>
        List("Could not connect to forums, please try again later.")
    }
  }

  /**
    * Posts a new "Version release" to a [[Project]]'s forum topic.
    *
    * @param project Project to post release to
    * @param version Version of project
    * @return
    */
  def postVersionRelease(project: Project, version: Version, content: Option[String])(
      implicit ec: ExecutionContext,
      service: ModelService
  ): Future[List[String]] = {
    if (!this.isEnabled)
      return Future.successful(List.empty)
    checkArgument(project.isDefined, "undefined project", "")
    checkArgument(version.isDefined, "undefined version", "")
    checkArgument(version.projectId == project.id.value, "invalid version project pair", "")
    project.owner.user.flatMap { user =>
      postDiscussionReply(project, user, content = this.templates.versionRelease(project, version, content)).map {
        errors =>
          if (errors.nonEmpty) {
            errors.foreach(error => project.logger.map(_.err(error)))
          }
          errors
      }
    }
  }

  def changeTopicVisibility(project: Project, isVisible: Boolean)(implicit ec: ExecutionContext): Future[Boolean] = {
    if (!this.isEnabled)
      return Future.successful(true)

    checkArgument(project.isDefined, "undefined project", "")
    checkArgument(project.topicId.isDefined, "undefined topic id", "")

    val resultPromise: Promise[Boolean] = Promise()
    updateTopic(
      this.admin,
      project.topicId.get,
      None,
      Some(if (isVisible) this.categoryDefault else this.categoryDeleted)
    ).foreach { list =>
      if (list.isEmpty) {
        Logger.debug(s"Successfully updated topic category for project: ${project.url}.")
        resultPromise.success(true)
      } else {
        Logger.warn(s"Couldn't hide topic for project: ${project.url}. Message: " + list.mkString(" | "))
        resultPromise.success(false)
      }
    }

    resultPromise.future
  }

  /**
    * Delete's a [[Project]]'s forum topic.
    *
    * @param project  Project to delete topic for
    * @return         True if deleted
    */
  def deleteProjectTopic(project: Project)(implicit ec: ExecutionContext, service: ModelService): Future[Boolean] = {
    if (!this.isEnabled)
      return Future.successful(true)
    checkArgument(project.isDefined, "undefined project", "")
    checkArgument(project.topicId.isDefined, "undefined topic id", "")

    def logFailure(): Unit = Logger.warn(s"Couldn't delete topic for project: ${project.url}. Rescheduling...")

    val resultPromise: Promise[Boolean] = Promise()
    deleteTopic(this.admin, project.topicId.get).andThen {
      case Success(result) =>
        if (!result) {
          logFailure()
          resultPromise.success(false)
        } else {
          Logger.debug(s"Successfully deleted project topic for: ${project.url}.")
          resultPromise.completeWith(service.update(project.copy(topicId = None, postId = None)).map(_ => true))
        }
      case Failure(_) =>
        logFailure()
        resultPromise.success(false)
    }

    resultPromise.future
  }

  /**
    * Returns a future result of the amount of users on Discourse that are in
    * this list.
    *
    * @param users  Users to check
    * @return       Amount on discourse
    */
  def countUsers(users: List[String])(implicit ec: ExecutionContext): Future[Int] = {
    if (!this.isEnabled)
      return Future.successful(0)
    var futures: Seq[Future[Boolean]] = Seq.empty
    for (user <- users) {
      futures :+= userExists(user).recover {
        case _: Exception => false
      }
    }
    Future.sequence(futures).map(results => results.count(_ == true))
  }

  /**
    * Discourse content templates.
    */
  class Templates {

    /** Creates a new title for a project topic. */
    def projectTitle(project: Project): String = project.name + project.description.map(d => s" - $d").getOrElse("")

    /** Generates the content for a project topic. */
    def projectTopic(
        project: Project
    )(implicit ec: ExecutionContext, config: OreConfig, service: ModelService): String = readAndFormatFile(
      OreDiscourseApi.this.topicTemplatePath,
      project.name,
      OreDiscourseApi.this.baseUrl + '/' + project.url,
      project.homePage.contents
    )

    /** Generates the content for a version release post. */
    def versionRelease(project: Project, version: Version, content: Option[String]): String = {
      implicit val p: Project = project
      readAndFormatFile(
        OreDiscourseApi.this.versionReleasePostTemplatePath,
        project.name,
        OreDiscourseApi.this.baseUrl + '/' + project.url,
        OreDiscourseApi.this.baseUrl + '/' + version.url,
        content.getOrElse("*No description given.*")
      )
    }

  }

}
