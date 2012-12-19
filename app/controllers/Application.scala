package controllers

import play.api._
import play.api.mvc._


import java.io.{InputStream, OutputStream}
import scala.sys.process.{ Process, ProcessIO }
import play.api.libs.iteratee._

case class CLI (cmd: String) {

  def enumerate: Enumerator[Array[Byte]] = 
    Enumerator.flatten[Array[Byte]] {
      import scala.concurrent.ExecutionContext.Implicits.global
      val process = Process(cmd)
      val promiseOfInputStream = concurrent.promise[InputStream]()
      try {
        process run new ProcessIO(
          (stdin: OutputStream) => (), 
          (stdout: InputStream) => promiseOfInputStream.success(stdout),
          (stderr: InputStream) => ()
        );
      } catch {
        case e: Throwable => promiseOfInputStream.failure(e)
      }

      promiseOfInputStream.future.map { cmdout =>
        Enumerator.fromStream(cmdout)
      }
    }
  //def consume: Iteratee[Array[Byte]] = 
  //def pipe: Enumeratee[Array[Byte], Array[Byte]] = 
}

object Application extends Controller {
  
  def index = Action {
    Ok.stream(CLI("ls").enumerate >>> Enumerator.eof)
  }
  
}
