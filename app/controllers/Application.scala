package controllers

import play.api._
import play.api.mvc._

import play.api.libs.iteratee._

import play.api.Play.current
import play.api.libs.ws._

import cli.CLI

object Application extends Controller {

  def index = find

  // Re-stream a web radio by adding echo with sox
  def webRadioWithEcho = Action {
    val radioSrc = "http://radio.hbr1.com:19800/ambient.ogg"
    var radio = Concurrent.unicast[Array[Byte]]( channel => {
      WS.url(radioSrc).get { header =>
        Iteratee.foreach[Array[Byte]](channel.push(_))
      }
    }, () => ())
    val addEcho = CLI.pipe("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
    Ok.stream(radio &> addEcho &> Concurrent.dropInputIfNotReady(50))
      .withHeaders(
        CONTENT_TYPE -> "audio/ogg"
      )
  }

  // List all files in this Play project
  def find = Action {
    Ok.stream(CLI.enumerate("find .") >>> Enumerator.eof).withHeaders(
      CONTENT_TYPE -> "text/plain"
    )
  }
  
  // Retrieve a webpage and display it (of course, this is just for the demo, I won't use curl, prefer using WS)
  def curl = Action {
    Ok.stream(CLI.enumerate("curl -s http://blog.greweb.fr/") >>> Enumerator.eof).withHeaders(
      CONTENT_TYPE -> "text/html"
    )
  }

  // consume a ogg sound, add an echo effect and store in a /tmp/out.ogg file
  def audioEchoEffectGenerate = Action {
    val consumer = CLI.consume("sox -t ogg - /tmp/out.ogg echo 0.5 0.7 60 1")
    Enumerator.fromFile(Play.getFile("conf/exemple.ogg"))(consumer)
    Ok("'/tmp/out.ogg' file has been generated.")
  }
  
}
