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
      def apply[A](consumer: Iteratee[Array[Byte], A]) = {
        logger.debug("enumerate "+command)
        val (process, stdin, stdout, stderr) = runProcess(command)

        stderr.map(logStream(_)(logger.error))

        val done = Promise[Unit]() // fulfilled when either done consuming / either done enumerating

        val iteratee = consumer mapDone { r => done.trySuccess(()); r }

        // When done, close everything
        done.future onComplete { _ =>
          stdin map (_.close())
          stderr map (_.close())
          closeProcess(process, command.toString)
        }

        stdout flatMap { stdout => 
          Enumerator.fromStream(stdout, chunkSize)
            .onDoneEnumerating { () => done.trySuccess(()) }
            .apply(iteratee)
        }
      }
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
      def applyOn[A](consumer: Iteratee[Array[Byte], A]) = {

        logger.debug("pipe "+command)
        val (process, stdin, stdout, stderr) = runProcess(command)

        stderr.map(logStream(_)(logger.error))

        Iteratee.flatten {
          (stdin zip stdout).map { case (stdin, stdout) =>
            import scala.concurrent.stm._

            val doneReading = Promise[Unit]()
            val doneWriting = Promise[Unit]()

            // When finished to read, close stdout
            doneReading.future onComplete { _ =>
              logger.trace("finished to read stdout for"+command)
              stdout.close()
            }

            // When finished to write, close stdin
            doneWriting.future onComplete { _ =>
              logger.trace("finished to write stdin for"+command)
              stdin.close()
            }

            // When reading/writing is finished, terminate the process
            (doneReading.future zip doneWriting.future) onComplete { _ =>
              stderr map (_.close())
              closeProcess(process, command.toString)
            }

            // When the consumer has finished, stop everything
            val iteratee = Ref(consumer mapDone { r =>
              doneReading.trySuccess(())
              doneWriting.trySuccess(())
              r
            })

            // Reading stdout and accumulating iteratee
            Future {
              while (!doneReading.future.isCompleted) {
                val buffer = new Array[Byte](chunkSize)
                stdout.read(buffer) match {
                  case -1 => 
                    doneReading.trySuccess(())
                  
                  case read => 
                    val input = new Array[Byte](read)
                    System.arraycopy(buffer, 0, input, 0, read)
                    val p = Promise[Iteratee[Array[Byte], A]]()
                    val next = Iteratee.flatten(p.future)
                    val it = iteratee.single.swap(next)
                    p.success(Iteratee.flatten(it.feed(Input.El(input))))
                }
              }
            }

            // Writing stdin iteratee loop (until EOF)
            def step(): Iteratee[Array[Byte], Iteratee[Array[Byte], A]] = Cont {
              case Input.El(e) => {
                stdin.write(e)
                step
              }
              case Input.Empty => step
              case Input.EOF => {
                doneWriting.trySuccess(())
                Iteratee.flatten {
                  doneReading.future map { _ =>
                    Done(iteratee.single.get, Input.EOF)
                  }
                }
              }
            }
            val result = step()

            // Finish with EOF
            result mapDone { it =>
              Iteratee.flatten(it.feed(Input.EOF))
            }
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

      stdout.map(logStream(_)(logger.info))
      stderr.map(logStream(_)(logger.error))

      stdin map { stdin =>
        Iteratee.foreach[Array[Byte]] { bytes =>
          stdin.write(bytes)
        } mapDone { _ =>
          stdin.close()
          stdout map (_.close())
          stderr map (_.close())
          closeProcess(process, command.toString)
        }
      }
    }


  private val logger = play.api.Logger("CLI")

  private def logStream (stream: InputStream)(loggerF: (=>String)=>Unit) {
    val br = new java.io.BufferedReader(new InputStreamReader(stream))
    var read = br.readLine()
    while(read != null) {
      loggerF(read)
      read = br.readLine()
    }
  }

  /**
   * Run a process from a command 
   * @return a (process, future of stdin, future of stdout, future of stderr)
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

  private def closeProcess (process: Process, commandName: String): Int = {
    val code = process.exitValue()
    logger.debug("exit("+code+") for command"+commandName)
    process.destroy()
    code
  }

}
