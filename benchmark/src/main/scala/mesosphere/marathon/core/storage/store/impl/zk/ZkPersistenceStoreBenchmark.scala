package mesosphere.marathon
package core.storage.store.impl.zk

import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.{ActorSystem, Scheduler}
import akka.stream.{ActorMaterializer, Materializer}
import mesosphere.marathon.core.base.{JvmExitsCrashStrategy, LifecycleState}
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition}
import mesosphere.marathon.storage.repository.StoredGroup
import mesosphere.marathon.storage.store.ZkStoreSerialization
import mesosphere.marathon.storage.{CuratorZk, StorageConf, StorageConfig}
import mesosphere.marathon.upgrade.DependencyGraphBenchmark
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.async.Async._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

object ZkPersistenceStoreBenchmark {

  implicit lazy val system: ActorSystem = ActorSystem()
  implicit lazy val scheduler: Scheduler = system.scheduler
  implicit lazy val mat: Materializer = ActorMaterializer()

  object Conf extends StorageConf with NetworkConf {
    override def availableFeatures: Set[String] = Set.empty
  }
  Conf.verify()
  val curatorFramework: RichCuratorFramework = StorageConfig.curatorFramework(Conf, JvmExitsCrashStrategy, LifecycleState.WatchingJVM)
  val lifecycleState = LifecycleState.WatchingJVM
  val curator = CuratorZk(Conf, curatorFramework)
  val metrics = DummyMetrics
  val zkStore = curator.leafStore(metrics)

  val rootGroup = DependencyGraphBenchmark.rootGroup
  val storedGroup = StoredGroup.apply(rootGroup)
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class ZkPersistenceStoreBenchmark {
  import ZkPersistenceStoreBenchmark._
  import ZkStoreSerialization._

  @Benchmark
  def storeAndRemoveGroup(hole: Blackhole): Unit = {
    val done = Promise[Done]
    val pipeline: Future[Done] = async {
      await(zkStore.store[AbsolutePathId, StoredGroup](storedGroup.id, storedGroup))
      val delete = Future.sequence(rootGroup.groupsById.keys.map { id =>
        zkStore.deleteAll[AbsolutePathId, AppDefinition](id)(appDefResolver)
      })
      await(delete)
      Done
    }
    done.completeWith(pipeline)

    // Poll until we are done
    while (!done.isCompleted) { Thread.sleep(100) }
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    println("Shutting down...")
    curator.client.close()
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }
}
