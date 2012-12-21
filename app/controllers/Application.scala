package controllers

import play.api._
import play.api.mvc._

import java.io._
import scala.sys.process.{ ProcessIO, ProcessBuilder }
import play.api.libs.iteratee._

object CLI {

  val logger = play.api.Logger("CLI")

  /*
  def process = {
    Process(cmd)
  }
  */

  def logstderr (stderr: InputStream) = {
    val br = new java.io.BufferedReader(new InputStreamReader(stderr))
    var read = br.readLine()
    while(read != null) {
      logger.warn(read)
      read = br.readLine()
    }
    stderr.close()
  }

  /**
   * Get an [[play.api.libs.iteratee.Enumerator]] from a CLI output.
   * (nothing is sent to the CLI input)
   *
   * @example {{{
   CLI.enumerate("find .")
   * }}}
   */
  def enumerate (process: ProcessBuilder): Enumerator[Array[Byte]] = 
    Enumerator.flatten[Array[Byte]] {
      import scala.concurrent.ExecutionContext.Implicits.global
      val promiseOfInputStream = concurrent.promise[InputStream]()
      process run new ProcessIO(
        _.close(),
        (stdout: InputStream) => promiseOfInputStream.success(stdout),
        logstderr(_)
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
     oggStream &> CLI.pipe("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
   * }}}
   */
  def pipe (process: ProcessBuilder): Enumeratee[Array[Byte], Array[Byte]] = {
    Enumeratee.map[Array[Byte]] { bytes => bytes } // FIXME
    /*
    import scala.concurrent.ExecutionContext.Implicits.global
    val promiseOfOutputStream = concurrent.promise[OutputStream]()
    val promiseOfInputStream = concurrent.promise[InputStream]()
    process run new ProcessIO(
      (stdin: OutputStream) => promiseOfOutputStream.success(stdin),
      (stdout: InputStream) => promiseOfInputStream.success(stdout),
      logstderr(_)
    );
    
    val promiseCmdin = promiseOfOutputStream.future.map { cmdin =>
      Iteratee.foreach[Array[Byte]] { bytes =>
        cmdin.write(bytes)
      }
    }
    val promiseCmdout = promiseOfInputStream.future.map { cmdout =>
      Enumerator.fromStream(cmdout)
    }

    import scala.concurrent.duration._
    val cmdin = promiseCmdin.result(1 second) // FIXME
    Enumeratee.grouped[Array[Byte]](cmdin)
    */
  }

  /**
   * Get an [[play.api.libs.iteratee.Iteratee]] consuming data 
   * to push in the CLI (regardless of the CLI output).
   *
   * @example {{{
     val consumer = CLI.consume("aSideEffectCommand")
     anEnumerator(consumer)
   * }}}
   */
  def consume (process: ProcessBuilder): Iteratee[Array[Byte], Unit] = {
    Iteratee.flatten[Array[Byte], Unit] {
      import scala.concurrent.ExecutionContext.Implicits.global
      val promiseOfOutputStream = concurrent.promise[OutputStream]()
      process run new ProcessIO(
        (stdin: OutputStream) => promiseOfOutputStream.success(stdin),
        _.close(),
        logstderr(_)
      );
      promiseOfOutputStream.future.map { cmdin =>
        Iteratee.foreach[Array[Byte]] { bytes =>
          cmdin.write(bytes)
        }
      }

    }
  }
}

import play.api.Play.current
import play.api.libs.ws._

object Application extends Controller {

  def index = find

  /**
   * Re-stream a web radio by adding echo with sox
   */
  def webRadioWithEcho = Action {
    val radioSrc = "http://radio.hbr1.com:19800/ambient.ogg"
    var radio = Concurrent.unicast[Array[Byte]]( channel => {
      WS.url(radioSrc).get { header =>
        Iteratee.foreach[Array[Byte]](channel.push(_))
      }
    }, () => ())
    val addEcho = CLI.pipe("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
    Ok.stream(radio &> addEcho &> Concurrent.dropInputIfNotReady(50))
      .withHeaders(
        CONTENT_TYPE -> "audio/ogg"
      )
  }

  // List all files in this Play project
  def find = Action {
    Ok.stream(CLI.enumerate("find .") >>> Enumerator.eof).withHeaders(
      CONTENT_TYPE -> "text/plain"
    )
  }
  
  // Retrieve a webpage and display it (of course, this is just for the demo, I won't use curl, prefer using WS)
  def curl = Action {
    Ok.stream(CLI.enumerate("curl -s http://blog.greweb.fr/") >>> Enumerator.eof).withHeaders(
      CONTENT_TYPE -> "text/html"
    )
  }

  // consume a ogg sound, add an echo effect and store in a /tmp/out.ogg file
  def audioEchoEffectGenerate = Action {
    val consumer = CLI.consume("sox -t ogg - /tmp/out.ogg echo 0.5 0.7 60 1")
    Enumerator.fromFile(Play.getFile("conf/exemple.ogg"))(consumer)
    Ok("'/tmp/out.ogg' file has been generated.")
  }

  
}
