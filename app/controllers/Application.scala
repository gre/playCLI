package controllers

import play.api._
import play.api.mvc._

import java.io._
import scala.sys.process.{ Process, ProcessIO }
import play.api.libs.iteratee._

case class CLI (cmd: String) {

  val logger = play.api.Logger("CLI")

  /**
   * Get an [[play.api.libs.iteratee.Enumerator]] from a CLI output.
   * (nothing is sent to the CLI input)
   *
   * @example {{{
   * CLI("find .").enumerate
   * }}}
   */
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

  /**
   * Get an [[play.api.libs.iteratee.Enumeratee]] from the CLI piping.
   *
   * @example {{{
     // Add an echo to an ogg audio stream.
     oggStream &> CLI("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
   * }}}
   */
  //def pipe: Enumeratee[Array[Byte], Array[Byte]] = 

  /**
   * Use for CLI consuming some data and generating a side effect
   */
  def consume: Iteratee[Array[Byte], Array[Byte]] = {
    Iteratee.consume[Array[Byte]]() // FIXME TODO
  }
}

import play.api.Play.current

object Application extends Controller {

  def index = find

  // consume a ogg sound, add an echo effect and store in a /tmp/out.ogg file
  def audioEchoEffectGenerate = Action {
    val consumer = CLI("sox -t ogg - /tmp/out.ogg echo 0.5 0.7 60 1").consume
    Enumerator.fromFile(Play.getFile("conf/exemple.ogg"))(consumer)
    Ok("'/tmp/out.ogg' file has been generated.")
  }

  /*
  def audioEchoEffect = Action {
    CLI("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
  }
  */

  // List all files in this Play project
  def find = Action {
    Ok.stream(CLI("find .").enumerate >>> Enumerator.eof).withHeaders(
      CONTENT_TYPE -> "text/plain"
    )
  }
  
  // Retrieve a webpage and display it (of course, this is just for the demo, I won't use curl, prefer using WS)
  def curl = Action {
    Ok.stream(CLI("curl http://blog.greweb.fr/").enumerate >>> Enumerator.eof).withHeaders(
      CONTENT_TYPE -> "text/html"
    )
  }
  
}
