package cli

import java.io._
import scala.sys.process.{ Process, ProcessIO, ProcessBuilder }
import play.api.libs.iteratee._

import concurrent.{ promise, Future, ExecutionContext }

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


  /**
   * Get an [[play.api.libs.iteratee.Enumerator]] from a CLI output.
   * (nothing is sent to the CLI input)
   *
   * @example {{{
   CLI.enumerate("find .")
   * }}}
   */
  def enumerate (cmd: ProcessBuilder, chunkSize: Int = 1024 * 8)(implicit ex: ExecutionContext): Enumerator[Array[Byte]] = Enumerator.flatten[Array[Byte]] {
    val (process, stdin, stdout) = runProcess(cmd)

    stdout map { stdout =>
      Enumerator.fromStream(stdout, chunkSize).
      onDoneEnumerating { () =>
        val code = process.exitValue()
        logger.debug("exit("+code+") for command"+cmd)
        stdin map { _.close() }
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
  def pipe (cmd: ProcessBuilder, chunkSize: Int = 1024 * 8)(implicit ex: ExecutionContext): Enumeratee[Array[Byte], Array[Byte]] = {
    val (process, stdin, stdout) = runProcess(cmd)
    
    val promiseCmdin = stdin.map { cmdin =>
      Iteratee.foreach[Array[Byte]] { bytes =>
        cmdin.write(bytes)
        } mapDone { _ =>
          cmdin.close()
        }
      }

    val promiseCmdout = stdout.map { cmdout =>
      Enumerator.fromStream(cmdout, chunkSize)
    }

    var promiseOfEnumeratee = (promiseCmdin zip promiseCmdout).map { case (cmdin, cmdout) =>
      enumerateePipe(cmdin, cmdout) ><> 
      Enumeratee.onIterateeDone { () =>
        val code = process.exitValue()
        logger.debug("exit("+code+") for command"+cmd)
        process.destroy()
      }
    }
    concurrent.Await.result(promiseOfEnumeratee, concurrent.duration.Duration("2 second")) // FIXME need a Enumeratee.flatten
  }

  /**
   * Create an Enumeratee where:
   * - all input sent to this Enumeratee are plugged to the cmdin (cmdin: Iteratee)
   * - all input coming from cmdout (cmdout: Enumerator) are streamed to the output of this Enumeratee
   */
  def enumerateePipe (
    cmdin: Iteratee[Array[Byte], Unit], 
    cmdout: Enumerator[Array[Byte]]
  )(implicit ex: ExecutionContext) : Enumeratee[Array[Byte], Array[Byte]] = {

    // enumerateePipe: FIXME Not Implemented Yet!

    Enumerator() |>>> cmdin // FIXME (temporary cmdin consuming)
    
    new Enumeratee[Array[Byte], Array[Byte]] {
      def applyOn[A] (it: Iteratee[Array[Byte], A]): Iteratee[Array[Byte], Iteratee[Array[Byte], A]] = {
        Enumeratee.passAlong[Array[Byte]] &> Iteratee.flatten(cmdout(it))
      }
    }
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
  def consume (cmd: ProcessBuilder)(implicit ex: ExecutionContext): Iteratee[Array[Byte], Unit] = Iteratee.flatten[Array[Byte], Unit] {
    val (process, stdin, stdout) = runProcess(cmd)

    stdin map { cmdin =>
      Iteratee.foreach[Array[Byte]] { bytes =>
        cmdin.write(bytes)
      } mapDone { _ =>
        cmdin.close()
        val code = process.exitValue()
        logger.debug("exit("+code+") for command"+cmd)
        stdout map { _.close() }
        process.destroy()
      }
    }
  }


  private val logger = play.api.Logger("CLI")

  private def logstderr (stderr: InputStream) {
    val br = new java.io.BufferedReader(new InputStreamReader(stderr))
    var read = br.readLine()
    while(read != null) {
      logger.warn(read)
      read = br.readLine()
    }
    stderr.close()
  }

  /**
   * Run a process from a command and return a (process, future of stdin, future of stdout)
   */
  private def runProcess (command: ProcessBuilder)(implicit ex: ExecutionContext): (Process, Future[OutputStream], Future[InputStream]) = {
    val promiseStdin = promise[OutputStream]()
    val promiseStdout = promise[InputStream]()

    val process = command run new ProcessIO(
      (stdin: OutputStream) => promiseStdin.success(stdin),
      (stdout: InputStream) => promiseStdout.success(stdout),
      logstderr(_)
    )
    (process, promiseStdin.future, promiseStdout.future)
  }
}
