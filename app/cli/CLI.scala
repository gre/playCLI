package cli

import java.io._
import scala.sys.process.{ ProcessIO, ProcessBuilder }
import play.api.libs.iteratee._

/**
 * CLI provides a link between play iteratee and an UNIX command.
 *
 * Depending on your needs, you can Pipe / Enumerate / Consume with a UNIX command:
 *
 * `CLI.pipe` create an [[play.api.libs.iteratee.Enumeratee]]
 * `CLI.enumerate` create an [[play.api.libs.iteratee.Enumerator]]
 * `CLI.consume` create an [[play.api.libs.iteratee.Iteratee]]
 */
object CLI {

  val logger = play.api.Logger("CLI")

  def logstderr (stderr: InputStream) = { // FIXME, rewrite this with iteratee?
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
    Enumeratee.map[Array[Byte]] { bytes => bytes } // FIXME how to implement this?
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
