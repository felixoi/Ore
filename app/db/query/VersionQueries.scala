package db.query

import java.sql.Timestamp

import db.OrePostgresDriver.api._
import db.query.Queries.DB._
import db.{ChannelTable, VersionDownloadsTable, VersionTable}
import models.project.Version

import scala.concurrent.Future

/**
  * Version related queries.
  */
object VersionQueries extends Queries[VersionTable, Version](TableQuery(tag => new VersionTable(tag))) {

  private val downloads = TableQuery[VersionDownloadsTable]

  /**
    * Returns all Versions in the specified Project.
    *
    * @param projectId  Project ID
    * @return           Versions in project
    */
  def inProject(projectId: Int): Future[Seq[Version]] = {
    run(this.models.filter(v => v.projectId === projectId).result)
  }

  /**
    * Returns all Versions in the specified Channel
    *
    * @param channelId  Channel ID
    * @return           Versions in channel
    */
  def inChannel(channelId: Int): Future[Seq[Version]] = {
    run(this.models.filter(v => v.channelId === channelId).result)
  }

  /**
    * Returns all Versions in the specified seq of channels.
    *
    * @param channelIds   Channel IDs
    * @return             Versions in the Channel ID seq
    */
  def inChannels(channelIds: Seq[Int]): Future[Seq[Version]] = {
    val query = for {
      version <- this.models
      if version.channelId inSetBind channelIds
    } yield version
    run(query.result)
  }

  /**
    * Returns the Version with the specified name in the specified Channel.
    *
    * @param channelId      Channel version is in
    * @param versionString  Version to search for
    * @return               Version with name
    */
  def withName(channelId: Int, versionString: String): Future[Option[Version]] = {
    find(v => v.channelId === channelId && v.versionString === versionString)
  }

  /**
    * Returns the Version with the specified ID.
    *
    * @param id   Version ID
    * @return     Version with ID
    */
  def withId(id: Int): Future[Option[Version]] = find(v => v.id === id)

  /**
    * Returns true if the specified Version has been downloaded by a client
    * with the specified cookie.
    *
    * @param versionId  Version to check
    * @param cookie     Cookie to look for
    * @return           True if downloaded
    */
  def hasBeenDownloadedBy(versionId: Int, cookie: String): Future[Boolean] = {
    val query = this.downloads.filter(vd => vd.versionId === versionId && vd.cookie === cookie).size > 0
    run(query.result)
  }

  /**
    * Sets the specified Version as downloaded by the client with the specified
    * cookie.
    *
    * @param versionId  Version to set as downloaded
    * @param cookie     To set as downloaded for
    */
  def setDownloadedBy(versionId: Int, cookie: String) = {
    val query = this.downloads += (None, Some(cookie), None, versionId)
    run(query)
  }

  /**
    * Returns true if the specified Version has been downloaded by the
    * specified User.
    *
    * @param versionId  Version to check
    * @param userId     User to look for
    * @return           True if downloaded
    */
  def hasBeenDownloadedBy(versionId: Int, userId: Int): Future[Boolean] = {
    val query = this.downloads.filter(vd => vd.versionId === versionId && vd.userId === userId).size > 0
    run(query.result)
  }

  /**
    * Sets the specified Version as downloaded by the specified User.
    *
    * @param versionId  Version to set as downloaded
    * @param userId     To set as downloaded for
    */
  def setDownloadedBy(versionId: Int, userId: Int) = {
    val query = this.downloads += (None, None, Some(userId), versionId)
    run(query)
  }

  override def copyInto(id: Option[Int], theTime: Option[Timestamp], version: Version): Version = {
    version.copy(id = id, createdAt = theTime)
  }

}