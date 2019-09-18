package com.ovotech.micrometer

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.concurrent.Semaphore
import cats.effect.implicits._
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import com.ovotech.micrometer.Reporter._
import io.micrometer.core.instrument.{MeterRegistry, Tags}
import io.micrometer.core.{instrument => micrometer}

import scala.collection.mutable
import scala.concurrent.duration._

trait Reporter[F[_]] {
  def counter(name: String): F[Counter[F]] = counter(name, Tags.empty)
  def counter(name: String, tags: Tags): F[Counter[F]]
  def counter(name: String, tags: Map[String, String]): F[Counter[F]] =
    counter(name, tags.toTags)

  def timer(name: String): F[Timer[F]] = timer(name, Tags.empty)
  def timer(name: String, tags: Tags): F[Timer[F]]
  def timer(name: String, tags: Map[String, String]): F[Timer[F]] =
    timer(name, tags.toTags)

  def gauge(name: String): F[Gauge[F]] = gauge(name, Tags.empty)
  def gauge(name: String, tags: Tags): F[Gauge[F]]
  def gauge(name: String, tags: Map[String, String]): F[Gauge[F]] =
    gauge(name, tags.toTags)
}

object Reporter {
  trait Counter[F[_]] {
    def increment: F[Unit] = incrementN(1)
    def incrementN(n: Int): F[Unit]
  }

  trait Timer[F[_]] {
    def record(d: FiniteDuration): F[Unit]
  }

  trait Gauge[F[_]] {
    def increment: F[Unit] = incrementN(1)
    def incrementN(n: Int): F[Unit]

    def decrement: F[Unit] = incrementN(-1)
    def decrementN(n: Int): F[Unit] = incrementN(-n)

    /** Run `action` with the gauge incremented before execution and decremented after termination (including error or cancelation) */
    def surround[A](action: F[A]): F[A]
  }

  def fromRegistry[F[_]](
      mx: MeterRegistry,
      metricPrefix: String = "",
      globalTags: Tags = Tags.empty
  )(
      implicit F: Concurrent[F]
  ): F[Reporter[F]] =
    for {
      sem <- Semaphore[F](1)
    } yield new ReporterImpl[F](mx, metricPrefix, globalTags, mutable.Map.empty, sem)

  private class ReporterImpl[F[_]](
      mx: MeterRegistry,
      metricPrefix: String,
      globalTags: Tags,
      activeGauges: mutable.Map[String, AtomicInteger],
      gaugeSem: Semaphore[F]
  )(
      implicit F: Sync[F]
  ) extends Reporter[F] {
    // local tags overwrite global tags
    def effectiveTags(tags: Tags) = globalTags and tags

    def counter(name: String, tags: Tags): F[Counter[F]] =
      F.delay {
          micrometer.Counter
            .builder(s"${metricPrefix}${name}")
            .tags(effectiveTags(tags))
            .register(mx)
        }
        .map { c =>
          new Counter[F] {
            def incrementN(n: Int) =
              F.delay(require(n >= 0)) *> F.delay(c.increment(n.toDouble))
          }
        }

    def timer(name: String, tags: Tags): F[Timer[F]] =
      F.delay {
          micrometer.Timer
            .builder(s"${metricPrefix}${name}")
            .tags(effectiveTags(tags))
            .register(mx)
        }
        .map { t =>
          new Timer[F] {
            def record(d: FiniteDuration) = F.delay(t.record(d.toNanos, NANOSECONDS))
          }
        }

    def gauge(name: String, tags: Tags): F[Gauge[F]] = {
      val pname = s"${metricPrefix}${name}"
      val create = for {
        created <- F.delay(new AtomicInteger(0))
        gauge <- F.delay(
          micrometer.Gauge
            .builder(
              pname,
              created, { x: AtomicInteger =>
                x.doubleValue
              }
            )
            .tags(effectiveTags(tags))
            .register(mx)
        )

      } yield created

      gaugeSem.withPermit {
        activeGauges
          .get(pname)
          .fold {
            create.flatTap(x => F.delay(activeGauges.put(pname, x)))
          }(_.pure[F])
          .map { g =>
            new Gauge[F] {
              def incrementN(n: Int): F[Unit] =
                F.delay(g.getAndAdd(n)).void

              def surround[A](action: F[A]): F[A] =
                increment.bracket(_ => action)(_ => decrement)
            }
          }
      }
    }
  }
}