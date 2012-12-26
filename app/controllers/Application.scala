package controllers

import play.api._
import play.api.mvc._

import play.api.libs.iteratee._

import play.api.Play.current
import play.api.libs.ws._

import cli.CLI

import sys.process._
import java.io._

import scala.concurrent.ExecutionContext.Implicits.global

object Application extends Controller {

  def index = Action(Ok(views.html.index()))

  // grep words
  def grepDictionary(search: String) = Action {
    Ok.stream(
      Enumerator.fromFile(new File("/usr/share/dict/words")) &>
      CLI.pipe(Seq("grep", search), 64)
    ) withHeaders (CONTENT_TYPE -> "text/plain")
  }

  // Re-stream a web radio by adding echo with sox
  def webRadioWithEcho = Action {
    val src = "http://radio.hbr1.com:19800/ambient.ogg"
    var stream = Concurrent.unicast[Array[Byte]]( channel => {
      WS.url(src).get { header => Iteratee.foreach[Array[Byte]](channel.push(_)) }
    }, () => ())
    val addEcho = CLI.pipe("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
    Ok.stream(stream &> addEcho).withHeaders(CONTENT_TYPE -> "audio/ogg")
  }

  // Retrieve an online video, resize it, stream it
  def reEncodeAndStreamVideo = Action {
    var src = "http://ftp.nluug.nl/pub/graphics/blender/demo/movies/glsl_bird.avi"
    var stream = Concurrent.unicast[Array[Byte]]( channel => {
      WS.url(src).get { header => Iteratee.foreach[Array[Byte]](channel.push(_)) }
    }, () => ())
    val scaleHalf = CLI.pipe("ffmpeg -i pipe:0 -vf scale=iw/2:-1 -f avi pipe:1")
    Ok.stream(stream &> scaleHalf).withHeaders(CONTENT_TYPE -> "video/avi")
  }

  // List all files in this Play project
  def find = Action {
    Ok.stream(CLI.enumerate("find .") >>> Enumerator.eof)
      .withHeaders(
        CONTENT_TYPE -> "text/plain"
      )
  }
  
  // Retrieve a webpage and display it (of course, this is just for the demo, I won't use curl, prefer using WS)
  def curl = Action {
    Ok.stream(CLI.enumerate(Process("curl -s http://blog.greweb.fr/")) >>> Enumerator.eof)
      .withHeaders(
        CONTENT_TYPE -> "text/html"
      )
  }

  // consume a ogg sound, add an echo effect and store in a /tmp/out.ogg file
  def audioEchoEffectGenerate = Action {
    val file = File.createTempFile("sample_with_echo_", ".ogg") // handle myself the output
    val enum = Enumerator.fromFile(Play.getFile("conf/exemple.ogg"))
    val result = CLI.consume(Process("sox -t ogg - -t ogg - echo 0.5 0.7 60 1") #> file)(enum)
    AsyncResult {
      result.map { _ =>
        Ok("'"+file.getAbsolutePath+"' file has been generated.")
      }
    }
  }
  
}
