package controllers

import scala.concurrent.ExecutionContext.Implicits.global
import java.io._
import sys.process._

import play.api._
import play.api.mvc._

import play.api.libs.iteratee._

import play.api.Play.current
import play.api.libs.ws._

import cli.CLI
import codecs._


/**
 * Example dependencies:
 * curl
 * ffmpeg
 * sox
 */
object Application extends Controller {

  val bytesFlattener = Enumeratee.mapFlatten[Array[Byte]]( bytes => Enumerator.apply(bytes : _*) ) 

  // Pipe examples
  val grep = (q: String) => CLI.pipe(Seq("grep", q), 64)
  val addEchoToOgg = CLI.pipe("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
  val scaleVideoHalf = CLI.pipe("ffmpeg -v warning -i pipe:0 -vf scale=iw/2:-1 -f avi pipe:1")

  // Streams
  //val (radioHeaders, radioStream) = AudioFormats.OggChunker(proxyBroadcast("http://radio.hbr1.com:19800/ambient.ogg") &> bytesFlattener)
  val (echoRadioHeaders, echoRadioStream) = {
    val radioStream = proxyUnicast("http://radio.hbr1.com:19800/ambient.ogg")
    val (headers, chunked) = OggChunker(radioStream &> addEchoToOgg &> bytesFlattener)
    val (broadcast, _) = Concurrent.broadcast(chunked)
    (headers, broadcast)
  }

  // Consume a stream with url and push it in a socket with f
  // FIXME, how to tell WS to stop when socket is done?
  def proxy[A] (url: String)(f: Socket.Out[Array[Byte]] => Iteratee[Array[Byte], A]): Iteratee[Array[Byte], Unit] => Unit = 
    (socket: Socket.Out[Array[Byte]]) => WS.url(url).withTimeout(-1).get(headers => f(socket))

  // Proxify a stream forever
  def proxyBroadcast (url: String) : Enumerator[Array[Byte]] = {
    val (enumerator, channel) = Concurrent.broadcast[Array[Byte]]
    WS.url(url).withTimeout(-1).get(headers => Iteratee.foreach[Array[Byte]] { bytes => channel.push(bytes) })
    enumerator
  }
  
  // Proxify a stream forever
  def proxyUnicast (url: String) : Enumerator[Array[Byte]] = {
    Concurrent.unicast[Array[Byte]] { channel =>
      WS.url(url).withTimeout(-1).get(headers => Iteratee.foreach[Array[Byte]] { bytes => channel.push(bytes) })
    }
  }


  def index = Action(Ok(views.html.index()))

  // grep words
  def grepDictionary(search: String) = Action {
    val dictionary = Enumerator.fromFile(new File("/usr/share/dict/words"))
    Ok.stream(dictionary &> grep(search))
      .withHeaders(CONTENT_TYPE -> "text/plain")
  }

  // Re-stream a web radio by adding echo with sox
  def webRadioWithEcho = Action {
    val src = "http://radio.hbr1.com:19800/ambient.ogg"
    Ok.stream(echoRadioHeaders >>> echoRadioStream)
      .withHeaders(CONTENT_TYPE -> "audio/ogg")
  }

  // Retrieve an online video, resize it, stream it
  def downloadReEncodeAndStreamVideo = Action {
    val src = "http://ftp.nluug.nl/pub/graphics/blender/demo/movies/Sintel.2010.1080p.mkv"
    Ok.stream(proxy(src)(scaleVideoHalf &> _))
      .withHeaders(CONTENT_TYPE -> "video/avi")
  }
  
  // Use a local video, resize it, stream it
  def reEncodeAndStreamVideo = Action {
    val stream = Enumerator.fromFile(Play.getFile("Sintel.2010.1080p.mkv")) // download it on sintel.org
    Ok.stream(stream &> scaleVideoHalf)
      .withHeaders(CONTENT_TYPE -> "video/avi")
  }

  // consume a ogg sound, add an echo effect and store in a /tmp/out.ogg file
  def audioEchoEffectGenerate = Action {
    val file = File.createTempFile("sample_with_echo_", ".ogg") // handle myself the output
    val enum = Enumerator.fromFile(Play.getFile("conf/exemple.ogg"))
    val addEchoToLocalOgg = CLI.consume(Process("sox -t ogg - -t ogg - echo 0.5 0.7 60 1") #> file)(_)
    AsyncResult {
      addEchoToLocalOgg(enum) map { _ =>
        Ok("'"+file.getAbsolutePath+"' file has been generated.\n")
      }
    }
  }

  // List all files in this Play project
  def find = Action {
    Ok.stream(CLI.enumerate("find .") >>> Enumerator.eof)
      .withHeaders(CONTENT_TYPE -> "text/plain")
  }
  
  // Retrieve a webpage and display it (of course, this is just for the demo, I won't use curl, prefer using WS)
  def curl = Action {
    Ok.stream(CLI.enumerate("curl -s http://blog.greweb.fr/") >>> Enumerator.eof)
      .withHeaders(CONTENT_TYPE -> "text/html")
  }
  
}
