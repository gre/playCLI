package cli

import java.io._
import scala.sys.process.{ ProcessIO, ProcessBuilder }
import play.api.libs.iteratee._

/**
 * CLI provides a link between play iteratee and an UNIX command.
 *
 * Depending on your needs, you can Pipe / Enumerate / Consume with an UNIX command:
 *
 * `CLI.pipe` create an [[play.api.libs.iteratee.Enumeratee]]
 * `CLI.enumerate` create an [[play.api.libs.iteratee.Enumerator]]
 * `CLI.consume` create an [[play.api.libs.iteratee.Iteratee]]
 */
object CLI {

  val logger = play.api.Logger("CLI")



  def logstderr (stderr: InputStream) {
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
  def enumerate (cmd: ProcessBuilder, chunkSize: Int = 1024 * 8): Enumerator[Array[Byte]] = 
    Enumerator.flatten[Array[Byte]] {
      import scala.concurrent.ExecutionContext.Implicits.global
      val promiseOfInputStream = concurrent.promise[InputStream]()
      val process = cmd run new ProcessIO(
        _.close(),
        (stdout: InputStream) => promiseOfInputStream.success(stdout),
        logstderr(_)
      );
      promiseOfInputStream.future.map { cmdout =>
        Enumerator.fromStream(cmdout, chunkSize).
          onDoneEnumerating { () =>
            process.destroy()
          }
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
  def pipe (cmd: ProcessBuilder, chunkSize: Int = 1024 * 8): Enumeratee[Array[Byte], Array[Byte]] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val promiseOfOutputStream = concurrent.promise[OutputStream]()
    val promiseOfInputStream = concurrent.promise[InputStream]()
    val process = cmd run new ProcessIO(
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
      Enumerator.fromStream(cmdout, chunkSize)
    }

    import Enumeratee.CheckDone

    var promiseOfEnumeratee = (promiseCmdin zip promiseCmdout).map { case (cmdin, cmdout) =>

      // FIXME, I'm trying something, this is not working yet...
      /**
       * What I basically want is to create an Enumeratee where:
       * - all input from this Enumeratee are plugged to the cmdin (cmdin: Iteratee)
       * - all input coming from cmdout (cmdout: Enumerator) are plugged to the output of this Enumeratee
       */
      new CheckDone[Array[Byte], Array[Byte]] {

        def step[A](k: K[Array[Byte], A]): K[Array[Byte], Iteratee[Array[Byte], A]] = {
          case in @ Input.El(bytes) => {
            cmdin.feed(in)
            val r = Iteratee.flatten(cmdout |>> k(in))
            new CheckDone[Array[Byte], Array[Byte]] { 
              def continue[A](k: K[Array[Byte], A]) = Cont(step(k)) 
            } &> r
          }

          case in @ Input.Empty => {
            val r = Iteratee.flatten(cmdout |>> k(in))
            new CheckDone[Array[Byte], Array[Byte]] { 
              def continue[A](k: K[Array[Byte], A]) = Cont(step(k)) 
            } &> r
          }

          case Input.EOF => {
            Done(Cont(k), Input.EOF)
          }
        }
        def continue[A](k: K[Array[Byte], A]) = Cont(step(k))
      } ><> 
      Enumeratee.onIterateeDone { () =>
        process.destroy()
      }

    }
    concurrent.Await.result(promiseOfEnumeratee, concurrent.duration.Duration("1 second")) // FIXME need a Enumeratee.flatten
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
  def consume (cmd: ProcessBuilder): Iteratee[Array[Byte], Unit] = {
    Iteratee.flatten[Array[Byte], Unit] {
      import scala.concurrent.ExecutionContext.Implicits.global
      val promiseOfOutputStream = concurrent.promise[OutputStream]()
      val process = cmd run new ProcessIO(
        (stdin: OutputStream) => promiseOfOutputStream.success(stdin), // FIXME: how to close this stdin when done?
        _.close(),
        logstderr(_)
      );
      promiseOfOutputStream.future.map { cmdin =>
        Iteratee.foreach[Array[Byte]] { bytes =>
          cmdin.write(bytes)
        } mapDone { _ =>
          cmdin.close()
          process.destroy()
        }
      }

    }
  }
}
