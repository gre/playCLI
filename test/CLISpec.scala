package test

import org.specs2.mutable._

import play.api.test._
import play.api.test.Helpers._

import play.api.libs.iteratee._
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

  "CLI.pipe" should {
    
    "echo should work" in {
      val enum = Enumerator[Array[Byte]]()
      val text = "HelloWorld"
      val pipe = CLI.pipe("echo -n "+text)
      val result: Array[Byte] = Await.result(enum &> pipe |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (text)
    }
    
    "pipe the equivalent with cat" in {
      val items = List("toto\n", "tata\n", "titi\n").map { str => stringToBytes(str) }
      val enum = Enumerator.apply(items : _*)
      val result: Array[Byte] = Await.result(enum &> CLI.pipe("cat") |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (bytesJoin(items)) updateMessage("successive CLI.pipe result equals items")
    }
        
    "testing with grep" in {
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

    "pipe the equivalent with multiple cat" in {
      val items = Range(0, 100).map { i =>
        stringToBytes(Range(0, 200).map { _ => "HelloWorld " } mkString)
      }
      val enum = Enumerator.apply(items : _*)
      val result: Array[Byte] = Await.result(enum &> CLI.pipe("cat") &> CLI.pipe("cat") &> CLI.pipe("cat") |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (bytesJoin(items)) updateMessage("CLI.pipe result equals items")
    }
    
    
    // TODO a test which pipe multiple times
  }

  "CLI.enumerate" should {

    "throw IOException for unknown command" in {
      (CLI.enumerate("thisIsNotAValidCommand") |>>> bytesJoinConsumer) should throwA[java.io.IOException]
    }

    "echo" in {
      val text = "HelloWorld"
      val enum = CLI.enumerate("echo -n "+text)
      val result: Array[Byte] = Await.result(enum |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (stringToBytes(text))
    }

    "cat a file" in {
      val fileContent = Await.result(Enumerator.fromFile(wordsFile) |>>> bytesJoinConsumer, maxDuration)
      val enum = CLI.enumerate(Seq("cat", wordsFile.getAbsolutePath))
      val result: Array[Byte] = Await.result(enum |>>> bytesJoinConsumer, maxDuration)
      result must equalTo (fileContent) updateMessage("CLI.enumerate result equals fileContent")
    }

    "be used a lot without issues" in {
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

    "write some bytes in temporary file" in {
      val items = Range(0, 100).map { i =>
        stringToBytes(Range(0, 200).map { _ => "A" } mkString)
      }
      val enum = Enumerator(items : _*)
      val file = File.createTempFile("tmp", ".txt")
      val writer = CLI.consume(Process("cat") #> file)
      Await.result(enum |>>> writer, maxDuration)
      val fileContent = Await.result(Enumerator.fromFile(file) |>>> bytesJoinConsumer, maxDuration)
      val exceptContent = bytesJoin(items)
      fileContent must equalTo (exceptContent) updateMessage("fileContent equals enumerator values.")
    }
  }

}
