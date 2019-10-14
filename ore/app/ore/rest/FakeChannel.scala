package ore.rest

import ore.models.project.{TagColor, VersionTag}

case class FakeChannel(
    name: String,
    color: TagColor,
    isNonReviewed: Boolean
)
object FakeChannel {

  def fromVersionTag(tag: VersionTag) = FakeChannel(tag.data.get.capitalize, tag.color, tag.name == ???)
}
