package com.softwaremill.realworld.utils

import com.softwaremill.realworld.GlobalDefectHandler
import com.softwaremill.realworld.db.{Db, DbConfig, DbMigrator}
import io.getquill.{SnakeCase, SqliteZioJdbcContext}
import sttp.client3.testing.SttpBackendStub
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.server.ziohttp.ZioHttpServerOptions
import sttp.tapir.ztapir.RIOMonadError
import zio.test.TestRandom
import zio.{RIO, Random, Task, UIO, ZIO, ZLayer}

import java.nio.file.{Files, Path, Paths}
import java.sql.{Connection, Statement}
import scala.io.Source
import scala.util.{Try, Using}
import javax.sql.DataSource

object TestUtils:

  val interceptors = ZioHttpServerOptions.customiseInterceptors.exceptionHandler(new GlobalDefectHandler())
  def zioTapirStubInterpreter =
    TapirStubInterpreter(
      ZioHttpServerOptions.customiseInterceptors.exceptionHandler(new GlobalDefectHandler()),
      SttpBackendStub(new RIOMonadError[Any])
    )

  type TestDbLayer = DbConfig with DataSource with DbMigrator with SqliteZioJdbcContext[SnakeCase]

  def withEmptyDb(): RIO[TestDbLayer, Any] = for {
    migrator <- ZIO.service[DbMigrator]
    _ <- migrator.migrate()
  } yield ()

  def withFixture(fixturePath: String): RIO[TestDbLayer, Any] = for {
    migrator <- ZIO.service[DbMigrator]
    _ <- migrator.migrate()
    _ <- loadFixture(fixturePath)
  } yield ()

  def clearDb: RIO[TestDbLayer, Any] = for {
    cfg <- ZIO.service[DbConfig]
  } yield {
    Files.deleteIfExists(Paths.get(cfg.dbPath))
  }

  private def loadFixture(fixturePath: String): RIO[DataSource, Unit] = for {
    ds <- ZIO.service[DataSource]
  } yield {
    val queries = Source
      .fromResource(fixturePath)
      .mkString
      .split(";")
      .map(_.strip())
      .filter(_.nonEmpty)
    Using.Manager { use =>
      val conn: Connection = use(ds.getConnection)
      val st: Statement = use(conn.createStatement())
      queries.foreach(st.execute)
    }
  }

  private def createTestDbConfig(): ZIO[Random, Nothing, DbConfig] = for {
    _ <- TestRandom.setSeed(System.nanoTime())
    r <- ZIO.random
    uuid <- r.nextUUID
  } yield DbConfig(s"/tmp/realworld-test-$uuid.sqlite")

  private val testDbConfigLive: ZLayer[Any, Nothing, DbConfig] = ZLayer.fromZIO(createTestDbConfig().provide(ZLayer.fromZIO(ZIO.random)))

  val testDbConfigLayer: ZLayer[Any, Nothing, TestDbLayer] =
    (testDbConfigLive >+> Db.dataSourceLive >+> DbMigrator.live)
      ++ Db.quillLive