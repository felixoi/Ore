package util.form

/**
  * Concrete counterpart of [[TraitProjectRoleSetBuilder]].
  *
  * @param users Users for result set
  * @param roles Roles for result set
  */
case class ProjectRoleSetBuilder(override val users: List[Int],
                                 override val roles: List[String])
                                 extends TraitProjectRoleSetBuilder
