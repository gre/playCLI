package test

import org.specs2.mutable._

import play.api.test._
import play.api.test.Helpers._
import play.api.libs.iteratee._
import play.api.libs.concurrent.Promise.timeout
import cli.CLI

import collection.immutable.StringOps

import scala.concurrent.ExecutionContext.Implicits.global
import concurrent.{Future, Await}
import concurrent.duration.Duration

import scala.sys.process.Process

import java.io.File

class CLITest extends Specification {

  val stringToBytes = (str: StringOps) => str.map(_.toByte).toArray
  val bytesJoinConsumer = Iteratee.fold[Array[Byte], Array[Byte]](Array[Byte]())((a, b) => a++b)
  val bytesJoin = (list: Seq[Array[Byte]]) => list.fold(Array[Byte]())((a, b) => a++b)
  val maxDuration = Duration("1 second")
  val wordsFile = new java.io.File("test/words.txt")

  // Generic items and enum
  val bigItems = Range(0, 100).map { i => stringToBytes(Range(0, 200).map { _ => "HelloWorld " } mkString) }
  val bigItemsBytes = bytesJoin(bigItems)
  val bigEnum = Enumerator.apply(bigItems : _*)
    

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

    "an Enumeratee instance is stateless (using cat)" in {
      val cat = CLI.pipe("cat")
      val chainOfCat = Range(0, 10).foldLeft(bigEnum) { (chain, i) => chain &> cat }
      val result: Array[Byte] = Await.result(chainOfCat |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (bigItemsBytes) updateMessage("CLI.pipe result equals items")
    }

    "should work without any input (using echo)" in {
      val enum = Enumerator[Array[Byte]]() // Empty enumerator
      val text = "HelloWorld"
      val pipe = CLI.pipe("echo -n "+text)
      val result: Array[Byte] = Await.result(enum &> pipe |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (stringToBytes(text))
    }
    
    "should stop itself when dealing with infinite stream (using head)" in {
      val nbOfLines = 3
      val line = stringToBytes("hello\n")
      val enum = Enumerator.generateM[Array[Byte]] {
        timeout(Some(line), 50)
      }
      val pipe = CLI.pipe(Seq("head", "-n", nbOfLines.toString), 8)
      val result: Array[Byte] = Await.result(enum &> pipe |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (bytesJoin(Range(0, nbOfLines) map { _ => line }))
    }
    
  }

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

    "be used a lot without issues (using echo)" in {
      val text = "HelloWorld"
      val results = Range(0, 200) map { _ =>
        val enum = CLI.enumerate("echo -n "+text)
        enum |>>> bytesJoinConsumer
      }
      for ( r <- results)
        Await.result(r, maxDuration)
    }
  }
  
  "CLI.consume" should {

    "write some bytes in temporary file (using cat)" in {
      val file = File.createTempFile("tmp", ".txt")
      val exitCode = Await.result(CLI.consume(Process("cat") #> file)(bigEnum), maxDuration)
      val fileContent = Await.result(Enumerator.fromFile(file) |>>> bytesJoinConsumer, maxDuration)
      exitCode must equalTo (0)
      fileContent must equalTo (bigItemsBytes) updateMessage("fileContent equals enumerator values.")
    }
  }

}
