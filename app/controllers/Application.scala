package controllers

import javax.inject.Inject

import controllers.routes.{Application => self}
import db.OrePostgresDriver.api._
import db.UserTable
import db.query.Queries
import db.query.Queries.now
import models.project.Project
import models.project.Project._
import models.user.{FakeUser, User}
import ore.permission.ResetOre
import ore.project.Categories
import ore.project.Categories.Category
import org.apache.commons.io.FileUtils
import play.api.Play.{configuration => config, current}
import play.api.i18n.MessagesApi
import play.api.libs.ws.WSClient
import play.api.mvc._
import util.P
import util.form.Forms
import util.forums.SpongeForums._
import views.{html => views}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Main entry point for application.
  */
class Application @Inject()(override val messagesApi: MessagesApi, ws: WSClient) extends BaseController(ws) {

  /**
    * Display the home page.
    *
    * @return Home page
    */
  def showHome(categories: Option[String]) = Action { implicit request =>
    var projectsFuture: Future[Seq[Project]] = null
    var categoryArray: Array[Category] = null
    categories match {
      case None => projectsFuture = Queries.Projects.collect(limit = InitialLoad)
      case Some(csv) =>
        categoryArray = Categories.fromString(csv)
        if (Categories.values.subsetOf(categoryArray.toSet) || categoryArray.isEmpty) {
          categoryArray = null
        }
        projectsFuture = Queries.Projects.collect(categoryArray, InitialLoad)
    }
    val projects = Queries.now(projectsFuture).get
    Ok(views.home(projects, Option(categoryArray)))
  }

  /**
    * Shows the User page for the user with the specified username.
    *
    * @param username   Username to lookup
    * @return           View of user page
    */
  def showUser(username: String) = Action { implicit request =>
    User.withName(username) match {
      case None => NotFound
      case Some(user) => Ok(views.user(user))
    }
  }

  /**
    * Submits a change to the specified user's tagline.
    *
    * @param username   User to update
    * @return           View of user page
    */
  def saveTagline(username: String) = Authenticated { implicit request =>
    val user = request.user
    val tagline = Forms.UserTagline.bindFromRequest.get.trim
    if (tagline.length > User.MaxTaglineLength) {
      Redirect(self.showUser(user.username)).flashing("error" -> "Tagline is too long.")
    } else {
      user.tagline = tagline
      Redirect(self.showUser(user.username))
    }
  }

  /**
    * Redirect to forums for SSO authentication and then back here again.
    *
    * @param sso  Incoming payload from forums
    * @param sig  Incoming signature from forums
    * @return     Logged in home
    */
  def logIn(sso: Option[String], sig: Option[String], returnPath: Option[String]) = Action { implicit request =>
    if (FakeUser.IsEnabled) {
      now(Queries.Users.getOrInsert(FakeUser))
      Redirect(self.showHome(None)).withSession(Security.username -> FakeUser.username)
    } else if (sso.isEmpty || sig.isEmpty) {
      Redirect(Auth.getRedirect(config.getString("application.baseUrl").get + "/login"))
        .flashing("url" -> returnPath.getOrElse(request.path))
    } else {
      val userData = Auth.authenticate(sso.get, sig.get)
      var user = new User(userData._1, userData._2, userData._3, userData._4)
      user = now(Queries.Users.getOrInsert(user)).get

      API.fetchRoles(user.username).andThen {
        case roles => if (!roles.equals(user.globalRoleTypes)) user.globalRoleTypes = roles.get
      }

      val baseUrl = config.getString("application.baseUrl").get
      Redirect(baseUrl + request2flash.get("url").get).withSession(Security.username -> user.username)
    }
  }

  /**
    * Clears the current session.
    *
    * @return Home page
    */
  def logOut = Action { implicit request =>
    Redirect(self.showHome(None)).withNewSession
  }

  /**
    * Helper route to reset Ore.
    *
    * TODO: REMOVE BEFORE PRODUCTION
    */
  def reset() = (Authenticated andThen PermissionAction[AuthRequest](ResetOre)) { implicit request =>
    val query: Query[UserTable, User, Seq] = Queries.Users.models
    now(Queries.DB.run(query.delete)).get
    FileUtils.deleteDirectory(P.UploadsDir.toFile)
    Redirect(self.showHome(None)).withNewSession
  }

}
