package mesosphere.marathon.state

trait Timestamped { val version: Timestamp }

object Timestamped {

  /**
   * Returns an ordering on type `T` derived from the natural ordering of
   * the `T`'s versions.
   */
  def timestampOrdering[T <: Timestamped](): Ordering[T] =
    Ordering.by { (item: T) => item.version }
}