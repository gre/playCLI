package controllers

import play.api._
import play.api.mvc._


import java.io._
import scala.sys.process.{ Process, ProcessIO }
import play.api.libs.iteratee._

case class CLI (cmd: String) {

  val logger = play.api.Logger("CLI")

  def enumerate: Enumerator[Array[Byte]] = 
    Enumerator.flatten[Array[Byte]] {
      import scala.concurrent.ExecutionContext.Implicits.global
      val process = Process(cmd)
      val promiseOfInputStream = concurrent.promise[InputStream]()
      process run new ProcessIO(
        _.close(),
        (stdout: InputStream) => promiseOfInputStream.success(stdout),
        (stderr: InputStream) => {
          val br = new java.io.BufferedReader(new InputStreamReader(stderr))
          var read = br.readLine()
          while(read != null) {
            logger.warn(read)
            read = br.readLine()
          }
          stderr.close()
        }
      );
      promiseOfInputStream.future.map { cmdout =>
        Enumerator.fromStream(cmdout)
      }
    }
  //def consume: Iteratee[Array[Byte]] = 
  //def pipe: Enumeratee[Array[Byte], Array[Byte]] = 
}

object Application extends Controller {

  def index = find
  
  def find = Action {
    Ok.stream(CLI("find .").enumerate >>> Enumerator.eof).withHeaders(
      CONTENT_TYPE -> "text/plain"
    )
  }
  
  def curl = Action {
    Ok.stream(CLI("curl http://blog.greweb.fr/").enumerate >>> Enumerator.eof).withHeaders(
      CONTENT_TYPE -> "text/html"
    )
  }
  
}
