package mesosphere.marathon
package stream

import akka.Done
import akka.stream.scaladsl.{Flow, Keep, Sink}

import scala.collection.immutable
import scala.concurrent.Future

/**
  * Extensions to Akka's Sink companion
  */
object EnrichedSink {
  def set[T]: Sink[T, Future[immutable.Set[T]]] = {
    Sink.fromGraph(new CollectionStage[T, immutable.Set[T]](immutable.Set.newBuilder[T]))
  }

  def sortedSet[T](implicit ordering: Ordering[T]): Sink[T, Future[immutable.SortedSet[T]]] = {
    Sink.fromGraph(new CollectionStage[T, immutable.SortedSet[T]](immutable.SortedSet.newBuilder[T]))
  }

  def map[K, V]: Sink[(K, V), Future[immutable.Map[K, V]]] = {
    Sink.fromGraph(new CollectionStage[(K, V), immutable.Map[K, V]](immutable.Map.newBuilder[K, V]))
  }

  def list[T]: Sink[T, Future[List[T]]] = {
    Sink.fromGraph(new CollectionStage[T, List[T]](List.newBuilder[T]))
  }

  def liveFold[T, U](zero: U)(fold: (U, T) => U): Sink[T, LiveFold.Folder[U]] =
    Sink.fromGraph(new LiveFold(zero)(fold))

  def statefulForeach[T](constructor: () => T => Unit): Sink[T, Future[Done]] = {
    Flow[T]
      .statefulMapConcat({ () =>
        val fn = constructor()

        { t =>
          fn(t)
          Nil
        }
      })
      .toMat(Sink.ignore)(Keep.right)
  }
}
