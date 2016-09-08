package mesosphere.mesos

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.RunSpec
import org.apache.mesos.Protos.{ Attribute, Offer, Value }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.Try

object Int {
  def unapply(s: String): Option[Int] = Try(s.toInt).toOption
}

object Constraints {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)
  val GroupByDefault = 0

  private def getIntValue(s: String, default: Int): Int = s match {
    case "inf" => Integer.MAX_VALUE
    case Int(x) => x
    case _ => default
  }

  private def getValueString(attribute: Attribute): String = attribute.getType match {
    case Value.Type.SCALAR =>
      java.text.NumberFormat.getInstance.format(attribute.getScalar.getValue)
    case Value.Type.TEXT =>
      attribute.getText.getValue
    case Value.Type.RANGES =>
      val s = attribute.getRanges.getRangeList.asScala
        .sortWith(_.getBegin < _.getBegin)
        .map(r => s"${r.getBegin.toString}-${r.getEnd.toString}")
        .mkString(",")
      s"[$s]"
    case Value.Type.SET =>
      val s = attribute.getSet.getItemList.asScala.sorted.mkString(",")
      s"{$s}"
  }

  private final class ConstraintsChecker(tasks: Iterable[Task], offer: Offer, constraint: Constraint) {
    val field = constraint.getField
    val value = constraint.getValue
    lazy val attr = offer.getAttributesList.asScala.find(_.getName == field)

    def isMatch: Boolean =
      if (field == "hostname") {
        checkHostName
      } else if (attr.nonEmpty) {
        checkAttribute
      } else {
        // This will be reached in case we want to schedule for an attribute
        // that's not supplied.
        checkMissingAttribute
      }

    private def checkGroupBy(constraintValue: String, groupFunc: (Task) => Option[String]) = {
      // Minimum group count
      val minimum = List(GroupByDefault, getIntValue(value, GroupByDefault)).max
      // Group tasks by the constraint value, and calculate the task count of each group
      val groupedTasks = tasks.groupBy(groupFunc).mapValues(_.size)
      // Task count of the smallest group
      val minCount = groupedTasks.values.reduceOption(_ min _).getOrElse(0)

      // Return true if any of these are also true:
      // a) this offer matches the smallest grouping when there
      // are >= minimum groupings
      // b) the constraint value from the offer is not yet in the grouping
      groupedTasks.find(_._1.contains(constraintValue)) match {
        case Some(pair) => (groupedTasks.size >= minimum) && (pair._2 == minCount)
        case None => true
      }
    }

    private def checkMaxPer(constraintValue: String, maxCount: Int, groupFunc: (Task) => Option[String]) = {
      // Group tasks by the constraint value, and calculate the task count of each group
      val groupedTasks = tasks.groupBy(groupFunc).mapValues(_.size)

      groupedTasks.find(_._1.contains(constraintValue)) match {
        case Some(pair) => (pair._2 < maxCount)
        case None => true
      }
    }

    private def checkHostName =
      constraint.getOperator match {
        case Operator.LIKE => offer.getHostname.matches(value)
        case Operator.UNLIKE => !offer.getHostname.matches(value)
        // All running tasks must have a hostname that is different from the one in the offer
        case Operator.UNIQUE => tasks.forall(_.agentInfo.host != offer.getHostname)
        case Operator.GROUP_BY => checkGroupBy(offer.getHostname, (task: Task) => Some(task.agentInfo.host))
        case Operator.MAX_PER => checkMaxPer(offer.getHostname, value.toInt, (task: Task) => Some(task.agentInfo.host))
        case Operator.CLUSTER =>
          // Hostname must match or be empty
          (value.isEmpty || value == offer.getHostname) &&
            // All running tasks must have the same hostname as the one in the offer
            tasks.forall(_.agentInfo.host == offer.getHostname)
        case _ => false
      }

    private def checkAttribute = {
      def matches: Iterable[Task] = matchTaskAttributes(tasks, field, getValueString(attr.get))
      def groupFunc = (task: Task) => task.agentInfo.attributes
        .find(_.getName == field)
        .map(getValueString(_))
      constraint.getOperator match {
        case Operator.UNIQUE => matches.isEmpty
        case Operator.CLUSTER =>
          // If no value is set, accept the first one. Otherwise check for it.
          (value.isEmpty || getValueString(attr.get) == value) &&
            // All running tasks should have the matching attribute
            matches.size == tasks.size
        case Operator.GROUP_BY =>
          checkGroupBy(getValueString(attr.get), groupFunc)
        case Operator.MAX_PER =>
          checkMaxPer(offer.getHostname, value.toInt, groupFunc)
        case Operator.LIKE => checkLike
        case Operator.UNLIKE => checkUnlike
      }
    }

    private def checkLike: Boolean = {
      if (value.nonEmpty) {
        getValueString(attr.get).matches(value)
      } else {
        log.warn(s"Error, value is required for LIKE operation")
        false
      }
    }

    private def checkUnlike: Boolean = {
      if (value.nonEmpty) {
        !getValueString(attr.get).matches(value)
      } else {
        log.warn(s"Error, value is required for UNLIKE operation")
        false
      }
    }

    private def checkMissingAttribute = constraint.getOperator == Operator.UNLIKE

    /**
      * Filters running tasks by matching their attributes to this field & value.
      */
    private def matchTaskAttributes(tasks: Iterable[Task], field: String, value: String) =
      tasks.filter {
        _.agentInfo.attributes
          .filter { y =>
            y.getName == field &&
              getValueString(y) == value
          }.nonEmpty
      }
  }

  def meetsConstraint(tasks: Iterable[Task], offer: Offer, constraint: Constraint): Boolean =
    new ConstraintsChecker(tasks, offer, constraint).isMatch

  /**
    * Select tasks to kill while maintaining the constraints of the application definition.
    * Note: It is possible, that the result of this operation does not select as much tasks as needed.
    *
    * @param runSpec the RunSpec.
    * @param runningInstances the list of running instances to filter
    * @param toKillCount the expected number of instances to select for kill
    * @return the selected instances to kill. The number of instances will not exceed toKill but can be less.
    */
  //scalastyle:off return
  def selectTasksToKill(
    runSpec: RunSpec, runningInstances: Iterable[Instance], toKillCount: Int): Iterable[Instance] = {

    require(toKillCount <= runningInstances.size, "Can not kill more instances than running")

    //short circuit, if all tasks shall be killed
    if (runningInstances.size == toKillCount) return runningInstances

    //currently, only the GROUP_BY operator is able to select tasks to kill
    val distributions = runSpec.constraints.filter(_.getOperator == Operator.GROUP_BY).map { constraint =>
      def groupFn(task: Instance): Option[String] = constraint.getField match {
        case "hostname" => Some(task.agentInfo.host)
        case field: String => task.agentInfo.attributes.find(_.getName == field).map(getValueString(_))
      }
      val taskGroups: Seq[Map[Instance.Id, Instance]] =
        runningInstances.groupBy(groupFn).values.map(Instance.instancesById).toSeq
      GroupByDistribution(constraint, taskGroups)
    }

    //short circuit, if there are no constraints to align with
    if (distributions.isEmpty) return Set.empty

    var toKillTasks = Map.empty[Instance.Id, Instance]
    var flag = true
    while (flag && toKillTasks.size != toKillCount) {
      val tried = distributions
        //sort all distributions in descending order based on distribution difference
        .toSeq.sortBy(_.distributionDifference(toKillTasks)).reverseIterator
        //select tasks to kill (without already selected ones)
        .flatMap(_.tasksToKillIterator(toKillTasks)) ++
        //fallback: if the distributions did not select a task, choose one of the not chosen ones
        runningInstances.iterator.filterNot(task => toKillTasks.contains(task.id))

      val matchingTask =
        tried.find(tryTask => distributions.forall(_.isMoreEvenWithout(toKillTasks + (tryTask.id -> tryTask))))

      matchingTask match {
        case Some(task) => toKillTasks += task.id -> task
        case None => flag = false
      }
    }

    //log the selected tasks and why they were selected
    if (log.isInfoEnabled) {
      val taskDesc = toKillTasks.values.map { task =>
        val attrs = task.agentInfo.attributes.map(a => s"${a.getName}=${getValueString(a)}").mkString(", ")
        s"${task.id} host:${task.agentInfo.host} attrs:$attrs"
      }.mkString("Selected Tasks to kill:\n", "\n", "\n")
      val distDesc = distributions.map { d =>
        val (before, after) = (d.distributionDifference(), d.distributionDifference(toKillTasks))
        s"${d.constraint.getField} changed from: $before to $after"
      }.mkString("Selected Constraint diff changed:\n", "\n", "\n")
      log.info(s"$taskDesc$distDesc")
    }

    toKillTasks.values
  }

  /**
    * Helper class for easier distribution computation.
    */
  private case class GroupByDistribution(constraint: Constraint, distribution: Seq[Map[Instance.Id, Instance]]) {
    def isMoreEvenWithout(selected: Map[Instance.Id, Instance]): Boolean = {
      val diffAfterKill = distributionDifference(selected)
      //diff after kill is 0=perfect, 1=tolerated or minimizes the difference
      diffAfterKill <= 1 || distributionDifference() > diffAfterKill
    }

    def tasksToKillIterator(without: Map[Instance.Id, Instance]): Iterator[Instance] = {
      val updated = distribution.map(_ -- without.keys).groupBy(_.size)
      if (updated.size == 1) /* even distributed */ Iterator.empty else {
        updated.maxBy(_._1)._2.iterator.flatten.map { case (taskId, task) => task }
      }
    }

    def distributionDifference(without: Map[Instance.Id, Instance] = Map.empty): Int = {
      val updated = distribution.map(_ -- without.keys).groupBy(_.size).keySet
      updated.max - updated.min
    }
  }
}