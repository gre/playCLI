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

  import scala.concurrent.ExecutionContext
  object internal {
    implicit lazy val defaultExecutionContext: ExecutionContext = {
      val playConfig = play.api.Play.maybeApplication.map(_.configuration)
      val nb = playConfig.flatMap(_.getInt("CLI.threadpool.size")).getOrElse(50)
      ExecutionContext.fromExecutorService(java.util.concurrent.Executors.newFixedThreadPool(nb))
    }
  }

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
  def enumerate (command: ProcessBuilder, chunkSize: Int = 1024 * 8) =
    new Enumerator[Array[Byte]] {
      def apply[A](consumer: Iteratee[Array[Byte], A]) = {
        import internal.defaultExecutionContext
        runProcess(command) map { case (process, stdin, stdout, stderr) =>
          logger.debug("enumerate "+command)
          stderr.map(logStream(_)(logger.warn))

          val done = Promise[Unit]() // fulfilled when either done consuming / either done enumerating

          // When done, close inputs and terminate the process
          done.future onComplete { _ =>
            stdin map (_.close())
            stderr map (_.close())
            terminateProcess(process, command.toString)
          }

          // when consumer has done, trigger done
          val iteratee = consumer mapDone { r =>
            done.trySuccess(())
            r 
          }

          stdout flatMap { stdout => 
            Enumerator.fromStream(stdout, chunkSize) // fromStream will .close() stdout
              .onDoneEnumerating { () => done.trySuccess(()) } // when done enumerating, trigger done
              .apply(iteratee)
          }
        } getOrElse(Future.successful(Error("CLI failed", Input.Empty)))
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
  def pipe (command: ProcessBuilder, chunkSize: Int = 1024 * 8) =
    new Enumeratee[Array[Byte], Array[Byte]] { 
      def applyOn[A](consumer: Iteratee[Array[Byte], A]) = {
        import internal.defaultExecutionContext
        runProcess(command) map { case (process, stdin, stdout, stderr) =>
          logger.debug("pipe "+command)

          stderr.map(logStream(_)(logger.warn))

          Iteratee.flatten {
            (stdin zip stdout).map { case (stdin, stdout) =>
              import scala.concurrent.stm._

              val doneReading = Promise[Unit]()
              val doneWriting = Promise[Unit]()

              // When finished to read, close stdout
              doneReading.future onComplete { _ =>
                logger.trace("finished to read stdout for "+command)
                stdout.close()
              }

              // When finished to write, close stdin
              doneWriting.future onComplete { _ =>
                logger.trace("finished to write stdin for "+command)
                stdin.close()
              }

              // When reading/writing is finished, terminate the process
              (doneReading.future zip doneWriting.future) onComplete { _ =>
                stderr map (_.close())
                terminateProcess(process, command.toString)
              }

              // When consumer has done, trigger done for reading and writing
              val iteratee = Ref(consumer mapDone { r =>
                logger.trace("consumer has done for "+command)
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
        } getOrElse(Done(Error("CLI failed", Input.EOF), Input.EOF)) // FIXME: am I doing something wrong?
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
  def consume (command: ProcessBuilder)(enumerator: Enumerator[Array[Byte]]) =
    enumerator |>>> Iteratee.flatten[Array[Byte], Int] {
      import internal.defaultExecutionContext
      runProcess(command) map { case (process, stdin, stdout, stderr) =>
        logger.debug("consume "+command)

        stdout.map(logStream(_)(logger.info))
        stderr.map(logStream(_)(logger.warn))

        stdin map { stdin =>
          Iteratee.foreach[Array[Byte]] { bytes =>
            stdin.write(bytes)
          } mapDone { _ =>
            stdin.close()
            stdout map (_.close())
            stderr map (_.close())
            terminateProcess(process, command.toString)
          }
        }
      } getOrElse(Future.successful(Error("CLI failed", Input.Empty)))
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
  : Option[(Process, Future[OutputStream], Future[InputStream], Future[InputStream])] = {
    val promiseStdin = Promise[OutputStream]()
    val promiseStdout = Promise[InputStream]()
    val promiseStderr = Promise[InputStream]()
    try {
      val process = command run new ProcessIO(
        (stdin: OutputStream) => promiseStdin.success(stdin),
        (stdout: InputStream) => promiseStdout.success(stdout),
        (stderr: InputStream) => promiseStderr.success(stderr)
      )
      Some( (process, promiseStdin.future, promiseStdout.future, promiseStderr.future) )
    }
    catch {
      case e: Throwable =>
        logger.error("Unable to run "+command+" : "+e.getMessage)
        None
    }
  }

  private def terminateProcess (process: Process, commandName: String): Int = {
    logger.trace("terminating process for command "+commandName)
    val code = process.exitValue()
    logger.debug("exit("+code+") for command "+commandName)
    process.destroy()
    code
  }

}
