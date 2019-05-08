package db.impl.query

import java.time.Instant

import db.impl.access.UserBase.UserOrdering
import models.querymodels.ProjectListEntry
import ore.OreConfig
import ore.db.DbRef
import ore.models.project.ProjectSortingStrategy
import ore.models.user.User
import ore.permission.role.Role

import doobie._
import doobie.implicits._

object UserPagesQueries extends WebDoobieOreProtocol {

  def getProjects(
      username: String,
      currentUserId: Option[DbRef[User]],
      canSeeHidden: Boolean,
      order: ProjectSortingStrategy,
      pageSize: Long,
      offset: Long
  ): Query0[ProjectListEntry] = {
    val visibilityFrag =
      if (canSeeHidden) None
      else
        currentUserId.fold(Some(fr"(p.visibility = 1 OR p.visibility = 2)")) { id =>
          Some(fr"(p.visibility = 1 OR p.visibility = 2 OR (p.owner_id = $id AND p.visibility != 5))")
        }

    val fragments =
      sql"""|SELECT p.owner_name,
            |       p.slug,
            |       p.visibility,
            |       p.views,
            |       p.downloads,
            |       p.stars,
            |       p.category,
            |       p.description,
            |       p.name,
            |       p.version_string,
            |       array_remove(array_agg(p.tag_name), NULL)  AS tag_names,
            |       array_remove(array_agg(p.tag_data), NULL)  AS tag_datas,
            |       array_remove(array_agg(p.tag_color), NULL) AS tag_colors
            |  FROM home_projects p
            |  WHERE p.owner_name = $username """.stripMargin ++ visibilityFrag.fold(fr0"")(frag => fr"AND" ++ frag) ++
        fr"""|GROUP BY (p.owner_name,
             |          p.slug,
             |          p.visibility,
             |          p.views,
             |          p.downloads,
             |          p.stars,
             |          p.category,
             |          p.description,
             |          p.name,
             |          p.created_at,
             |          p.last_updated,
             |          p.version_string,
             |          p.search_words)""".stripMargin ++
        fr"ORDER BY" ++ order.fragment ++
        fr"LIMIT $pageSize OFFSET $offset"

    fragments.query[ProjectListEntry]
  }

  private def userFragOrder(reverse: Boolean, sortStr: String) = {
    val sort = if (reverse) fr"ASC" else fr"DESC"

    val sortUserName     = fr"sq.name" ++ sort
    val thenSortUserName = fr"," ++ sortUserName

    sortStr match {
      case UserOrdering.JoinDate => fr"ORDER BY sq.join_date" ++ sort
      case UserOrdering.UserName => fr"ORDER BY" ++ sortUserName
      case UserOrdering.Projects => fr"ORDER BY sq.count" ++ sort ++ thenSortUserName
      case UserOrdering.Role =>
        fr"ORDER BY sq.permission::BIGINT" ++ sort ++ fr"NULLS LAST" ++ fr", sq.role" ++ sort ++ thenSortUserName
    }
  }

  def getAuthors(page: Int, ordering: String)(
      implicit config: OreConfig
  ): Query0[(String, Option[Instant], Instant, Option[Role], Option[Role], Long)] = {
    val (sort, reverse) = if (ordering.startsWith("-")) (ordering.substring(1), false) else (ordering, true)
    val pageSize        = config.ore.users.authorPageSize
    val offset          = (page - 1) * pageSize

    val fragments =
      sql"""|SELECT sq.name,
            |       sq.join_date,
            |       sq.created_at,
            |       sq.role,
            |       sq.donator_role,
            |       sq.count
            |  FROM (SELECT u.name,
            |               u.join_date,
            |               u.created_at,
            |               r.name                                                      AS role,
            |               r.permission,
            |               (SELECT COUNT(*) FROM projects WHERE owner_id = u.id)       AS count,
            |               CASE WHEN dr.rank IS NULL THEN NULL ELSE dr.name END        AS donator_role,
            |               row_number() OVER (PARTITION BY u.id ORDER BY r.permission::BIGINT DESC, dr.rank ASC NULLS LAST) AS row
            |          FROM projects p
            |                 JOIN users u ON p.owner_id = u.id
            |                 LEFT JOIN user_global_roles gr ON gr.user_id = u.id
            |                 LEFT JOIN roles r ON gr.role_id = r.id
            |                 LEFT JOIN user_global_roles dgr on dgr.user_id = u.id
            |                 LEFT JOIN roles dr ON dgr.role_id = dr.id) sq
            |  WHERE sq.row = 1 """.stripMargin ++
        userFragOrder(reverse, sort) ++
        fr"""OFFSET $offset LIMIT $pageSize"""

    fragments.query[(String, Option[Instant], Instant, Option[Role], Option[Role], Long)]
  }

  def getStaff(page: Int, ordering: String)(
      implicit config: OreConfig
  ): Query0[(String, Role, Option[Instant], Instant)] = {
    val (sort, reverse) = if (ordering.startsWith("-")) (ordering.substring(1), false) else (ordering, true)
    val pageSize        = config.ore.users.authorPageSize
    val offset          = (page - 1) * pageSize

    val fragments =
      sql"""|SELECT sq.name, sq.role, sq.join_date, sq.created_at
            |  FROM (SELECT u.name                                                  AS name,
            |               r.name                                                  AS role,
            |               u.join_date,
            |               u.created_at,
            |               r.permission,
            |               rank() OVER (PARTITION BY u.name ORDER BY r.permission::BIGINT DESC) AS rank
            |          FROM users u
            |                 JOIN user_global_roles ugr ON u.id = ugr.user_id
            |                 JOIN roles r ON ugr.role_id = r.id
            |          WHERE r.name IN ('Ore_Admin', 'Ore_Mod')) sq
            |  WHERE sq.rank = 1 """.stripMargin ++
        userFragOrder(reverse, sort) ++
        fr"""OFFSET $offset LIMIT $pageSize"""

    fragments.query[(String, Role, Option[Instant], Instant)]
  }
}
