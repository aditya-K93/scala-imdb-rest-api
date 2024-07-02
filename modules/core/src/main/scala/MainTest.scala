import config.Config
import resources._
import cats.effect._
import cats.effect.std.Supervisor
import eu.timepit.refined.auto._
import modules.{ HttpApi, Services }
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object MainTest extends IOApp.Simple {

  implicit val logger = Slf4jLogger.getLogger[IO]

  override def run: IO[Unit] =
    Config.load[IO].flatMap { cfg =>
      Logger[IO].info(s"Loaded config $cfg") >>
        Supervisor[IO]
          .use { implicit sp =>
            AppResources
              .make[IO](cfg)
              .evalMap { res =>
                IO {
                  val services = Services.make[IO](res.postgres)
                  val api      = HttpApi.make[IO](services)
                  cfg.httpServerConfig -> api.httpApp
                }
              }
              .flatMap {
                case (cfg, httpApp) =>
                  MkHttpServer[IO].newEmber(cfg, httpApp)
              }
              .useForever
          }
    }
}
