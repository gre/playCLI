package playcli

import org.slf4j.{ LoggerFactory, Logger }

import java.io.{ InputStream, OutputStream }
import sys.process.{ Process, ProcessIO, ProcessBuilder }
import concurrent.{ Promise, Future, ExecutionContext, Await }
import concurrent.duration._
import play.api.libs.iteratee._

/**
 */

/**
 * CLI defines helpers to deal with UNIX command with [[http://playframework.org Play Framework]] iteratees.
 *
 * ==Overview==
 *
 * Depending on your needs, you can '''Enumerate / Pipe / Consume''' an UNIX command:
 *
 * [[CLI.enumerate]] is a way to create a stream from a command which '''generates output'''
 * (it creates an $Enumerator[Array[Byte]] )
 *
 * [[CLI.pipe]] is a way to pipe a command which '''consumes input and generates output'''
 * (it creates an $Enumeratee[Array[Byte],Array[Byte]])
 *
 * [[CLI.consume]] creates a process which '''consumes a stream''' - useful for side effect commands
 * (it creates an $Iteratee[Array[Byte],Int])
 *
 * {{{
 * import playcli._
 * import scala.sys.process._
 *
 * // Some CLI use cases
 * val tail = CLI.enumerate("tail -f /var/log/nginx/access.log")
 * val grep = (word: String) => CLI.pipe(Seq("grep", word))
 * val ffmpeg = CLI.pipe("ffmpeg -i pipe:0 ... pipe:1") // video processing
 * val convert = CLI.pipe("convert - -colors 64 png:-") // color quantization
 *
 * // Some usage examples
 * val sharedTail = Concurrent.broadcast(tail)
 * Ok.stream(sharedTail).withHeaders(CONTENT_TYPE -> "text/plain") // Play framework
 *
 * val searchResult: Enumerator[String] = dictionaryEnumerator &> grep("able") &> aStringChunker
 *
 * Ok.stream(Enumerator.fromFile("image.jpg") &> convert).withHeaders(CONTENT_TYPE -> "image/png")
 *
 * Enumerator.fromFile("video.avi") &> ffmpeg &> ...
 * }}}
 *
 * ==Process==
 *
 * CLI uses [[http://www.scala-lang.org/api/current/index.html#scala.sys.process.package scala.sys.process]]
 * and create a Process instance for each UNIX command.
 *
 * A CLI process terminates when:
 - The command has end.
 - stdin and stdout is terminated.
 - $Done is reached (for [[enumerate]] and [[pipe]]).
 - $EOF is sent (for [[pipe]] and [[consume]]).
 *
 * CLI still waits for the Process to terminate by asking the exit code (via `Process.exitCode()`).
 * If the process is never ending during this phase, it will be killed when `terminateTimeout` is reached.
 *
 * PS: Thanks to implicits, you can simply give a [[String]] or a [[Seq]] to give the CLI.* functions a `ProcessBuilder`.
 *
 * ==Mutability==
 *
 * [[enumerate]] and [[pipe]] are '''immutable''', in other words, re-usable
 * (each result can be stored in a val and applied multiple times).
 * '''A new process is created for each re-use'''.
 *
 * [[consume]] is '''mutable''', it should not be used multiple times: it targets side effect command.
 *
 * ==Logs==
 *
 * A "CLI" logger (logback) is used to log different information in different log levels:
 *
 - '''ERROR''' would mean a CLI error (not used yet).
 - '''INFO''' used for the process' stdout output of a [[CLI.consume]].
 - '''DEBUG''' used for the process life cycle (process creation, process termination, exit code).
 - '''WARN''' used for the process' stderr output.
 - '''TRACE''' used for low level information (IO read/write).
 *
 * @see [[http://github.com/gre/playCLI PlayCLI on Github]]
 * @see [[http://github.com/gre/playCLI-examples PlayCLI-examples on Github]]
 *
 * @version 0.1
 *
 * @define Enumerator [[http://www.playframework.org/documentation/api/2.1-RC1/scala/index.html#play.api.libs.iteratee.Enumerator Enumerator]]
 * @define Iteratee [[http://www.playframework.org/documentation/api/2.1-RC1/scala/index.html#play.api.libs.iteratee.Iteratee Iteratee]]
 * @define Enumeratee [[http://www.playframework.org/documentation/api/2.1-RC1/scala/index.html#play.api.libs.iteratee.Enumeratee Enumeratee]]
 * @define Done [[http://www.playframework.org/documentation/api/2.1-RC1/scala/index.html#play.api.libs.iteratee.Done$ Done]]
 * @define EOF [[http://www.playframework.org/documentation/api/2.1-RC1/scala/index.html#play.api.libs.iteratee.Input$$EOF$ EOF]]
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
   * @param terminateTimeout the time in milliseconds to wait before the process to terminate when enumerate is done
   * @return an [[play.api.libs.iteratee.Enumerator]] from this command which generate output. (immutable)
   *
   * @example {{{
   * CLI.enumerate("find .")
   * }}}
   */
  def enumerate(command: ProcessBuilder, chunkSize: Int = 1024 * 8, terminateTimeout: Long = defaultTerminateTimeout)(implicit ec: ExecutionContext = internal.defaultExecutionContext): Enumerator[Array[Byte]] =
    new Enumerator[Array[Byte]] {
      def apply[A](consumer: Iteratee[Array[Byte], A]) = {
        val (process, stdin, stdout, stderr) = runProcess(command)
        logger.debug("enumerate " + command)
        stderr.map(logStream(_)(logger.warn))

        val done = Promise[Unit]() // fulfilled when either done consuming / either done enumerating

        // When done, close inputs and terminate the process
        done.future onComplete { _ =>
          stdin map (_.close())
          stderr map (_.close())
          terminateProcess(process, command.toString, terminateTimeout)
        }

        // when consumer has done, trigger done
        val iteratee = consumer map { r =>
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
   * @param terminateTimeout the time in milliseconds to wait before the process to terminate when pipe is done
   * @return an [[play.api.libs.iteratee.Enumeratee]] from the pipe of this command which consumes input and generates output. (immutable)
   *
   * @example {{{
   * // Add an echo to an ogg audio stream.
   * oggStream &> CLI.pipe("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
   * }}}
   */
  def pipe(command: ProcessBuilder, chunkSize: Int = 1024 * 8, terminateTimeout: Long = defaultTerminateTimeout)(implicit ec: ExecutionContext = internal.defaultExecutionContext): Enumeratee[Array[Byte], Array[Byte]] =
    new Enumeratee[Array[Byte], Array[Byte]] {
      def applyOn[A](consumer: Iteratee[Array[Byte], A]) = {
        val (process, stdin, stdout, stderr) = runProcess(command)
        logger.debug("pipe " + command)

        stderr.map(logStream(_)(logger.warn))

        Iteratee.flatten {
          (stdin zip stdout).map {
            case (stdin, stdout) =>
              import scala.concurrent.stm._

              val doneReading = Promise[Unit]()
              val doneWriting = Promise[Unit]()

              // When finished to read, close stdout
              doneReading.future onComplete { _ =>
                logger.trace("finished to read stdout for " + command)
                stdout.close()
              }

              // When finished to write, close stdin
              doneWriting.future onComplete { _ =>
                logger.trace("finished to write stdin for " + command)
                stdin.close()
              }

              // When reading/writing is finished, terminate the process
              (doneReading.future zip doneWriting.future) onComplete { _ =>
                stderr map (_.close())
                terminateProcess(process, command.toString, terminateTimeout)
              }

              // When consumer has done, trigger done for reading and writing
              val iteratee = Ref(consumer map { r =>
                logger.trace("consumer has done for " + command)
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
                      logger.trace("read " + read + " bytes")
                      val p = Promise[Iteratee[Array[Byte], A]]()
                      val next = Iteratee.flatten(p.future)
                      val it = iteratee.single.swap(next)
                      p.success(Iteratee.flatten(it.feed(Input.El(input))))
                  }
                }
              }

              def stepDone() = {
                doneWriting.trySuccess(())
                Iteratee.flatten[Array[Byte], Iteratee[Array[Byte], A]] {
                  doneReading.future map { _ =>
                    Done(iteratee.single.get, Input.EOF)
                  }
                }
              }

              // Writing stdin iteratee loop (until EOF)
              def step(): Iteratee[Array[Byte], Iteratee[Array[Byte], A]] = Cont {
                case Input.El(e) => {
                  logger.trace("write " + e.length + " bytes")
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
              result map { it =>
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
   * @param terminateTimeout the time in milliseconds to wait before the process to terminate when consume is done
   * @return an `Iteratee[Array[Byte], Int]` which consumes data and returns the exitValue of the command when done.
   *
   * @example {{{
   * enumerator |>>> CLI.consume("aSideEffectCommand")
   * }}}
   */
  def consume(command: ProcessBuilder, terminateTimeout: Long = defaultTerminateTimeout)(implicit ec: ExecutionContext = internal.defaultExecutionContext): Iteratee[Array[Byte], Int] =
    Iteratee.flatten[Array[Byte], Int] {
      val (process, stdin, stdout, stderr) = runProcess(command)
      logger.debug("consume " + command)

      stdout.map(logStream(_)(logger.info))
      stderr.map(logStream(_)(logger.warn))

      stdin map { stdin =>
        Iteratee.foreach[Array[Byte]] { bytes =>
          stdin.write(bytes)
        } map { _ =>
          stdin.close()
          stdout map (_.close())
          stderr map (_.close())
          terminateProcess(process, command.toString, terminateTimeout)
        }
      }
    }

  private case class LazyLogger(logger: org.slf4j.Logger) {
    def trace(s: => String) { if (logger.isTraceEnabled) logger.trace(s) }
    def debug(s: => String) { if (logger.isDebugEnabled) logger.debug(s) }
    def info(s: => String) { if (logger.isInfoEnabled) logger.info(s) }
    def warn(s: => String) { if (logger.isWarnEnabled) logger.warn(s) }
    def error(s: => String) { if (logger.isErrorEnabled) logger.error(s) }
  }

  private val logger = LazyLogger(LoggerFactory.getLogger("CLI"))

  /**
   * Logs an InputStream with a logger function.
   * @param stream the InputStream
   * @param loggerF the logger function
   */
  private def logStream(stream: InputStream)(loggerF: (=> String) => Unit) {
    val br = new java.io.BufferedReader(new java.io.InputStreamReader(stream))
    var read = br.readLine()
    while (read != null) {
      loggerF(read)
      read = br.readLine()
    }
  }

  /**
   * Runs a process from a command.
   * @return a (process, future of stdin, future of stdout, future of stderr)
   */
  private def runProcess(command: ProcessBuilder)(implicit ex: ExecutionContext): (Process, Future[OutputStream], Future[InputStream], Future[InputStream]) = {
    val promiseStdin = Promise[OutputStream]()
    val promiseStdout = Promise[InputStream]()
    val promiseStderr = Promise[InputStream]()
    val process = command run new ProcessIO(
      (stdin: OutputStream) => promiseStdin.success(stdin),
      (stdout: InputStream) => promiseStdout.success(stdout),
      (stderr: InputStream) => promiseStderr.success(stderr))
    (process, promiseStdin.future, promiseStdout.future, promiseStderr.future)
  }

  private val defaultTerminateTimeout =
    try { com.typesafe.config.ConfigFactory.load().getInt("CLI.timeout") }
    catch { case e: com.typesafe.config.ConfigException.Missing => 60000 }

  /**
   * Terminates a process and returns the exitValue.
   * @param process the process
   * @param commandName the command name
   * @param terminateTimeout the time in milliseconds to wait before the process to terminate
   * @return the exitValue (integer from the UNIX command)
   */
  private def terminateProcess(process: Process, commandName: String, terminateTimeout: Long = defaultTerminateTimeout)(implicit ex: ExecutionContext): Int = {
    val timeout = Duration(terminateTimeout, MILLISECONDS)

    val code = try {
      Await.result(Future {
        logger.trace("waiting exitValue for command " + commandName)
        process.exitValue()
      }, timeout)
    } catch {
      case e: java.util.concurrent.TimeoutException =>
        logger.debug("timeout reached")
        process.destroy()
        process.exitValue()
    }

    logger.debug("exit(" + code + ") for command " + commandName)
    code
  }

}
