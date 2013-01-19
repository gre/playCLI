package playcli

import org.slf4j.{ LoggerFactory, Logger }

import java.io.{ InputStream, OutputStream }
import sys.process.{ Process, ProcessIO, ProcessBuilder }
import concurrent.{ Promise, Future, ExecutionContext, Await }
import concurrent.duration._
import play.api.libs.iteratee._

/**
 * CLI defines helpers to deal with UNIX command with Play Framework iteratees.
 *
 * ==Overview==
 *
 * Depending on your needs, you can Enumerate / Pipe / Consume an UNIX command:
 *
 * [[CLI.enumerate]] is a way to create a stream from a command which generates output 
 * (it creates an [[play.api.libs.iteratee.Enumerator]])
 *
 * [[CLI.pipe]] is a way to pipe a command which consumes input and generates output 
 * (it creates an [[play.api.libs.iteratee.Enumeratee]])
 *
 * [[CLI.consume]] creates a process which consumes a stream - useful for side effect commands 
 * (it creates an [[play.api.libs.iteratee.Iteratee]])
 *
 * ==Note==
 *
 * [[CLI.enumerate]] and [[CLI.pipe]] API are immutable, in other words, each result can be stored 
 * in a val and re-used multiple times. A new process is created for each re-use.
 * [[CLI.consume]] is mutable should not be used multiple times because it targets side effect command.
 *
 * Every process' `stderr` is logged in the console with a "CLI" logger
 *
 * @version 0.1
 */
object CLI {

  private[playcli] object internal {
    implicit lazy val defaultExecutionContext: ExecutionContext = {
      val nb = try { com.typesafe.config.ConfigFactory.load().getInt("CLI.threadpool.size") } 
             catch { case e: com.typesafe.config.ConfigException.Missing => 100 }
      ExecutionContext.fromExecutorService(java.util.concurrent.Executors.newFixedThreadPool(nb))
    }
  }

  /**
   * Returns an Enumerator from a command which generates output - nothing is sent to the CLI input.
   *
   * @param command the UNIX command
   * @param timeoutInMs the time to wait before the process to terminate when enumerate is done
   * @return an [[play.api.libs.iteratee.Enumerator]] from this command which generate output. (immutable)
   *
   * @example {{{
   CLI.enumerate("find .")
   * }}}
   */
  def enumerate (command: ProcessBuilder, chunkSize: Int = 1024*8, timeoutInMs: Long = defaultTerminateTimeout): Enumerator[Array[Byte]] =
    new Enumerator[Array[Byte]] {
      def apply[A](consumer: Iteratee[Array[Byte], A]) = {
        import internal.defaultExecutionContext
        val (process, stdin, stdout, stderr) = runProcess(command) 
        logger.debug("enumerate "+command)
        stderr.map(logStream(_)(logger.warn))

        val done = Promise[Unit]() // fulfilled when either done consuming / either done enumerating

        // When done, close inputs and terminate the process
        done.future onComplete { _ =>
          stdin map (_.close())
          stderr map (_.close())
          terminateProcess(process, command.toString, timeoutInMs)
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
      }
    }

  /**
   * Returns an Enumeratee for piping a command which consumes input and generates output.
   *
   * @param command the UNIX command
   * @param timeoutInMs the time to wait before the process to terminate when pipe is done
   * @return an [[play.api.libs.iteratee.Enumeratee]] from the pipe of this command which consumes input and generates output. (immutable)
   *
   * @example {{{
     // Add an echo to an ogg audio stream.
     oggStream &> CLI.pipe("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
   * }}}
   */
  def pipe (command: ProcessBuilder, chunkSize: Int = 1024*8, timeoutInMs: Long = defaultTerminateTimeout): Enumeratee[Array[Byte], Array[Byte]] =
    new Enumeratee[Array[Byte], Array[Byte]] { 
      def applyOn[A](consumer: Iteratee[Array[Byte], A]) = {
        import internal.defaultExecutionContext
        val (process, stdin, stdout, stderr) = runProcess(command)
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
              terminateProcess(process, command.toString, timeoutInMs)
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
                    logger.trace("reach end of stdout")
                    doneReading.trySuccess(())
                  
                  case read => 
                    val input = new Array[Byte](read)
                    System.arraycopy(buffer, 0, input, 0, read)
                    logger.trace("read "+read+" bytes")
                    val p = Promise[Iteratee[Array[Byte], A]]()
                    val next = Iteratee.flatten(p.future)
                    val it = iteratee.single.swap(next)
                    p.success(Iteratee.flatten(it.feed(Input.El(input))))
                }
              }
            }

            def stepDone() = {
              doneWriting.trySuccess(())
              Iteratee.flatten[Array[Byte], Iteratee[Array[Byte],A]] {
                doneReading.future map { _ =>
                  Done(iteratee.single.get, Input.EOF)
                }
              }
            }

            // Writing stdin iteratee loop (until EOF)
            def step(): Iteratee[Array[Byte], Iteratee[Array[Byte], A]] = Cont {
              case Input.El(e) => {
                logger.trace("write "+e.length+" bytes")
                stdin.write(e)
                // flush() will throw an exception once the process has terminated
                val available = try { stdin.flush(); true } catch { case _: java.io.IOException => false }
                if (available) step()
                else {
                  logger.trace("stdin is no more writable (flush failed).")
                  stepDone()
                }
              }
              case Input.Empty => step()
              case Input.EOF => {
                logger.trace("reach stdin EOF")
                stepDone()
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
   * Returns an Iteratee for piping a command which consumes input.
   *
   * This method is useful for side effect commands.
   *
   * @param command the UNIX command
   * @param timeoutInMs the time to wait before the process to terminate when consume is done
   * @return an `Iteratee[Array[Byte], Int]` which consumes data and returns the exitValue of the command when done.
   *
   * @example {{{
     enumerator |>>> CLI.consume("aSideEffectCommand")
   * }}}
   */
  def consume (command: ProcessBuilder, timeoutInMs: Long = defaultTerminateTimeout): Iteratee[Array[Byte], Int] =
    Iteratee.flatten[Array[Byte], Int] {
      import internal.defaultExecutionContext
      val (process, stdin, stdout, stderr) = runProcess(command)
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
          terminateProcess(process, command.toString, timeoutInMs)
        }
      }
    }

  private case class LazyLogger(logger: org.slf4j.Logger) {
    def trace(s: => String) { if(logger.isTraceEnabled) logger.trace(s) }
    def debug(s: => String) { if(logger.isDebugEnabled) logger.debug(s) }
    def info(s: => String) { if(logger.isInfoEnabled) logger.info(s) }
    def warn(s: => String) { if(logger.isWarnEnabled) logger.warn(s) }
    def error(s: => String) { if(logger.isErrorEnabled) logger.error(s) }
  }

  private val logger = LazyLogger(LoggerFactory.getLogger("CLI"))

  /**
   * Logs an InputStream with a logger function.
   * @param stream the InputStream
   * @param loggerF the logger function
   */
  private def logStream (stream: InputStream)(loggerF: (=>String)=>Unit) {
    val br = new java.io.BufferedReader(new java.io.InputStreamReader(stream))
    var read = br.readLine()
    while(read != null) {
      loggerF(read)
      read = br.readLine()
    }
  }

  /**
   * Runs a process from a command.
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

  private val defaultTerminateTimeout = 
      try { com.typesafe.config.ConfigFactory.load().getInt("CLI.timeout") }
    catch { case e: com.typesafe.config.ConfigException.Missing => 60000 }

  /**
   * Terminates a process and returns the exitValue.
   * @param process the process
   * @param commandName the command name
   * @param timeoutInMs the time to wait before the process to terminate
   * @return the exitValue (integer from the UNIX command)
   */
  private def terminateProcess (process: Process, commandName: String, timeoutInMs: Long = defaultTerminateTimeout)(implicit ex: ExecutionContext): Int = {
    val timeout = Duration(timeoutInMs, MILLISECONDS)

    val code = try {
      Await.result(Future {
        logger.trace("waiting exitValue for command "+commandName)
        process.exitValue()
      }, timeout)
    }
    catch {
      case e: java.util.concurrent.TimeoutException =>
        logger.debug("timeout reached")
        process.destroy()
        process.exitValue()
    }

    logger.debug("exit("+code+") for command "+commandName)
    code
  }

}
