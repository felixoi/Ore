package controllers

import javax.inject.Inject

import _root_.ore.Categories
import controllers.routes.{Application => self}
import db.query.Queries
import db.query.Queries.now
import models.project.Project
import models.project.Project._
import models.user.{FakeUser, User}
import ore.Categories.Category
import play.api.i18n.MessagesApi
import play.api.mvc._
import util.DiscourseSSO._
import util.Forms
import views.{html => views}

import scala.concurrent.Future

/**
  * Main entry point for application.
  */
class Application @Inject()(override val messagesApi: MessagesApi) extends BaseController {

  /**
    * Display the home page.
    *
    * @return Home page
    */
  def showHome(categories: Option[String]) = Action { implicit request =>
    var projectsFuture: Future[Seq[Project]] = null
    var categoryArray: Array[Category] = null
    categories match {
      case None => projectsFuture = Queries.Projects.collect(limit = INITIAL_LOAD)
      case Some(csv) =>
        categoryArray = Categories.fromString(csv)
        var categoryIds = categoryArray.map(_.id)
        if (Categories.values.subsetOf(categoryArray.toSet) || categoryArray.isEmpty) {
          categoryArray = null
          categoryIds = null
        }
        projectsFuture = Queries.Projects.collect(categoryIds, INITIAL_LOAD)
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
  def saveTagline(username: String) = withUser(Some(username), user => implicit request => {
    val tagline = Forms.UserTagline.bindFromRequest.get.trim
    if (tagline.length > User.MAX_TAGLINE_LENGTH) {
      Redirect(self.showUser(user.username)).flashing("error" -> "Tagline is too long.")
    } else {
      if (user.tagline.isEmpty || !user.tagline.get.equals(tagline)) {
        user.tagline = tagline
      }
      Redirect(self.showUser(user.username))
    }
  })

  /**
    * Redirect to forums for SSO authentication and then back here again.
    *
    * @param sso  Incoming payload from forums
    * @param sig  Incoming signature from forums
    * @return     Logged in home
    */
  def logIn(sso: Option[String], sig: Option[String]) = Action {
    if (FakeUser.ENABLED) {
      now(Queries.Users.getOrCreate(FakeUser))
      Redirect(self.showHome(None)).withSession(Security.username -> FakeUser.username, "email" -> FakeUser.email)
    } else if (sso.isEmpty || sig.isEmpty) {
      Redirect(getRedirect)
    } else {
      val userData = authenticate(sso.get, sig.get)
      var user = new User(userData._1, userData._2, userData._3, userData._4)
      user = now(Queries.Users.getOrCreate(user)).get
      Redirect(self.showHome(None)).withSession(Security.username -> user.username, "email" -> user.email)
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

}
