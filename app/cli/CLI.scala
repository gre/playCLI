package cli

import java.io._
import scala.sys.process.{ Process, ProcessIO, ProcessBuilder }
import play.api.libs.iteratee._

import concurrent.{ Promise, Future, ExecutionContext }

/**
 * CLI defines helpers to deal with UNIX command with Play Framework iteratees.
 *
 * ==Overview==
 *
 * Depending on your needs, you can Enumerate / Pipe / Consume an UNIX command:
 *
 * [[CLI.enumerate]] is a way to create a stream from a command which generate output (it creates an [[play.api.libs.iteratee.Enumerator]])

 * [[CLI.pipe]] is a way to pipe a command which consume input and generate output (it creates an [[play.api.libs.iteratee.Enumeratee]])

 * [[CLI.consume]] creates a process which consume a stream - useful for side effect commands (it takes an [[play.api.libs.iteratee.Enumerator]])
 *
 * ==Note==
 * Every process' `stderr` is logged in the console with a "CLI" logger
 *
 * @version 0.1
 */
object CLI {


  /**
   * Returns an Enumerator from a command which generate output - nothing is sent to the CLI input.
   *
   * @param command the UNIX command
   * @return an [[play.api.libs.iteratee.Enumerator]] from this command which generate output.
   *
   * @example {{{
   CLI.enumerate("find .")
   * }}}
   */
  def enumerate (command: ProcessBuilder, chunkSize: Int = 1024 * 8)(implicit ex: ExecutionContext) =
    new Enumerator[Array[Byte]] {
      def apply[A](i: Iteratee[Array[Byte], A]) = Enumerator.flatten[Array[Byte]] {
        logger.debug("enumerate "+command)
        val (process, stdin, stdout, stderr) = runProcess(command)
        stderr map { stderr => logStd(stderr)(logger.error) }
        stdout map { stdout =>
          Enumerator.fromStream(stdout, chunkSize).
          onDoneEnumerating { () =>
            stdin map { _.close() }
            val code = process.exitValue()
            logger.debug("exit("+code+") for command"+command)
            process.destroy()
          }
        }
      } apply i
    }

  /**
   * Returns an Enumeratee for piping a command which consume input and generate output.
   *
   * @param command the UNIX command
   * @return an [[play.api.libs.iteratee.Enumeratee]] from the pipe of this command which consume input and generate output.

   *
   * @example {{{
     // Add an echo to an ogg audio stream.
     oggStream &> CLI.pipe("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
   * }}}
   */
  def pipe (command: ProcessBuilder, chunkSize: Int = 1024 * 8)(implicit ex: ExecutionContext) =
    new Enumeratee[Array[Byte], Array[Byte]] { 
      def applyOn[A](it: Iteratee[Array[Byte], A]): Iteratee[Array[Byte], Iteratee[Array[Byte], A]] = {
        logger.debug("pipe "+command)
        val (process, stdin, stdout, stderr) = runProcess(command)

        stderr map { stderr => logStd(stderr)(logger.error) }

        Iteratee.flatten {
          (stdin zip stdout).map { case (stdin, stdout) =>
            import scala.concurrent.stm._

            val iteratee = Ref(it)
            val endP = Promise[Unit]()
            val end = endP.future

            // Reading stdout
            Future {
              while (!end.isCompleted) {
                val buffer = new Array[Byte](chunkSize)
                stdout.read(buffer) match {
                  case -1 => 
                    endP.success(())
                  
                  case read => 
                    val input = new Array[Byte](read)
                    System.arraycopy(buffer, 0, input, 0, read)
                    val p = Promise[Iteratee[Array[Byte], A]]()
                    val next = Iteratee.flatten(p.future)
                    val it = iteratee.single.swap(next)
                    p.success(Iteratee.flatten(it.feed(Input.El(input))))
                }
              }
              logger.debug("done reading")
              stdout.close()
            }

            // Writing stdin
            def step(): Iteratee[Array[Byte], Iteratee[Array[Byte], A]] = Cont {
              case Input.El(e) => {
                stdin.write(e)
                step
              }
              case Input.Empty => step
              case Input.EOF => {
                logger.debug("done writing")
                stdin.close()
                Iteratee.flatten {
                  end map { _ =>
                    logger.debug("Done step reached")
                    Done(iteratee.single.get, Input.EOF)
                  }
                }
              }
            }
            val result = step()

            // When Writing and Reading is finished
            result mapDone { _ =>
              println("MAP DONE NEVER REACHED !") // FIXME
              val code = process.exitValue()
              logger.debug("exit("+code+") for command"+command)
              process.destroy()
            }

            result
          }
        }
      }
    }


  /**
   * Consumes an Enumerator with a command - the CLI output is logged.
   *
   * This method is useful for side effect commands.
   *
   * @param command the UNIX command
   * @param enumerator the enumerator producing data consumed by this command
   * @return a `Future[Int]` completed when the consuming has finished. The Future value contains the command code value.
   *
   * @example {{{
     CLI.consume("aSideEffectCommand")(anEnumerator)
   * }}}
   */
  def consume (command: ProcessBuilder)(enumerator: Enumerator[Array[Byte]])(implicit ex: ExecutionContext) =
    enumerator |>>> Iteratee.flatten[Array[Byte], Int] {
      logger.debug("consume "+command)
      val (process, stdin, stdout, stderr) = runProcess(command)

      stdout map { stdout => logStd(stdout)(logger.info) }
      stderr map { stderr => logStd(stderr)(logger.error) }

      stdin map { stdin =>
        Iteratee.foreach[Array[Byte]] { bytes =>
          stdin.write(bytes)
        } mapDone { _ =>
          stdin.close()
          val code = process.exitValue()
          logger.debug("exit("+code+") for command"+command)
          stdout map { _.close() }
          process.destroy()
          code
        }
      }
    }


  private val logger = play.api.Logger("CLI")

  private def logStd (stream: InputStream)(loggerF: (=>String)=>Unit ) {
    val br = new java.io.BufferedReader(new InputStreamReader(stream))
    var read = br.readLine()
    while(read != null) {
      loggerF(read)
      read = br.readLine()
    }
    stream.close()
  }

  /**
   * Run a process from a command 
   * @return a (process, future of stdin, future of stdout)
   */
  private def runProcess (command: ProcessBuilder)(implicit ex: ExecutionContext)
  : (Process, Future[OutputStream], Future[InputStream], Future[InputStream]) = {
    val promiseStdin = Promise[OutputStream]()
    val promiseStdout = Promise[InputStream]()
    val promiseStderr = Promise[InputStream]()

    val process = command run new ProcessIO(
      (stdin: OutputStream) => promiseStdin.success(stdin),
      (stdout: InputStream) => promiseStdout.success(stdout),
      (stderr: InputStream) => promiseStderr.success(stderr)
    )
    (process, promiseStdin.future, promiseStdout.future, promiseStderr.future)
  }
}
