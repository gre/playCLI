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

  def stringToBytes = (str: StringOps) => str.map(_.toByte).toArray

  val bytesJoin = Iteratee.fold[Array[Byte], Array[Byte]](Array[Byte]())((a, b) => a++b)

  /*
  "CLI.pipe" should {
    
    "pipe the equivalent with cat" in {
      val items = List("toto\n", "tata\n", "titi\n").map { str => stringToBytes(str) }
      val enum = Enumerator.apply(items : _*)
      val it = Iteratee.fold[Array[Byte], Array[Byte]](Array[Byte]())((a, b) => a++b)
      val result: Array[Byte] = Await.result(enum &> CLI.pipe("cat") |>>> it, Duration("1 second"))
      result must equalTo (items)
    }

    // TODO a test which pipe multiple times
  }
  */

  "CLI.enumerate" should {

    "echo" in {
      val text = "HelloWorld"
      val enum = CLI.enumerate("echo -n "+text)
      val result: Array[Byte] = Await.result(enum |>>> bytesJoin, Duration.Inf)
      result must equalTo (stringToBytes(text))
    }

    "cat a file" in {
      val file = new File("test/integers.txt")
      val fileContent = Await.result(Enumerator.fromFile(file) |>>> bytesJoin, Duration.Inf)
      val enum = CLI.enumerate("cat "+file.getAbsolutePath)
      val result: Array[Byte] = Await.result(enum |>>> bytesJoin, Duration.Inf)
      result must equalTo (fileContent) updateMessage("CLI.enumerate result equals fileContent")
    }

    "be used a lot without issues" in {
      val text = "HelloWorld"
      val results = Range(0, 200) map { _ =>
        val enum = CLI.enumerate("echo -n "+text)
        enum |>>> bytesJoin
      }
      for ( r <- results)
        Await.result(r, Duration("1 second"))
    }
  }
  
  "CLI.consume" should {

    // FIXME: This test doesn't work
    "write some bytes in temporary file" in {
      val items = List("foo", "bar", "fooooo").map { str => stringToBytes(str) }
      val enum = Enumerator(items : _*)
      val file = File.createTempFile("tmp.txt", null)
      val writer = CLI.consume(Process("cat") #> file)
      Await.result(enum(writer), Duration.Inf)
      val fileContent = Await.result(Enumerator.fromFile(file) |>>> bytesJoin, Duration.Inf)
      fileContent must equalTo (items.fold(Array[Byte]())((a, b) => a++b))
    }

  }

}
