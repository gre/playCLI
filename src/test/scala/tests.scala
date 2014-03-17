package test

import playcli.CLI
import sys.process.Process
import play.api.libs.iteratee._
import org.specs2.mutable._
import java.util.concurrent.Executors
import concurrent.ExecutionContext
// import ExecutionContext.Implicits.global
import concurrent.{Promise, Future, Await}
import concurrent.duration.Duration
import collection.immutable.StringOps
import java.util.concurrent.{ TimeUnit }
import java.io.File


object Utils {

  implicit val context = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(64)).prepare()

  val maxDuration = Duration("9 second")

  val stringToBytes = (str: StringOps) => str.map(_.toByte).toArray
  val bytesJoinConsumer = Iteratee.fold[Array[Byte], Array[Byte]](Array[Byte]())((a, b) => a++b)
  val bytesJoin = (list: Seq[Array[Byte]]) => list.fold(Array[Byte]())((a, b) => a++b)
  val bytesFlattener = Enumeratee.mapFlatten[Array[Byte]]( bytes => Enumerator.apply(bytes : _*) ) 
  val bytesToTrimString = (bytes: Array[Byte]) => bytes.map(_.toChar).mkString.trim
    
  private val timer = new java.util.Timer()
  def timeout[A](message: => A, duration: Long, unit: TimeUnit = TimeUnit.MILLISECONDS)(implicit ec: ExecutionContext): Future[A] = {
    val p = Promise[A]()
    timer.schedule(new java.util.TimerTask { def run() { p.completeWith(Future(message)(ec)) } }, unit.toMillis(duration))
    p.future
  }
}

object Resources {
  import Utils._
  // Resources
  val wordsFile = new java.io.File(this.getClass.getClassLoader.getResource("words.txt").getFile())
  val bigItems = Range(0, 100).map { i => stringToBytes(Range(0, 200).map { _ => "HelloWorld " } mkString) }
  val bigItemsBytes = bytesJoin(bigItems)
  val bigEnum = Enumerator.apply(bigItems : _*)
}

class CLIenumerate extends Specification {
  import Utils._
  import Resources._

  "CLI.enumerate" should {

    "generate basic word (using echo)" in {
      val text = "HelloWorld"
      val enum = CLI.enumerate("echo -n "+text)
      val result: Array[Byte] = Await.result(enum |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (stringToBytes(text))
    }

    "enumerate a file (using cat)" in {
      val fileContent = Await.result(Enumerator.fromFile(wordsFile) |>>> bytesJoinConsumer, maxDuration)
      val enum = CLI.enumerate(Seq("cat", wordsFile.getAbsolutePath))
      val result: Array[Byte] = Await.result(enum |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (fileContent) updateMessage("CLI.enumerate result equals fileContent")
    }
    
    "enumerate a few bytes from an infinite file stream (using tail)" in {
      val file = File.createTempFile("tmp", ".txt")
      var writer = new java.io.FileWriter(file)
      val tail = CLI.enumerate("tail -f "+file.getAbsolutePath)

      val line = stringToBytes("hello world!\n")
      val linesToTake = 20
      val linesToWrite = 100
      
      // Write some lines
      Enumerator.generateM[Array[Byte]] { timeout(Some(line), 50) } &> 
        Enumeratee.take(linesToWrite) |>>> 
        Iteratee.foreach[Array[Byte]] { bytes => writer.write(bytes.map(_.toChar)); writer.flush() } map
        { _ => writer.close() }

      // tail some lines
      val retrieveSomeLines: Enumerator[Byte] = tail &> bytesFlattener &> Enumeratee.take(line.length*linesToTake)
      var result = Await.result(retrieveSomeLines |>>> Iteratee.getChunks, maxDuration)
      val expected = List.fill(linesToTake)(line).flatten
      result must equalTo (expected) updateMessage("result equals expected value.")
    }

    "be immutable and used a lot without issues (using echo)" in {
      val text = "HelloWorld"
      val enum = CLI.enumerate("echo -n "+text)
      val results = Range(0, 200) map { _ =>
        enum |>>> bytesJoinConsumer
      }
      for ( r <- results)
        Await.result(r, maxDuration)
    }
  }
}

class CLIpipe extends Specification {
  import Utils._
  import Resources._

  "CLI.pipe" should {

    "pipe the equivalent (using cat)" in {
      val result: Array[Byte] = Await.result(bigEnum &> CLI.pipe("cat") |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (bigItemsBytes) updateMessage("successive CLI.pipe result equals items")
    }
        
    "filter input (using grep)" in {
      val enum = Enumerator.fromFile(wordsFile)
      val grepParam = "superman"
      val exceptResult = stringToBytes(
"""superman
supermanhood
supermanifest
supermanism
supermanliness
supermanly
supermannish
""")
      val result: Array[Byte] = Await.result(enum &> CLI.pipe(Seq("grep", grepParam)) |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (exceptResult)
    }

    "pipe multiple commands (using cat)" in {
      val result: Array[Byte] = Await.result(bigEnum &> CLI.pipe("cat") &> CLI.pipe("cat") |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (bigItemsBytes) updateMessage("CLI.pipe result equals items")
    }

    "an Enumeratee instance is immutable (using cat)" in {
      val cat = CLI.pipe("cat")
      val chainOfCat = Range(0, 10).foldLeft(bigEnum) { (chain, i) => chain &> cat }
      val result: Array[Byte] = Await.result(chainOfCat |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (bigItemsBytes) updateMessage("CLI.pipe result equals items")
    }

    "work without any input (using echo)" in {
      val enum = Enumerator[Array[Byte]]() // Empty enumerator
      val text = "HelloWorld"
      val pipe = CLI.pipe("echo -n "+text)
      val result: Array[Byte] = Await.result(enum &> pipe |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (stringToBytes(text))
    }
    
    "stop itself when dealing with infinite stream (using head)" in {
      val nbOfLines = 3
      val line = stringToBytes("hello\n")
      val enum = Enumerator.generateM[Array[Byte]] {
        timeout(Some(line), 50)
      }
      val pipe = CLI.pipe(Seq("head", "-n", nbOfLines.toString), 8)
      val result: Array[Byte] = Await.result(enum &> pipe |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (bytesJoin(Range(0, nbOfLines) map { _ => line }))
    }

    "terminate when EOF and iteratee done" in {
      Await.result(Enumerator.eof &> CLI.pipe(Process("sleep 10"), terminateTimeout = 500) |>>> Done(Array[Byte](), Input.Empty), Duration("1 s"))
      true
    }
    
  }
}

class CLIconsume extends Specification {
  import Utils._
  import Resources._

  "CLI.consume" should {

    "mix with CLI.enumerate and CLI.pipe (using echo, wc, cat)" in {
      val file = File.createTempFile("tmp", ".txt")
      val value = List.fill(100)("foo\n").flatten.mkString
      val echoValue = CLI.enumerate(Seq("echo", "-n", value), 1)
      val wcBytes = CLI.pipe("wc -c")
      val writeInFile = CLI.consume(Process("cat") #> file)
      val exitCode = Await.result(echoValue &> wcBytes |>>> writeInFile, maxDuration)
      val fileContent = Await.result(Enumerator.fromFile(file) |>>> bytesJoinConsumer, maxDuration)
      exitCode must equalTo (0)
      bytesToTrimString(fileContent) must equalTo (""+value.length) updateMessage("fileContent equals enumerator values.")
    }

    "write some bytes in temporary file (using cat)" in {
      val file = File.createTempFile("tmp", ".txt")
      val exitCode = Await.result(bigEnum |>>> CLI.consume(Process("cat") #> file), maxDuration)
      val fileContent = Await.result(Enumerator.fromFile(file) |>>> bytesJoinConsumer, maxDuration)
      exitCode must equalTo (0)
      fileContent must equalTo (bigItemsBytes) updateMessage("fileContent equals enumerator values.")
    }

    "terminate with EOF" in {
      val exitCode = Await.result(Enumerator.eof |>>> CLI.consume(Process("sleep 10"), 500), Duration("1 s"))
      exitCode must not equalTo (0)
    }
  }

}
